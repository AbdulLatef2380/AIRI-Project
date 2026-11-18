#!/usr/bin/env python3
"""Static acceptance gates for the capability report remediation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")

checks = []
def check(name: str, condition: bool):
    checks.append((name, condition))

admission = text("app/src/main/java/com/airi/assistant/agent/loop/AgentLoopToolAdmission.kt")
policy = text("app/src/main/java/com/airi/assistant/agent/loop/tool/AgentLoopSideEffectPolicy.kt")
runtime = text("app/src/main/java/com/airi/assistant/agent/loop/AgentLoopTaskRuntime.kt")
loop = text("app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt")
schema = text("app/src/main/java/com/airi/assistant/agent/loop/tool/ToolSchema.kt")
dispatcher = text("app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt")
verifier = text("app/src/main/java/com/airi/assistant/agent/loop/tool/ToolResultVerifier.kt")
viewmodel = text("app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt")

artifact_names = [
    "generate_document", "generate_spreadsheet", "generate_pdf", "generate_image",
    "generate_image_from_prompt", "generate_audio", "generate_video", "compress_files",
]
check("artifact tools are durable", all(f'"{name}"' in runtime for name in artifact_names) and ") + ARTIFACT_TOOLS" in runtime)
check("artifact policy has typed route", "ALLOW_TYPED_ARTIFACT_WRITE" in policy and "ARTIFACT_TOOLS" in policy)
check("artifact admission is not silently blocked", "INTENTIONALLY_BLOCKED_WITHOUT_TYPED_ROUTE: Set<String> = emptySet()" in admission)
check("release terminal admission uses sandbox policy", "SandboxIsolationPolicy" in loop and "mayExecuteShell(isDebugBuild = com.airi.assistant.BuildConfig.DEBUG)" in loop)
check("file writes pass durable context and approval", "durableExecutionContext = durableExecutionContext" in loop and "userApproved = true" in loop)
check("success consumes evidence", "ToolResultVerifier.isVerified(toolResult)" in loop and "ToolResultVerifier.isVerified(result)" in loop)
check("image prompt schema exists", "GENERATE_IMAGE_FROM_PROMPT" in schema and "generate_image_from_prompt" in dispatcher)
check("image prompt is verified on disk", "generateImageFromPrompt" in dispatcher and "verifyArtifact(it.artifactId)" in dispatcher)
check("dispatcher rejects unowned artifact writes", "durableExecutionContext == null || !userApproved" in dispatcher and "ARTIFACT_TOOLS" in dispatcher)
check("read screen readiness is surfaced", 'result["read_screen"]' in viewmodel and "AccessibilityReadiness.inspect" in viewmodel)
check("skill load reports readiness", "SkillLoadReadiness" in dispatcher and "enable_skill_runtime" in dispatcher)

failed = [name for name, passed in checks if not passed]
for name, passed in checks:
    print(f"[{'PASS' if passed else 'FAIL'}] {name}")
print(f"summary: {len(checks) - len(failed)}/{len(checks)} checks passed")
raise SystemExit(1 if failed else 0)
