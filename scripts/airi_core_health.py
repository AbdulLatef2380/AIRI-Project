#!/usr/bin/env python3
"""Source-level product health inventory for AIRI Core.

The script deliberately reports review candidates separately from build-blocking
findings. It never upgrades a warning to PASS merely because it is known.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main"
KOTLIN = SOURCE / "java"
RES = SOURCE / "res"
MARKER_PATTERN = re.compile(
    r"\b(TODO|FIXME|HACK|XXX|NotImplemented|UnsupportedOperationException|"
    r"placeholder|dummy|stub|coming soon|mock)\b",
    re.IGNORECASE,
)
CONFLICT_PATTERN = re.compile(r"^(?:<<<<<<< [^\n]+|=======$|>>>>>>> [^\n]+)$", re.MULTILINE)
EMPTY_CALLBACK_PATTERN = re.compile(r"\b(?:on[A-Z]\w*|callback)\s*=\s*\{\s*\}")


@dataclass(frozen=True)
class Finding:
    category: str
    classification: str
    path: str
    line: int
    text: str


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def kotlin_files() -> list[Path]:
    return sorted(KOTLIN.rglob("*.kt"))


def classify_marker(relative: str, token: str, content: str) -> str:
    stripped = content.lstrip()
    if "/src/test/" in relative:
        return "TEST_ONLY"
    if token.lower() == "todo" and not stripped.startswith(("//", "/*", "*")):
        return "REAL_PRODUCTION"
    if token.lower() == "placeholder" and "placeholder" in content.lower():
        return "REAL_PRODUCTION"
    if stripped.startswith(("//", "/*", "*")):
        return "LEGITIMATE_COMMENT"
    return "UNFINISHED"


def marker_findings(files: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    for path in files:
        relative = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in MARKER_PATTERN.finditer(text):
            line = line_number(text, match.start())
            content = text.splitlines()[line - 1].strip()
            findings.append(
                Finding(
                    "unfinished-marker",
                    classify_marker(relative, match.group(1), content),
                    relative,
                    line,
                    content[:180],
                )
            )
    return findings


def empty_callback_findings(files: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    for path in files:
        relative = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in EMPTY_CALLBACK_PATTERN.finditer(text):
            line = line_number(text, match.start())
            content = text.splitlines()[line - 1].strip()
            findings.append(Finding("empty-callback", "REVIEW", relative, line, content[:180]))
    return findings


def resource_parity() -> dict[str, list[str]]:
    default = {
        node.attrib["name"]
        for node in ET.parse(RES / "values/strings.xml").getroot().findall("string")
    }
    missing: dict[str, list[str]] = {}
    for locale in ("values-ar", "values-es", "values-zh"):
        keys = {
            node.attrib["name"]
            for node in ET.parse(RES / locale / "strings.xml").getroot().findall("string")
        }
        missing[locale] = sorted(default - keys)
    return missing


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", dest="json_path", type=Path)
    parser.add_argument("--max-lines", type=int, default=1200)
    args = parser.parse_args()

    files = kotlin_files()
    source_suffixes = {".kt", ".java", ".xml", ".py", ".sh", ".yml", ".yaml", ".kts", ".gradle", ".properties"}
    conflict_files = [
        str(path.relative_to(ROOT))
        for path in ROOT.rglob("*")
        if path.is_file()
        and path.suffix in source_suffixes
        and not any(part in {".git", ".gradle", "build"} for part in path.parts)
        and CONFLICT_PATTERN.search(path.read_text(encoding="utf-8", errors="ignore"))
    ]
    largest = sorted(
        (
            {
                "path": str(path.relative_to(ROOT)),
                "lines": len(path.read_text(encoding="utf-8", errors="replace").splitlines()),
            }
            for path in files
        ),
        key=lambda item: item["lines"],
        reverse=True,
    )
    large_files = [item for item in largest if item["lines"] > args.max_lines]
    markers = marker_findings(files)
    empty_callbacks = empty_callback_findings(files)
    missing = resource_parity()
    marker_by_classification = Counter(item.classification for item in markers)

    report = {
        "blocking": {"merge_conflicts": conflict_files, "missing_resource_keys": missing},
        "review_candidates": {
            "large_kotlin_files": large_files,
            "unfinished_markers": [asdict(item) for item in markers],
            "unfinished_only": [asdict(item) for item in markers if item.classification == "UNFINISHED"],
            "empty_callbacks": [asdict(item) for item in empty_callbacks],
        },
        "summary": {
            "kotlin_files": len(files),
            "large_file_count": len(large_files),
            "unfinished_marker_count": len(markers),
            "empty_callback_count": len(empty_callbacks),
            "marker_classifications": dict(marker_by_classification),
        },
    }
    blocking = bool(conflict_files or any(missing.values()))
    print(f"[{'FAIL' if conflict_files else 'PASS'}] Merge conflicts: {len(conflict_files)}")
    for locale, keys in missing.items():
        print(f"[{'FAIL' if keys else 'PASS'}] Resource parity {locale}: missing={len(keys)}")
    print(f"[REVIEW] Kotlin files over {args.max_lines} lines: {len(large_files)}")
    for item in large_files[:12]:
        print(f"  {item['lines']:>5} {item['path']}")
    print(f"[REVIEW] Marker candidates: {len(markers)}; unfinished={len([item for item in markers if item.classification == 'UNFINISHED'])}")
    print(f"[REVIEW] Empty callbacks: {len(empty_callbacks)}")
    if args.json_path:
        args.json_path.parent.mkdir(parents=True, exist_ok=True)
        args.json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
