#!/usr/bin/env python3
"""Deterministic Phase 3 contract gate; does not claim device performance."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def check(name: str, ok: bool, detail: str) -> dict[str, object]:
    return {"name": name, "status": "PASS" if ok else "FAIL", "detail": detail}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("phase3-runtime-contracts.json"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    app = (root / "app/build.gradle.kts").read_text()
    settings = (root / "settings.gradle.kts").read_text()
    native = (root / "app/src/main/cpp/LlamaBridge.cpp").read_text()
    application = (root / "app/src/main/java/com/airi/assistant/app/AIRIApplication.kt").read_text()
    recovery = (root / "app/src/main/java/com/airi/assistant/runtime/recovery/RuntimeRecoveryEngine.kt").read_text()
    policy = (root / "app/src/main/java/com/airi/assistant/runtime/recovery/MemoryPressurePolicy.kt").read_text()
    baseline = root / "baselineprofile/build.gradle.kts"
    profile_test = root / "baselineprofile/src/main/java/com/airi/assistant/baselineprofile/AiriBaselineProfile.kt"

    results = [
        check("memory_policy_exists", "object MemoryPressurePolicy" in policy, "pure decision table is present"),
        check("memory_policy_app_wiring", "MemoryPressurePolicy.classify(level)" in application, "Application delegates trim decisions"),
        check("ui_hidden_is_non_destructive", "shouldReleaseNativeMemory = false" in policy, "UI_HIDDEN does not unload live native state"),
        check("native_signal_handler_is_signal_safe", "__android_log_print" not in native[native.index("static void airi_signal_handler"):native.index("static void install_signal_handlers")], "fatal handler only writes a fixed marker and re-raises"),
        check("native_signal_marker", "AIRI_NATIVE_FATAL_SIGNAL" in native, "native crash marker is emitted"),
        check("recovery_handler_is_idempotent", "crashHandlerInstalled.compareAndSet(false, true)" in recovery, "default handler is installed once"),
        check("baseline_module_included", 'include(":baselineprofile")' in settings and baseline.exists(), "baseline profile module is wired"),
        check("baseline_profile_test_present", profile_test.exists() and "BaselineProfileRule" in profile_test.read_text(), "startup profile scenario exists"),
        check("app_baseline_wiring", "baselineProfile(project(\":baselineprofile\"))" in app, "app packages generated profile"),
        check("profile_installer", "androidx.profileinstaller" in app, "release runtime can install the profile"),
    ]
    payload = {"phase": "3", "device_performance_measured": False, "checks": results}
    args.output.write_text(json.dumps(payload, indent=2) + "\n")
    failed = [item for item in results if item["status"] != "PASS"]
    print(f"PHASE3_CONTRACTS={'PASS' if not failed else 'FAIL'} checks={len(results)} failed={len(failed)}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
