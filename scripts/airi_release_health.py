#!/usr/bin/env python3
"""Checks the release workflow's guardrails without reading any secret values."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "android_build.yml"
REQUIRED_SECRETS = ("KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")


def require(content: str, expected: str, label: str, failures: list[str]) -> None:
    if expected not in content:
        failures.append(label)


def section(content: str, heading: str) -> str:
    marker = f"    - name: {heading}\n"
    start = content.find(marker)
    if start < 0:
        return ""
    next_step = content.find("\n    - name: ", start + len(marker))
    return content[start:] if next_step < 0 else content[start:next_step]


def main() -> int:
    content = WORKFLOW.read_text(encoding="utf-8")
    failures: list[str] = []

    require(content, "RELEASE_SIGNING_READY:", "Missing signing readiness guard", failures)
    for secret in REQUIRED_SECRETS:
        require(content, f"secrets.{secret}", f"Missing secret reference: {secret}", failures)

    compile_section = section(content, "Compile release sources")
    package_section = section(content, "Package signed release outputs")
    unavailable_section = section(content, "Report unavailable release signing")

    require(compile_section, ":app:compileReleaseKotlin", "Release source compilation is missing", failures)
    if any(token in compile_section for token in (":app:assembleRelease", ":app:bundleRelease", "KEYSTORE_BASE64")):
        failures.append("Release source compilation must not package artifacts or consume signing secrets")

    require(package_section, "if: github.ref == 'refs/heads/main' && env.RELEASE_SIGNING_READY == 'true'", "Signed package guard is missing", failures)
    for secret in REQUIRED_SECRETS:
        require(package_section, f"{secret}: ${{{{ secrets.{secret} }}}}", f"Signed package step is missing {secret}", failures)
    require(package_section, ":app:assembleRelease :app:bundleRelease", "Signed package tasks are missing", failures)
    require(unavailable_section, "env.RELEASE_SIGNING_READY != 'true'", "Missing explicit unavailable-signing report", failures)
    require(content, "rm -f release.keystore", "Temporary keystore cleanup is missing", failures)

    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}")
        return 1

    print("[PASS] Release source compilation is independent of signing secrets")
    print("[PASS] Signed release packaging is limited to main with all required secrets")
    print("[PASS] Missing signing configuration is reported and temporary material is removed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
