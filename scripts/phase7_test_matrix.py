#!/usr/bin/env python3
"""Validate and report the risk-linked Phase 7 test matrix.

The inventory check is deliberately separate from execution status: a test file
being present is not evidence that it ran. Missing runtime evidence is emitted
as NOT_VERIFIED rather than being promoted to PASS.
"""
from __future__ import annotations

import argparse
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"PASS", "SKIP", "BLOCKED", "NOT_VERIFIED"}
REQUIRED_TIERS = {"fast-pr", "main-ci", "extended-device", "release-verification"}


def junit_evidence(root: Path) -> tuple[list[dict], str | None]:
    """Read non-empty JUnit XML without treating an empty report as success."""
    if not root.exists():
        return [], "evidence root does not exist"
    reports = []
    for path in sorted(root.glob("**/*.xml")):
        try:
            document = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            reports.append({"path": str(path), "parse_error": str(exc)})
            continue
        cases = list(document.iter("testcase"))
        if not cases:
            continue
        reports.append({
            "path": str(path),
            "tests": len(cases),
            "failures": sum(1 for case in cases if case.find("failure") is not None),
            "errors": sum(1 for case in cases if case.find("error") is not None),
            "skipped": sum(1 for case in cases if case.find("skipped") is not None),
            "identities": [
                value
                for case in cases
                for value in (case.attrib.get("name"), case.attrib.get("classname"))
                if value
            ],
        })
    return reports, None if reports else "no non-empty JUnit test report was found"


def runtime_status(test_paths: list[str], reports: list[dict], missing_reason: str | None) -> tuple[str, dict]:
    stems = [Path(path).stem for path in test_paths]
    matched = [
        report for report in reports
        if any(stem in " ".join(report.get("identities", [])) for stem in stems)
    ]
    if missing_reason:
        return "NOT_VERIFIED", {"reason": missing_reason, "matched_reports": []}
    if not matched:
        return "NOT_VERIFIED", {"reason": "No JUnit testcase matched the declared target classes", "matched_reports": []}
    failures = sum(report.get("failures", 0) for report in matched)
    errors = sum(report.get("errors", 0) for report in matched)
    tests = sum(report.get("tests", 0) for report in matched)
    detail = {
        "matched_reports": [report["path"] for report in matched],
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": sum(report.get("skipped", 0) for report in matched),
    }
    if failures or errors:
        return "BLOCKED", {"reason": "Matched test reports contain failures or errors", **detail}
    return "PASS", {"reason": "Matched JUnit testcases completed without failures or errors", **detail}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", default="config/phase7_test_matrix.json")
    parser.add_argument("--output", default="phase7-test-matrix-report.json")
    parser.add_argument(
        "--evidence-root",
        default=os.environ.get("PHASE7_EVIDENCE_ROOT", ""),
        help="Directory containing JUnit XML; omitted means runtime remains NOT_VERIFIED",
    )
    args = parser.parse_args()

    matrix_path = ROOT / args.matrix
    data = json.loads(matrix_path.read_text(encoding="utf-8"))
    errors: list[str] = []
    warnings: list[str] = []
    rows = []
    reports, evidence_error = (
        junit_evidence(ROOT / args.evidence_root)
        if args.evidence_root
        else ([], "runtime evidence root was not supplied")
    )
    tiers = set(data.get("tiers", {}))
    missing_tiers = REQUIRED_TIERS - tiers
    errors.extend(f"missing required tier: {tier}" for tier in sorted(missing_tiers))

    for row in data.get("risks", []):
        rid = row.get("id", "<missing-id>")
        status = row.get("status")
        tests = row.get("tests", [])
        if status not in ALLOWED:
            errors.append(f"{rid}: invalid status {status!r}")
        if row.get("tier") not in tiers:
            errors.append(f"{rid}: unknown tier {row.get('tier')!r}")
        if not tests:
            errors.append(f"{rid}: no test or blocked reason is recorded")
        missing = [p for p in tests if not (ROOT / p).exists()]
        if missing:
            errors.extend(f"{rid}: missing test/evidence declaration target {p}" for p in missing)
        if status == "PASS":
            warnings.append(f"{rid}: PASS is only valid when the referenced runtime evidence is attached")
        runtime, runtime_detail = (
            runtime_status(tests, reports, evidence_error)
            if not missing
            else ("BLOCKED", {"reason": "Declared test target is missing", "matched_reports": []})
        )
        rows.append({
            "id": rid,
            "component": row.get("component"),
            "tier": row.get("tier"),
            "declared_status": status,
            "inventory": "PASS" if not missing else "BLOCKED",
            "runtime_evidence": runtime,
            "runtime_detail": runtime_detail,
            "tests": tests,
            "missing_targets": missing,
            "evidence": row.get("evidence"),
        })

    report = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": str(args.matrix),
        "policy": data.get("policy", {}),
        "summary": {
            "risk_count": len(rows),
            "inventory_pass": sum(r["inventory"] == "PASS" for r in rows),
            "inventory_blocked": sum(r["inventory"] == "BLOCKED" for r in rows),
            "runtime_not_verified": sum(r["runtime_evidence"] == "NOT_VERIFIED" for r in rows),
            "runtime_pass": sum(r["runtime_evidence"] == "PASS" for r in rows),
            "runtime_blocked": sum(r["runtime_evidence"] == "BLOCKED" for r in rows),
            "errors": len(errors),
            "warnings": len(warnings),
        },
        "errors": errors,
        "warnings": warnings,
        "runtime_evidence": {
            "root": args.evidence_root or None,
            "report_count": len(reports),
            "error": evidence_error,
        },
        "risks": rows,
    }
    output = ROOT / args.output
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, sort_keys=True))
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        return 1
    print("PASS: test matrix inventory is complete; runtime execution remains separately classified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
