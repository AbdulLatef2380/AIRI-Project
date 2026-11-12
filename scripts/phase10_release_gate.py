#!/usr/bin/env python3
"""Run local, non-destructive Phase 10 release-candidate checks."""
from pathlib import Path
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
def run(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()

def exists_nonempty(rel):
    p = ROOT / rel
    return p.is_file() and p.stat().st_size > 0

tracked = run("git", "ls-files").splitlines()
status = run("git", "status", "--porcelain=v1")
checks = []
checks.append(("working tree clean", not status))
checks.append(("candidate is on main", run("git", "branch", "--show-current") == "main"))
checks.append(("CycloneDX SBOM parses", isinstance(json.loads((ROOT / "docs/supply-chain/SBOM.cyclonedx.json").read_text()), dict)))
checks.append(("SPDX SBOM parses", isinstance(json.loads((ROOT / "docs/supply-chain/SBOM.spdx.json").read_text()), dict)))
for rel in (
    "docs/supply-chain/THIRD_PARTY_NOTICES.md",
    "docs/supply-chain/RUNTIME_ASSET_MANIFEST.md",
    "docs/product/ARTIFACT_PROVENANCE_CONTRACT.md",
    "docs/product/AIRI_PHASE10_RELEASE_GATE_REPORT_2026-11-13.md",
):
    checks.append((f"required evidence exists: {rel}", exists_nonempty(rel)))
for pattern in (".jks", ".keystore", "secrets.properties", "service-account"):
    checks.append((f"no tracked signing candidate: {pattern}", not any(pattern in p for p in tracked)))
report_text = (ROOT / "docs/product/AIRI_PHASE10_RELEASE_GATE_REPORT_2026-11-13.md").read_text()
checks.append(("release status is not falsely commercial", "**Commercial status:** `NOT COMMERCIAL_RELEASE_READY`" in report_text))

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'} {name}")
failed = [name for name, ok in checks if not ok]
if failed:
    raise SystemExit("Phase 10 local gate failed: " + ", ".join(failed))
print(f"Phase 10 local gate passed: {len(checks)}/{len(checks)}")
