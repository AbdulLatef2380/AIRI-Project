#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_DOCS = (
    "docs/multiplatform/TOOLCHAIN_COMPATIBILITY_MATRIX.md",
    "docs/multiplatform/TOOLCHAIN_UPGRADE_PLAN.md",
    "docs/multiplatform/TOOLCHAIN_RISK_REGISTER.md",
    "docs/multiplatform/TOOLCHAIN_ROLLBACK_PLAN.md",
)
FORBIDDEN_COMMON = (
    "android.",
    "androidx.",
    "java.",
    "javax.",
    "kotlinx.coroutines.android",
    "androidx.room",
    "workmanager",
    "System.loadLibrary",
    "external fun",
)


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def check(name: str, condition: bool, detail: str) -> dict[str, str]:
    return {"name": name, "status": "PASS" if condition else "FAIL", "detail": detail}


def main() -> int:
    checks: list[dict[str, str]] = []
    settings = text(ROOT / "settings.gradle.kts")
    workflow = text(ROOT / ".github/workflows/android_build.yml")
    catalog = text(ROOT / "gradle/libs.versions.toml")

    checks.append(check("Core module included", 'include(":core-domain")' in settings, "core-domain is registered in Gradle settings."))
    checks.append(check("Shared core CI gate", ":core-domain:desktopTest" in workflow and ":core-domain:compileDebugKotlinAndroid" in workflow, "CI builds and tests core before Android."))
    checks.append(check("No KMP compatibility suppression", "kotlin.mpp.androidGradlePluginCompatibility.nowarn=true" not in (ROOT / "gradle.properties").read_text(encoding="utf-8"), "KMP/AGP compatibility warnings remain visible."))
    checks.append(check("Version catalog has Kotlin", re.search(r"^kotlin\s*=\s*\"[^\"]+\"", catalog, re.MULTILINE) is not None, "Kotlin version is catalog-managed."))

    for relative in REQUIRED_DOCS:
        checks.append(check(f"Required document {relative}", (ROOT / relative).is_file(), "Required toolchain governance document exists."))

    common_files = sorted(ROOT.glob("core-*/src/commonMain/**/*.kt"))
    checks.append(check("Shared core discovered", bool(common_files), f"Found {len(common_files)} common Kotlin source file(s)."))
    findings: list[str] = []
    for path in common_files:
        source = text(path)
        for token in FORBIDDEN_COMMON:
            if token in source:
                findings.append(f"{path.relative_to(ROOT)}: {token}")
    checks.append(check("No common platform leakage", not findings, "; ".join(findings) if findings else "No forbidden platform APIs found in commonMain."))

    report = {
        "schema_version": 1,
        "checks": checks,
        "common_source_count": len(common_files),
        "leakage_findings": findings,
    }
    report_dir = ROOT / "reports/multiplatform"
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "toolchain_health.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    failed = [item for item in checks if item["status"] == "FAIL"]
    for item in checks:
        print(f"[{item['status']}] {item['name']}: {item['detail']}")
    print(f"Report: {(report_dir / 'toolchain_health.json').relative_to(ROOT)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
