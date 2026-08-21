#!/usr/bin/env python3
"""Perform static security checks for AIRI paired remote control."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REMOTE_ROOT = ROOT / "core-domain/src/commonMain/kotlin/com/airi/core/remote"
RULES = ROOT / "firestore.rules"


@dataclass(frozen=True)
class Finding:
    check: str
    status: str
    detail: str


def source_files() -> list[Path]:
    files = list(REMOTE_ROOT.glob("*.kt"))
    desktop = ROOT / "app-desktop/src/main/kotlin/com/airi/desktop/PairedDesktopControl.kt"
    if desktop.is_file():
        files.append(desktop)
    if RULES.is_file():
        files.append(RULES)
    return sorted(files)


def contains_all(text: str, markers: tuple[str, ...]) -> bool:
    return all(marker in text for marker in markers)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", dest="json_path", type=Path)
    args = parser.parse_args()

    policy_path = REMOTE_ROOT / "RemoteControlPolicy.kt"
    pairing_path = REMOTE_ROOT / "DevicePairingPolicy.kt"
    security_path = REMOTE_ROOT / "RemoteControlSecurityPolicy.kt"
    failures: list[Finding] = []
    findings: list[Finding] = []

    for path in (policy_path, pairing_path, security_path, RULES):
        status = "SOURCE_VERIFIED" if path.is_file() else "FAIL"
        finding = Finding("required source", status, str(path.relative_to(ROOT)))
        findings.append(finding)
        if status == "FAIL":
            failures.append(finding)
    if failures:
        for item in findings:
            print(f"[{item.status}] {item.check}: {item.detail}")
        return 1

    policy = policy_path.read_text(encoding="utf-8")
    pairing = pairing_path.read_text(encoding="utf-8")
    security = security_path.read_text(encoding="utf-8")
    rules = RULES.read_text(encoding="utf-8")
    combined = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in source_files())

    checks = [
        ("expiry enforcement", contains_all(policy, ("expiresAtMillis", "nowMillis >= session.expiresAtMillis")), "expired session rejection"),
        ("replay enforcement", contains_all(policy, ("lastAcceptedSequence", "command.sequence <= session.lastAcceptedSequence")), "monotonic sequence rejection"),
        ("revocation enforcement", "session.revoked" in policy and "revoked" in pairing.lower(), "revoked sessions and devices are rejected"),
        ("request rate limit", contains_all(security, ("RemoteRateLimit", "maxEvents", "windowMillis", "decideRateLimit")), "bounded request window"),
        ("audit redaction", contains_all(security, ("data class RemoteAuditEvent", "fun audit")) and "payload" not in security and "token" not in security.lower(), "audit record excludes payloads and tokens"),
        ("Firestore owner isolation", "request.auth.uid == userId" in rules, "all client paths are scoped to the authenticated owner"),
        ("Firestore broad-access ban", "allow read, write: if true" not in rules and "allow read, write: if request.auth != null" not in rules, "no broad read/write rule"),
        ("Firestore command immutability", "allow update, delete: if false;" in rules, "commands are append-only to clients"),
        ("Firestore session client-write ban", "allow create, update, delete: if false;" in rules, "sessions are relay-managed"),
    ]
    for name, passed, detail in checks:
        finding = Finding(name, "SOURCE_VERIFIED" if passed else "FAIL", detail)
        findings.append(finding)
        if not passed:
            failures.append(finding)

    forbidden = {
        "raw socket": r"\b(ServerSocket|Socket\(|DatagramSocket|java\.net\.)",
        "cleartext HTTP": r"http://",
        "service account": r"service[_ -]?account|GOOGLE_APPLICATION_CREDENTIALS",
        "embedded secret": r"(?i)(api[_-]?key|secret|token)\s*[:=]\s*[\"'][^\"']{12,}",
    }
    for name, pattern in forbidden.items():
        match = re.search(pattern, combined)
        finding = Finding(name, "FAIL" if match else "SOURCE_VERIFIED", "forbidden transport or secret marker" if match else "not present in paired-control sources")
        findings.append(finding)
        if match:
            failures.append(finding)

    for item in findings:
        print(f"[{item.status}] {item.check}: {item.detail}")
    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps([asdict(item) for item in findings], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
