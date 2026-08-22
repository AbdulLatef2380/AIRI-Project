#!/usr/bin/env python3
"""Checks that AIRI's system maintenance schedules remain idempotent."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEDULER = ROOT / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant" / "agent" / "scheduler" / "ScheduledJobOrchestrator.kt"
SERVICE_LOCATOR = ROOT / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant" / "core" / "ServiceLocator.kt"
SYSTEM_JOB_IDS = (
    "system_sandbox_reaper",
    "system_audit_log_pruner",
    "system_context_cache_pruner",
)


def require(content: str, expected: str, label: str, failures: list[str]) -> None:
    if expected not in content:
        failures.append(label)


def main() -> int:
    scheduler = SCHEDULER.read_text(encoding="utf-8")
    service_locator = SERVICE_LOCATOR.read_text(encoding="utf-8")
    failures: list[str] = []

    require(scheduler, "stableJobId:     String? = null", "Periodic scheduler lacks a stable job identifier", failures)
    require(scheduler, "listJobs().firstOrNull { it.id == id }", "Periodic scheduler does not reuse a persisted stable job", failures)
    require(scheduler, "AIRI PERIODIC_JOB_REUSED", "Periodic job reuse is not auditable", failures)
    require(scheduler, "id          = stableJobId ?: UUID.randomUUID().toString()", "Stable job identifier is not used for the WorkManager name", failures)

    for job_id in SYSTEM_JOB_IDS:
        require(service_locator, f'stableJobId     = "{job_id}"', f"Missing stable identifier for {job_id}", failures)

    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}")
        return 1

    print("[PASS] Periodic scheduler reuses persisted stable jobs")
    print("[PASS] Every system maintenance job has a stable identifier")
    return 0


if __name__ == "__main__":
    sys.exit(main())
