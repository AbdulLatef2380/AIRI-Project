#!/usr/bin/env python3
"""Produce a conservative coverage report from available JaCoCo XML.

No XML means BLOCKED, never zero coverage and never PASS. The report also
records changed source files, but intentionally does not claim changed-code
coverage unless a real report can map those files to covered counters.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def git_changed(base: str) -> list[str]:
    try:
        output = subprocess.check_output(["git", "diff", "--name-only", f"{base}...HEAD"], cwd=ROOT, text=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    return [line.strip() for line in output.splitlines() if line.strip()]


def counters(root: ET.Element) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    for counter in root.findall("counter"):
        kind = counter.attrib.get("type", "").lower()
        missed = int(counter.attrib.get("missed", "0"))
        covered = int(counter.attrib.get("covered", "0"))
        result[kind] = {"missed": missed, "covered": covered, "total": missed + covered}
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="app/build")
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--output", default="phase7-coverage-report.json")
    args = parser.parse_args()
    report_files = sorted((ROOT / args.root).glob("**/jacoco*.xml")) + sorted((ROOT / args.root).glob("**/jacoco.xml"))
    report_files = list(dict.fromkeys(report_files))
    payload = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "policy": {
            "missing_report": "BLOCKED",
            "no_global_threshold": True,
            "changed_code_requires_mapping": True,
        },
        "changed_files": git_changed(args.base),
        "components": {
            row["component"]: {
                "status": "NOT_VERIFIED",
                "tier": row["tier"],
                "risk_ids": [row["id"]],
                "reason": "No trusted component-level coverage mapping is available yet.",
            }
            for row in json.loads((ROOT / "config/phase7_test_matrix.json").read_text(encoding="utf-8")).get("risks", [])
        },
        "reports": [],
    }
    if not report_files:
        payload["status"] = "BLOCKED"
        payload["reason"] = "No JaCoCo XML report was produced by the selected test tier."
    else:
        payload["status"] = "PASS"
        for path in report_files:
            try:
                root = ET.parse(path).getroot()
                payload["reports"].append({"path": str(path.relative_to(ROOT)), "counters": counters(root)})
            except (ET.ParseError, OSError) as exc:
                payload["status"] = "NOT_VERIFIED"
                payload["reports"].append({"path": str(path.relative_to(ROOT)), "error": str(exc)})
        payload["changed_code_coverage"] = "NOT_VERIFIED"
        payload["changed_code_reason"] = "The current report does not include a trusted changed-file mapping."
    out = ROOT / args.output
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": payload["status"], "report_count": len(report_files), "changed_files": len(payload["changed_files"])}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
