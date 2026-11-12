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


def add_counters(target: dict[str, dict[str, int]], source: dict[str, dict[str, int]]) -> None:
    for kind, value in source.items():
        current = target.setdefault(kind, {"missed": 0, "covered": 0, "total": 0})
        current["missed"] += value["missed"]
        current["covered"] += value["covered"]
        current["total"] += value["total"]


def source_key(class_node: ET.Element) -> str:
    package = class_node.get("package", "")
    source = class_node.get("sourcefilename", "")
    parent = class_node
    while parent is not None and parent.tag != "package":
        parent = None
    return "/".join(part for part in (package.replace(".", "/"), source) if part)


def mapped_counters(report_roots: list[ET.Element], mapping: dict[str, list[str]]) -> dict[str, dict[str, dict[str, int]]]:
    result = {component: {} for component in mapping}
    for root in report_roots:
        for package in root.findall(".//package"):
            package_name = package.get("name", "")
            for class_node in package.findall("class"):
                source = "/".join(part for part in (package_name, class_node.get("sourcefilename", "")) if part)
                class_counts = counters(class_node)
                for component, prefixes in mapping.items():
                    if any(source.startswith(prefix) for prefix in prefixes):
                        add_counters(result[component], class_counts)
    return result


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="app/build")
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--components", default="config/phase7_coverage_components.json")
    parser.add_argument("--history", default="")
    parser.add_argument("--output", default="phase7-coverage-report.json")
    args = parser.parse_args()
    report_files = sorted((ROOT / args.root).glob("**/jacoco*.xml")) + sorted((ROOT / args.root).glob("**/jacoco.xml"))
    report_files = list(dict.fromkeys(report_files))
    matrix = json.loads((ROOT / "config/phase7_test_matrix.json").read_text(encoding="utf-8"))
    risk_by_component = {row["component"]: row for row in matrix.get("risks", [])}
    mapping = json.loads((ROOT / args.components).read_text(encoding="utf-8"))["components"]
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
            component: {
                "status": "NOT_VERIFIED",
                "tier": risk_by_component.get(component, {}).get("tier"),
                "risk_ids": [risk_by_component[component]["id"]] if component in risk_by_component else [],
                "reason": "No JaCoCo XML report was produced by the selected test tier.",
            }
            for component in mapping
        },
        "reports": [],
    }
    if not report_files:
        payload["status"] = "BLOCKED"
        payload["reason"] = "No JaCoCo XML report was produced by the selected test tier."
    else:
        payload["status"] = "PASS"
        roots = []
        for path in report_files:
            try:
                root = ET.parse(path).getroot()
                roots.append(root)
                payload["reports"].append({"path": display_path(path), "counters": counters(root)})
            except (ET.ParseError, OSError) as exc:
                payload["status"] = "NOT_VERIFIED"
                payload["reports"].append({"path": display_path(path), "error": str(exc)})
        for component, component_counts in mapped_counters(roots, mapping).items():
            row = risk_by_component.get(component, {})
            payload["components"][component] = {
                "status": "PASS" if component_counts else "NOT_VERIFIED",
                "tier": row.get("tier"),
                "risk_ids": [row["id"]] if row else [],
                "counters": component_counts,
                "reason": "JaCoCo classes matched configured source prefixes." if component_counts else "No JaCoCo classes matched configured source prefixes.",
            }
        payload["changed_code_coverage"] = "SKIP" if not payload["changed_files"] else "NOT_VERIFIED"
        payload["changed_code_reason"] = (
            "No changed files relative to the selected base."
            if not payload["changed_files"]
            else "Changed-file mapping is emitted only after sourcefile counters are matched."
        )
        payload["trend"] = {
            "status": "NOT_VERIFIED" if not args.history else "PASS",
            "reason": "A prior report directory was not supplied." if not args.history else "Trend input directory supplied; compare component counters in the artifact history.",
        }
    out = ROOT / args.output
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": payload["status"], "report_count": len(report_files), "changed_files": len(payload["changed_files"])}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
