#!/usr/bin/env python3
"""Validate AIRI Firestore paired-control rules and optionally run the Emulator suite."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "firestore.rules"
FIREBASE_CONFIG = ROOT / "firebase.json"
INDEXES = ROOT / "firestore.indexes.json"
TEST_DIR = ROOT / "remote-control-tests"
TEST_FILE = TEST_DIR / "firestore.rules.test.mjs"


@dataclass(frozen=True)
class Check:
    name: str
    status: str
    detail: str


def check(name: str, passed: bool, detail: str) -> Check:
    return Check(name, "SOURCE_VERIFIED" if passed else "FAIL", detail)


def static_checks() -> list[Check]:
    required = [RULES, FIREBASE_CONFIG, INDEXES, TEST_FILE, TEST_DIR / "package.json"]
    checks = [check(f"required file: {path.relative_to(ROOT)}", path.is_file(), "paired-control rules test asset") for path in required]
    if not RULES.is_file():
        return checks

    rules = RULES.read_text(encoding="utf-8")
    required_paths = (
        "match /users/{userId}/devices/{deviceId}",
        "match /sessions/{sessionId}",
        "match /commands/{commandId}",
        "match /events/{eventId}",
    )
    checks.extend(
        [
            check("no unconditional access", "allow read, write: if true" not in rules, "unconditional rule is forbidden"),
            check("no broad authenticated access", "allow read, write: if request.auth != null" not in rules, "owner/device scoping is required"),
            check("authenticated owner isolation", "request.auth.uid == userId" in rules, "owner is bound to path userId"),
            check("session expiry validation", "expiresAt > request.time" in rules, "commands require active session"),
            check("command field allowlist", "request.resource.data.keys().hasOnly" in rules, "unexpected command fields are denied"),
            check("command payload bound", "payload.text.size() <= 8000" in rules or "text.size() <= 8000" in rules, "text payload has maximum size"),
            check("client command immutability", "allow update, delete: if false;" in rules, "commands cannot be rewritten or removed by clients"),
        ]
    )
    checks.extend(check(f"route: {route}", route in rules, "required scoped Firestore path") for route in required_paths)
    return checks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-emulator", action="store_true")
    parser.add_argument("--json", dest="json_path", type=Path)
    args = parser.parse_args()

    checks = static_checks()
    failure = any(item.status == "FAIL" for item in checks)
    for item in checks:
        print(f"[{item.status}] {item.name}: {item.detail}")

    if args.run_emulator and not failure:
        result = subprocess.run(["pnpm", "test:rules"], cwd=TEST_DIR, check=False)
        emulator_status = "TESTED" if result.returncode == 0 else "FAIL"
        checks.append(Check("Firestore Emulator suite", emulator_status, f"exit={result.returncode}"))
        print(f"[{emulator_status}] Firestore Emulator suite: exit={result.returncode}")
        failure = result.returncode != 0

    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps([asdict(item) for item in checks], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if failure else 0


if __name__ == "__main__":
    sys.exit(main())
