#!/usr/bin/env python3
"""Enforce AIRI cross-platform architecture boundaries.

The script is intentionally useful before and after the first KMP module exists.
Before Gate 2 it verifies Gate 1 documentation. After Gate 2 it rejects known
Android, JVM, Room, WorkManager, and JNI leakage into commonMain.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

REQUIRED_DOCUMENTS = (
    "docs/multiplatform/README.md",
    "docs/multiplatform/PLATFORM_DEPENDENCY_GRAPH.md",
    "docs/multiplatform/CROSS_PLATFORM_ARCHITECTURE.md",
    "docs/multiplatform/MIGRATION_PLAN.md",
    "docs/multiplatform/PLATFORM_MATRIX.md",
    "docs/multiplatform/RUNTIME_STRATEGY.md",
    "docs/multiplatform/STORAGE_STRATEGY.md",
    "docs/multiplatform/AUTH_STRATEGY.md",
    "docs/multiplatform/SECURITY_MODEL.md",
    "docs/multiplatform/RISK_REGISTER.md",
)

FORBIDDEN_COMMON_PATTERNS = {
    "Android framework import": re.compile(r"^\s*import\s+android\.", re.MULTILINE),
    "AndroidX import": re.compile(r"^\s*import\s+androidx\.", re.MULTILINE),
    "Room dependency": re.compile(r"\b(?:RoomDatabase|@Database|@Dao|@Entity|Room\.)\b"),
    "WorkManager dependency": re.compile(r"\b(?:WorkManager|CoroutineWorker|WorkerParameters|WorkRequest)\b"),
    "Android Context": re.compile(r"\bContext\b"),
    "Android Uri": re.compile(r"\bUri\b"),
    "JNI declaration": re.compile(r"\bexternal\s+fun\b"),
    "Native library loading": re.compile(r"\bSystem\.loadLibrary\b"),
    "JVM API": re.compile(r"\b(?:java\.|javax\.|kotlin\.io\.path\.)"),
}

EXPECT_DECLARATION = re.compile(
    r"\bexpect\s+(?:class|object|interface|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
ACTUAL_DECLARATION = re.compile(
    r"\bactual\s+(?:class|object|interface|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"
)


@dataclass(frozen=True)
class Finding:
    level: str
    rule: str
    path: str
    detail: str


def kotlin_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.kt")) if root.exists() else []


def common_source_roots(repo: Path) -> list[Path]:
    return sorted(path for path in repo.glob("core/**/src/commonMain") if path.is_dir())


def project_source_roots(repo: Path) -> list[Path]:
    return sorted(path for path in repo.glob("core/**/src") if path.is_dir())


def scan_common_sources(repo: Path, findings: list[Finding]) -> tuple[set[str], set[str]]:
    expected: set[str] = set()
    actual: set[str] = set()
    for source_root in common_source_roots(repo):
        for path in kotlin_files(source_root):
            content = path.read_text(encoding="utf-8", errors="replace")
            relative = path.relative_to(repo).as_posix()
            expected.update(EXPECT_DECLARATION.findall(content))
            for name, pattern in FORBIDDEN_COMMON_PATTERNS.items():
                if pattern.search(content):
                    findings.append(Finding("ERROR", name, relative, "Forbidden in commonMain."))
    for source_root in project_source_roots(repo):
        if source_root.name == "commonMain":
            continue
        for path in kotlin_files(source_root):
            content = path.read_text(encoding="utf-8", errors="replace")
            actual.update(ACTUAL_DECLARATION.findall(content))
    return expected, actual


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--report", type=Path, default=None)
    args = parser.parse_args()

    repo = args.repo.resolve()
    if not (repo / ".git").exists():
        print(f"Repository not found: {repo}", file=sys.stderr)
        return 2

    findings: list[Finding] = []
    for document in REQUIRED_DOCUMENTS:
        if not (repo / document).is_file():
            findings.append(Finding("ERROR", "Gate 1 documentation", document, "Required document is missing."))

    expected, actual = scan_common_sources(repo, findings)
    for declaration in sorted(expected - actual):
        findings.append(
            Finding(
                "ERROR",
                "Missing actual implementation",
                "core/**/src",
                f"No actual declaration detected for expect '{declaration}'.",
            )
        )

    common_roots = common_source_roots(repo)
    if not common_roots:
        findings.append(
            Finding(
                "INFO",
                "Core extraction pending",
                "core",
                "No commonMain source set exists yet; this is expected before Gate 2.",
            )
        )

    report_path = args.report or repo / "reports/multiplatform/cross_platform_health.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "common_source_roots": [path.relative_to(repo).as_posix() for path in common_roots],
                "expected_declarations": sorted(expected),
                "actual_declarations": sorted(actual),
                "findings": [asdict(finding) for finding in findings],
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    errors = [finding for finding in findings if finding.level == "ERROR"]
    for finding in findings:
        print(f"{finding.level}: {finding.rule}: {finding.path}: {finding.detail}")
    print(f"Report: {report_path.relative_to(repo)}")
    print(f"Cross-platform health: {'FAIL' if errors else 'PASS'} ({len(errors)} error(s))")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
