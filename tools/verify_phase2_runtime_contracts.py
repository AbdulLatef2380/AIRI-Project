#!/usr/bin/env python3
"""Static release gate for phase-2 runtime/performance contracts.

This gate is intentionally SDK-free: Android/Gradle compilation remains the
responsibility of CI, while these checks prevent regressions in the contracts
that are easy to break during native or service refactors.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
cpp = (ROOT / "app/src/main/cpp/LlamaBridge.cpp").read_text()
model_download = (ROOT / "app/src/main/java/com/airi/assistant/tools/ModelDownloadService.kt").read_text()
hotword = (ROOT / "app/src/main/java/com/airi/assistant/voice/HotwordService.kt").read_text()
live_voice = (ROOT / "app/src/main/java/com/airi/assistant/voice/LiveVoiceService.kt").read_text()
r8 = (ROOT / "app/proguard-rules.pro").read_text()

checks = {
    "kv_telemetry_is_lock_free": (
        "Java_com_airi_assistant_ai_LlamaNative_getKvPosition(JNIEnv*, jobject) {\n"
        "    return (jint)g_kv_position_snapshot.load" in cpp
        and "Java_com_airi_assistant_ai_LlamaNative_getKvPosition(JNIEnv*, jobject) {\n    LLAMA_LOCK" not in cpp
    ),
    "context_telemetry_is_lock_free": (
        "Java_com_airi_assistant_ai_LlamaNative_getNCtx(JNIEnv*, jobject) {\n"
        "    return (jint)g_n_ctx_snapshot.load" in cpp
    ),
    "projector_telemetry_is_lock_free": (
        "g_mmproj_loaded_snapshot.load(std::memory_order_acquire)" in cpp
    ),
    "kv_snapshot_has_publish_points": cpp.count("airi_publish_kv_position();") >= 10,
    "download_is_not_sticky": "return START_STICKY" not in model_download,
    "download_has_timeout_path": "override fun onTimeout(startId: Int)" in model_download,
    "download_prevents_parallel_workers": "DOWNLOAD_IGNORED reason=already_running" in model_download,
    "hotword_checks_permission_before_foreground": (
        "RECORD_AUDIO not granted — refusing foreground start" in hotword
        and "FOREGROUND_SERVICE_TYPE_MICROPHONE" in hotword
    ),
    "live_voice_checks_permission_before_foreground": (
        "Live voice requires RECORD_AUDIO; refusing service start" in live_voice
        and "FOREGROUND_SERVICE_TYPE_MICROPHONE" in live_voice
    ),
    "gson_reflection_models_kept": "ProjectFileManager$ProjectFile" in r8 and "DurableTask" in r8,
    "jna_reflection_bridge_kept": "-keep class com.sun.jna.**" in r8,
}

for name, passed in checks.items():
    print(f"[{'PASS' if passed else 'FAIL'}] {name}")

if not all(checks.values()):
    raise SystemExit(1)
print(f"summary: {len(checks)}/{len(checks)} phase-2 contracts passed")
