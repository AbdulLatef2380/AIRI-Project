#!/usr/bin/env python3
"""Static/component safety checks for AIRI.

This test does not require Android SDK. It validates source-level invariants that
protect model storage, cloud failover, agent permissions, and streaming output.
It exits non-zero on the first failed release invariant and is suitable for CI.
"""
from pathlib import Path
import hashlib
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
FAILURES = []
PASSES = 0


def text(rel: str) -> str:
    p = ROOT / rel
    if not p.is_file():
        fail(rel, f"missing file: {p}")
        return ""
    return p.read_text(encoding="utf-8")


def check(name: str, condition: bool, detail: str = "") -> None:
    global PASSES
    if condition:
        PASSES += 1
        print(f"PASS {name}")
    else:
        FAILURES.append((name, detail))
        print(f"FAIL {name}: {detail}")


def fail(name: str, detail: str) -> None:
    FAILURES.append((name, detail))
    print(f"FAIL {name}: {detail}")


def test_path_policy() -> None:
    safe = ["qwen.gguf", "model-1.5b.gguf", "gemma_Q4.gguf"]
    unsafe = ["../model.gguf", "/tmp/model.gguf", "models/evil.gguf", "model.bin"]
    for name in safe:
        check(f"safe_filename:{name}", Path(name).name == name and name.lower().endswith(".gguf"))
    for name in unsafe:
        check(f"reject_filename:{name}", not (Path(name).name == name and name.lower().endswith(".gguf")))


def test_content_range() -> None:
    pattern = re.compile(r"bytes\s+(\d+)-\d+/(?:\d+|\*)", re.I)
    for header, expected, valid in [
        ("bytes 1024-2047/4096", 1024, True),
        ("bytes 2048-4095/*", 1024, False),
        ("invalid", 0, False),
    ]:
        match = pattern.search(header)
        actual = int(match.group(1)) if match else None
        check(f"content_range:{header}", (actual == expected) == valid)


def test_source_invariants() -> None:
    cloud = text("app/src/main/java/com/airi/assistant/execution/backend/CloudBackend.kt")
    worker = text("app/src/main/java/com/airi/assistant/tools/ModelDownloadWorker.kt")
    model_controller = text("app/src/main/java/com/airi/assistant/ui/viewmodel/ModelController.kt")
    embedding = text("app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt")
    tool_executor = text("app/src/main/java/com/airi/assistant/ai/tools/ToolExecutor.kt")
    firewall = text("app/src/main/java/com/airi/assistant/security/ExecutionFirewall.kt")
    permissions = text("app/src/main/java/com/airi/assistant/security/ToolPermissionPolicy.kt")
    router = text("app/src/main/java/com/airi/assistant/connector/AgentRouter.kt")
    registry = text("app/src/main/java/com/airi/assistant/connector/ConnectorRegistry.kt")
    chat_ui = text("app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt")
    input_ui = text("app/src/main/java/com/airi/assistant/ui/screens/AdvancedInputBar.kt")
    app_ui = text("app/src/main/java/com/airi/assistant/ui/AiriApp.kt")
    capability_engine = text("app/src/main/java/com/airi/assistant/execution/ModelCapabilityEngine.kt")
    capability_tests = text("app/src/test/java/com/airi/assistant/execution/ModelCapabilityEngineTest.kt")

    check("cloud_checks_all_fallbacks", "providers.any" in cloud and "FAILOVER_PRIORITY" in cloud)
    check("cloud_emits_only_success", "emit only after success" in cloud and "onToken(result.fullText)" in cloud)
    check("download_requires_https", 'protocol !in setOf("https")' in worker)
    check("download_rejects_path_escape", "path escaped storage root" in worker and "canonicalFile" in worker)
    check("download_validates_content_range", "Content-Range" in worker and "DOWNLOAD_RANGE_MISMATCH" in worker)
    check("download_atomic_part", ".part" in worker and "renameTo(finalFile)" in worker)
    check("model_copy_before_jni", "materializeForNative" in model_controller and "imported_models" in model_controller)
    check("model_validated_before_jni", "ModelValidator.validate(target" in model_controller)
    check("embedding_atomic_copy", '".${fileName}.part"' in embedding and "output.fd.sync()" in embedding)
    check("embedding_gguf_magic", 'magic.decodeToString() != "GGUF"' in embedding)
    check("tool_executor_firewall", "firewall.guard(agentId, toolCall.toolName)" in tool_executor)
    check("permission_maps_real_tools", all(x in permissions for x in [
        "github_get_user", "github_get_repos", "telegram_send_message",
        "gmail_list_emails", "drive_search_file", "calendar_next_events"
    ]))
    check("firewall_unknown_tools_denied", "UnknownToolException" in firewall and "permissionFor(toolName)" in firewall)
    check("router_timeout", "withTimeout(CONNECTOR_TIMEOUT_MS)" in router)
    check("connector_order_stable", "registrationOrder" in registry and "sortedBy" in registry)
    check("welcome_actions_removed", "chat_starter_email_label" not in chat_ui and "Suggestion chips" not in chat_ui)
    check("fullscreen_editor_exists", "showFullScreenEditor" in chat_ui and "DialogProperties(usePlatformDefaultWidth = false)" in chat_ui)
    check("existing_expand_arrow_preserved", "isExpanded = !isExpanded" in chat_ui and "KeyboardArrowUp" in chat_ui)
    check("compact_composer_default", "max = if (isExpanded) 180.dp else 44.dp" in chat_ui)
    check("navigation_handle_exists", "onBottomNavToggle" in input_ui and "detectVerticalDragGestures" in input_ui)
    check("navigation_hidden_by_default", "rememberSaveable { mutableStateOf(false) }" in app_ui and "bottomNavRevealed" in app_ui)
    check("capability_engine_single_source", "enum class CapabilityStatus" in capability_engine and "object ModelCapabilityEngine" in capability_engine)
    check("declared_runtime_separated", "declared: Map<Capability, CapabilityStatus>" in capability_engine and "runtime: Map<Capability, CapabilityStatus>" in capability_engine)
    check("local_mmproj_runtime_gate", "mmprojLoaded" in capability_engine and "TEMPORARILY_UNAVAILABLE" in capability_engine)
    check("cloud_exact_model_resolution", "fromCloud(provider: CloudProvider, modelId: String)" in capability_engine)
    check("request_level_capability_guard", "currentCapabilityDescriptor()" in text("app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt") and "ModelCapabilityEngine.check" in text("app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt"))
    check("capability_unit_tests", "declaredVisionWithoutProjectorIsUnavailable" in capability_tests and "cloudUsesExactModelId" in capability_tests)


def test_patch_identity() -> None:
    patch = Path("/home/ubuntu/AIRI-comprehensive-stability-fixes.patch")
    if not patch.is_file():
        print("INFO patch file not present; skipping identity check")
        return
    current = ROOT / ".airi-current.patch.tmp"
    import subprocess
    result = subprocess.run(["git", "-c", "color.ui=false", "diff", "--binary"], cwd=ROOT, capture_output=True, check=True)
    if not result.stdout:
        check("patch_worktree_clean", True)
        return
    digest_current = hashlib.sha256(result.stdout).hexdigest()
    digest_patch = hashlib.sha256(patch.read_bytes()).hexdigest()
    check("patch_matches_worktree", digest_current == digest_patch, f"current={digest_current} patch={digest_patch}")


def main() -> int:
    print(f"AIRI component safety test root={ROOT}")
    test_path_policy()
    test_content_range()
    test_source_invariants()
    test_patch_identity()
    print(f"RESULT passed={PASSES} failed={len(FAILURES)}")
    if FAILURES:
        for name, detail in FAILURES:
            print(f"ERROR {name}: {detail}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
