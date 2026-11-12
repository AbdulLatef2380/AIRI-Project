#!/usr/bin/env python3
"""Validate explicit Phase 7 environment gates and emit auditable status."""
from __future__ import annotations
import argparse, json
from datetime import datetime, timezone
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"PASS", "SKIP", "BLOCKED", "NOT_VERIFIED"}
REQUIRED = {"process-death-restart", "migration", "corruption-recovery", "firebase-emulator-rules", "sandbox-provider", "ui-scroll-rtl-accessibility-font-states", "jni-native-resource-lifecycle"}

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="config/phase7_environment_gates.json")
    ap.add_argument("--output", default="phase7-environment-gates-report.json")
    args = ap.parse_args()
    data = json.loads((ROOT / args.config).read_text(encoding="utf-8"))
    gates = data.get("gates", [])
    ids = {g.get("id") for g in gates}
    errors = []
    if REQUIRED - ids:
        errors.append(f"missing gates: {sorted(REQUIRED - ids)}")
    rows = []
    for gate in gates:
        status = gate.get("status")
        if status not in ALLOWED:
            errors.append(f"{gate.get('id')}: invalid status {status!r}")
        if not gate.get("requires") or not gate.get("reason") or not gate.get("evidence"):
            errors.append(f"{gate.get('id')}: blocked/not-verified gate needs requires, reason and evidence")
        rows.append(gate)
    report = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": "PASS" if not errors else "FAIL",
        "summary": {s: sum(g.get("status") == s for g in rows) for s in sorted(ALLOWED)},
        "errors": errors,
        "gates": rows,
    }
    (ROOT / args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], **report["summary"]}, ensure_ascii=False, sort_keys=True))
    return 1 if errors else 0
if __name__ == "__main__":
    raise SystemExit(main())
