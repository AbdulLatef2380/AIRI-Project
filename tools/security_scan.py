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
ATTACHMENT_POLICY = SOURCE_ROOT / "java/com/airi/assistant/domain/AttachmentPolicy.kt"
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
    try:
        config = json.loads(config_path.read_text(encoding="utf-8"))
        clients = config.get("client", [])
        package_names = {
            client.get("client_info", {}).get("android_client_info", {}).get("package_name")
            for client in clients
        }
        return (
            "Firebase client configuration scope",
            "com.airi.assistant" in package_names,
            "google-services.json declares the AIRI Android package",
        )
    except (OSError, json.JSONDecodeError):
        return ("Firebase client configuration scope", False, "google-services.json could not be parsed")


def source_checks() -> list[tuple[str, bool, str]]:
    view_model = VIEW_MODEL.read_text(encoding="utf-8")
    policy = ATTACHMENT_POLICY.read_text(encoding="utf-8")
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
