#!/usr/bin/env python3
"""Deterministic source-level security checks for release review."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "app/src/main"
MANIFEST = SOURCE_ROOT / "AndroidManifest.xml"
VIEW_MODEL = SOURCE_ROOT / "java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt"
ATTACHMENT_POLICY = ROOT / "core-domain/src/commonMain/kotlin/com/airi/core/attachments/AttachmentPolicy.kt"
UNTRUSTED_ENVELOPE = SOURCE_ROOT / "java/com/airi/assistant/security/UntrustedContentEnvelope.kt"
PROMPT_SERVICE = SOURCE_ROOT / "java/com/airi/assistant/domain/prompt/PromptService.kt"
COMMUNITY_SKILLS = SOURCE_ROOT / "java/com/airi/assistant/community/CommunitySkillHub.kt"
CUSTOM_SKILL_EXECUTOR = SOURCE_ROOT / "java/com/airi/assistant/domain/customskill/CustomSkillExecutor.kt"
CUSTOM_SKILL_SECURITY = SOURCE_ROOT / "java/com/airi/assistant/domain/customskill/CustomSkillSecurity.kt"
SANDBOX_EXECUTOR = SOURCE_ROOT / "java/com/airi/assistant/agent/sandbox/SandboxExecutor.kt"
SANDBOX_ISOLATION = SOURCE_ROOT / "java/com/airi/assistant/agent/sandbox/SandboxIsolationPolicy.kt"
DOCUMENT_AGENT = SOURCE_ROOT / "java/com/airi/assistant/agent/subagent/impl/DocumentProcessorAgent.kt"
WEBSITE_READER = SOURCE_ROOT / "java/com/airi/assistant/ai/skills/impl/WebsiteReaderSkill.kt"
SKILL_RESULT_FORMATTER = SOURCE_ROOT / "java/com/airi/assistant/ai/skills/SkillToolResultFormatter.kt"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

HIGH_CONFIDENCE_SECRET_PATTERNS = {
    "OpenAI-style key": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
    "Google API key": re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b"),
    "GitHub personal token": re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{30,}\b"),
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "Private key block": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
}


def scan_secret_literals() -> list[str]:
    findings: list[str] = []
    candidates = [
        path for path in ROOT.rglob("*")
        if path.is_file()
        and ".git" not in path.parts
        and "build" not in path.parts
        and path != ROOT / "app/google-services.json"
        and path.suffix in {".kt", ".java", ".xml", ".json", ".properties", ".gradle", ".kts", ".yml", ".yaml"}
    ]
    for path in candidates:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for label, pattern in HIGH_CONFIDENCE_SECRET_PATTERNS.items():
            if pattern.search(text):
                findings.append(f"{label}: {path.relative_to(ROOT)}")
    return findings


def manifest_checks() -> list[tuple[str, bool, str]]:
    root = ET.parse(MANIFEST).getroot()
    application = root.find("application")
    if application is None:
        return [("Manifest application", False, "application element missing")]
    provider = next(
        (item for item in application.findall("provider") if item.attrib.get(ANDROID_NS + "name") == "androidx.core.content.FileProvider"),
        None,
    )
    return [
        (
            "No cleartext traffic override",
            application.attrib.get(ANDROID_NS + "usesCleartextTraffic") != "true",
            "usesCleartextTraffic is not enabled",
        ),
        (
            "FileProvider is non-exported",
            provider is not None and provider.attrib.get(ANDROID_NS + "exported") == "false",
            "FileProvider requires non-exported access",
        ),
        (
            "FileProvider grants URI access",
            provider is not None and provider.attrib.get(ANDROID_NS + "grantUriPermissions") == "true",
            "FileProvider uses explicit URI grants",
        ),
    ]


def firebase_client_config_check() -> tuple[str, bool, str]:
    config_path = ROOT / "app/google-services.json"
    production_sha1 = "6c6efe1241eafe28748e036ae1472dc93d7d911d"
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        clients = config.get("client", [])
        package_names = {
            client.get("client_info", {}).get("android_client_info", {}).get("package_name")
            for client in clients
        }
        oauth_sha1_fingerprints = {
            oauth.get("android_info", {}).get("certificate_hash", "").lower().replace(":", "")
            for client in clients
            for oauth in client.get("oauth_client", [])
            if oauth.get("client_type") == 1
        }
        return (
            "Firebase production Google Sign-In configuration",
            "com.airi.assistant" in package_names and production_sha1 in oauth_sha1_fingerprints,
            "google-services.json declares the AIRI package and the Android OAuth client for the production signing SHA-1",
        )
    except (OSError, json.JSONDecodeError):
        return ("Firebase production Google Sign-In configuration", False, "google-services.json could not be parsed")


def source_checks() -> list[tuple[str, bool, str]]:
    view_model = VIEW_MODEL.read_text(encoding="utf-8")
    policy = ATTACHMENT_POLICY.read_text(encoding="utf-8")
    envelope = UNTRUSTED_ENVELOPE.read_text(encoding="utf-8")
    prompt = PROMPT_SERVICE.read_text(encoding="utf-8")
    community = COMMUNITY_SKILLS.read_text(encoding="utf-8")
    custom_skill = CUSTOM_SKILL_EXECUTOR.read_text(encoding="utf-8")
    custom_skill_security = CUSTOM_SKILL_SECURITY.read_text(encoding="utf-8")
    sandbox = SANDBOX_EXECUTOR.read_text(encoding="utf-8")
    isolation = SANDBOX_ISOLATION.read_text(encoding="utf-8")
    document = DOCUMENT_AGENT.read_text(encoding="utf-8")
    website_reader = WEBSITE_READER.read_text(encoding="utf-8")
    skill_formatter = SKILL_RESULT_FORMATTER.read_text(encoding="utf-8")
    return [
        (
            "Private attachment persistence",
            'File(appContext.filesDir, "attachments")' in view_model,
            "attachments are copied into app-private filesDir",
        ),
        (
            "Bounded attachment sizes",
            "MAX_ATTACHMENT_BYTES" in policy and "MAX_TEXT_ATTACHMENT_BYTES" in policy,
            "attachment and text limits are enforced before staging",
        ),
        (
            "Untrusted text attachment boundary",
            "BEGIN UNTRUSTED TEXT ATTACHMENT" in view_model,
            "text attachment content is explicitly bounded and delimited",
        ),
        (
            "No source URI persistence",
            "source URIs and absolute paths" in view_model and "attachmentMetadataJson" in view_model,
            "message metadata stores generated local names rather than picker URIs",
        ),
        (
            "Untrusted content is structurally encoded and bounded",
            "MAX_CONTENT_CHARS" in envelope and '"content"' in envelope and "redactCredentials" in envelope,
            "external/document/RAG text uses bounded JSON encoding with credential redaction",
        ),
        (
            "Prompt injection has no policy authority",
            "SECURITY BOUNDARY — NON-TRUSTED CONTENT" in prompt and "bypass approval" in prompt and "runtime permission" in prompt,
            "system prompt explicitly rejects authority-bearing instructions from untrusted data",
        ),
        (
            "Community skill fetches are public-host and redirect guarded",
            ".followRedirects(false)" in community and ".followSslRedirects(false)" in community and "HostAddressPolicy.PublicOnlyDns" in community and "SearchSourcePolicy.evaluateRead" in community and "sourceUrl   = sanitizeSourceReference(sourceUrl)" in community and "sourceUrl = file.absolutePath" not in community,
            "skill imports and ping requests deny redirects, resolve only public hosts, and persist only sanitized source provenance",
        ),
        (
            "Custom skill requests refuse redirects and recheck resolved hosts",
            ".followRedirects(false)" in custom_skill and ".followSslRedirects(false)" in custom_skill and "HostAddressPolicy.PublicOnlyDns" in custom_skill and "queryNames.none(::isSensitiveName)" in custom_skill_security and "uri.userInfo == null" in custom_skill_security,
            "dynamic endpoints reject credential query names and URL user-info, cannot follow redirects, and resolve only public addresses",
        ),
        (
            "Policy-only terminal is blocked in release builds",
            "SandboxIsolationPolicy.mayExecuteShell(BuildConfig.DEBUG)" in sandbox and "OS_SANDBOX_AVAILABLE: Boolean = false" in isolation,
            "release shell remains disabled until OS-level isolation is implemented",
        ),
        (
            "Document extraction uses untrusted-content envelope",
            "UntrustedContentEnvelope.wrap(\"user-attachment\"" in document,
            "extracted PDF/text content is separated from instructions before synthesis",
        ),
        (
            "Website results omit raw URL credentials",
            '"source_host" to sourceHost' in website_reader and '"url" to url' not in website_reader and "Could not fetch content from the requested URL" in website_reader,
            "website result metadata retains only the host and suppresses raw error/URL values",
        ),
        (
            "All skill-result fields share credential redaction",
            "UntrustedContentEnvelope.redactSensitiveValues(value)" in skill_formatter,
            "data, errors, metadata, and nested tool outputs use the central credential redactor",
        ),
    ]


def main() -> int:
    secret_findings = scan_secret_literals()
    checks = manifest_checks() + [firebase_client_config_check()] + source_checks()
    report = {
        "checks": [
            {"name": name, "status": "PASS" if ok else "FAIL", "detail": detail}
            for name, ok, detail in checks
        ],
        "secret_findings": secret_findings,
        "status": "PASS" if all(ok for _, ok, _ in checks) and not secret_findings else "FAIL",
    }
    for item in report["checks"]:
        print(f"[{item['status']}] {item['name']}: {item['detail']}")
    if secret_findings:
        for finding in secret_findings:
            print(f"[FAIL] Secret literal: {finding}")
    print(json.dumps(report, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
