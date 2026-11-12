#!/usr/bin/env python3
"""Validate and report the risk-linked Phase 7 test matrix.

The inventory check is deliberately separate from execution status: a test file
being present is not evidence that it ran. Missing runtime evidence is emitted
as NOT_VERIFIED rather than being promoted to PASS.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"PASS", "SKIP", "BLOCKED", "NOT_VERIFIED"}
REQUIRED_TIERS = {"fast-pr", "main-ci", "extended-device", "release-verification"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", default="config/phase7_test_matrix.json")
    parser.add_argument("--output", default="phase7-test-matrix-report.json")
    args = parser.parse_args()

    matrix_path = ROOT / args.matrix
    data = json.loads(matrix_path.read_text(encoding="utf-8"))
    errors: list[str] = []
    warnings: list[str] = []
    rows = []
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
        rows.append({
            "id": rid,
            "component": row.get("component"),
            "tier": row.get("tier"),
            "declared_status": status,
            "inventory": "PASS" if not missing else "BLOCKED",
            "runtime_evidence": "NOT_VERIFIED" if status == "NOT_VERIFIED" else status,
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
            "errors": len(errors),
            "warnings": len(warnings),
        },
        "errors": errors,
        "warnings": warnings,
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
