#!/usr/bin/env python3
"""Validate the source-level contract of AIRI paired remote control."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REMOTE_ROOT = ROOT / "core-domain/src/commonMain/kotlin/com/airi/core/remote"
POLICY = REMOTE_ROOT / "RemoteControlPolicy.kt"
DESKTOP_DISPATCHER = ROOT / "app-desktop/src/main/kotlin/com/airi/desktop/PairedDesktopControl.kt"
RULES = ROOT / "firestore.rules"
EMULATOR_TEST = ROOT / "remote-control-tests/firestore.rules.test.mjs"


@dataclass(frozen=True)
class Check:
    name: str
    status: str
    detail: str


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def command_types(policy: str) -> set[str]:
    match = re.search(
        r"enum class RemoteControlCommandType\s*\{(?P<body>[^}]*)\}",
        policy,
        flags=re.DOTALL,
    )
    if not match:
        return set()
    return set(re.findall(r"\b([A-Z][A-Z0-9_]+)\b", match.group("body")))


def check(name: str, valid: bool, detail: str) -> Check:
    return Check(name, "SOURCE_VERIFIED" if valid else "FAIL", detail)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", dest="json_path", type=Path)
    args = parser.parse_args()

    required = [
        REMOTE_ROOT / "RemoteControlPolicy.kt",
        REMOTE_ROOT / "DevicePairingPolicy.kt",
        REMOTE_ROOT / "RemoteControlSecurityPolicy.kt",
        REMOTE_ROOT / "RemoteControlPorts.kt",
        EMULATOR_TEST,
        RULES,
    ]
    checks = [check(f"required file: {path.relative_to(ROOT)}", path.is_file(), "required by paired-control gate") for path in required]
    if not POLICY.is_file() or not RULES.is_file():
        for item in checks:
            print(f"[{item.status}] {item.name}: {item.detail}")
        return 1

    policy = read(POLICY)
    rules = read(RULES)
    core_commands = command_types(policy)
    rules_commands = set(re.findall(r"'([A-Z][A-Z0-9_]+)'", rules))
    dispatcher = read(DESKTOP_DISPATCHER) if DESKTOP_DISPATCHER.is_file() else ""
    missing_in_rules = sorted(core_commands - rules_commands)
    missing_in_dispatcher = sorted(command for command in core_commands if command not in dispatcher)

    checks.extend(
        [
            check("non-empty core command allowlist", bool(core_commands), f"commands={sorted(core_commands)}"),
            check("Firestore command allowlist covers core", not missing_in_rules, f"missing={missing_in_rules}"),
            check("Desktop dispatcher covers core", not missing_in_dispatcher, f"missing={missing_in_dispatcher}"),
            check("command text budget", "MAX_TEXT_REQUEST_CHARS = 8_000" in policy, "expected maximum is 8000 characters"),
            check("session expiry policy", "expiresAtMillis" in policy and "nowMillis >= session.expiresAtMillis" in policy, "expiry is enforced before acceptance"),
            check("replay policy", "lastAcceptedSequence" in policy and "command.sequence <= session.lastAcceptedSequence" in policy, "sequence is monotonic"),
            check("revocation policy", "session.revoked" in policy, "revoked sessions are rejected"),
        ]
    )

    for item in checks:
        print(f"[{item.status}] {item.name}: {item.detail}")
    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps([asdict(item) for item in checks], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if any(item.status == "FAIL" for item in checks) else 0


if __name__ == "__main__":
    sys.exit(main())
