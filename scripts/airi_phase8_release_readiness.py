#!/usr/bin/env python3
"""Static Phase 8 release-readiness contract gate.

This gate intentionally checks truthful classification and source contracts only;
it cannot replace device, provider, store, or legal validation.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise SystemExit(f"MISSING {rel}")
    return path.read_text(encoding="utf-8")

checks = [
    (
        "local deletion is explicit and local-only",
        "eraseLocalData" in read("app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt")
        and "does not call the remote" in read("app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt"),
    ),
    (
        "deletion reports partial failures",
        "PartialSuccess" in read("app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt")
        and "runStep" in read("app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt"),
    ),
    (
        "automatic update remains fail-closed",
        "automaticChecksAvailable: Boolean = false" in read("app/src/main/java/com/airi/assistant/domain/release/UpdateAvailabilityPolicy.kt"),
    ),
    (
        "performance diagnostics exist",
        "class ThermalProfiler" in read("app/src/main/java/com/airi/assistant/runtime/thermal/ThermalProfiler.kt")
        and "object RuntimeProfiler" in read("app/src/main/java/com/airi/assistant/runtime/profiler/RuntimeProfiler.kt"),
    ),
    (
        "phase 8 evidence is linked",
        "AIRI_PHASE8_RELEASE_READINESS.md" in read("README.md")
        and "AIRI_PHASE8_RELEASE_READINESS.md" in read("docs/AIRI_KNOWN_LIMITATIONS.md"),
    ),
]
failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'} {name}")
if failed:
    raise SystemExit(f"Phase 8 readiness gate failed: {', '.join(failed)}")
print(f"Phase 8 readiness gate passed: {len(checks)}/{len(checks)}")
