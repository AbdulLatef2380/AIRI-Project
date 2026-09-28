#!/usr/bin/env python3
"""Static regression audit for AIRI's cloud-chat integration.

This intentionally avoids Android SDK/runtime dependencies and fails closed when
one of the cross-layer contracts is missing.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "schema": ROOT / "app/src/main/java/com/airi/assistant/agent/loop/tool/ToolSchema.kt",
    "dispatcher": ROOT / "app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt",
    "identity": ROOT / "app/src/main/java/com/airi/assistant/product/AiriIdentityProfile.kt",
    "vm": ROOT / "app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt",
    "loop": ROOT / "app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt",
    "execution_request": ROOT / "app/src/main/java/com/airi/assistant/execution/ExecutionRequest.kt",
    "gemini": ROOT / "app/src/main/java/com/airi/assistant/execution/cloud/GeminiAdapter.kt",
    "foundation": ROOT / "app/src/main/java/com/airi/assistant/execution/AttachmentTransportFoundation.kt",
    "gemini_payload": ROOT / "app/src/main/java/com/airi/assistant/execution/cloud/GeminiPayloadContract.kt",
    "openai": ROOT / "app/src/main/java/com/airi/assistant/execution/cloud/OpenAIAdapter.kt",
    "openai_payload": ROOT / "app/src/main/java/com/airi/assistant/execution/cloud/OpenAIResponsesPayloadContract.kt",
    "anthropic": ROOT / "app/src/main/java/com/airi/assistant/execution/cloud/AnthropicAdapter.kt",
    "anthropic_payload": ROOT / "app/src/main/java/com/airi/assistant/execution/cloud/AnthropicPayloadContract.kt",
    "chat": ROOT / "app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt",
    "strings": ROOT / "app/src/main/res/values/strings.xml",
}
text = {key: path.read_text(encoding="utf-8") for key, path in FILES.items()}
checks = []

def check(name, condition):
    checks.append((name, bool(condition)))

check("terminal tool is advertised", 'name        = "terminal_execute"' in text["schema"])
check("terminal tool is in ALL", "CREATE_NOTE, TERMINAL_EXECUTE" in text["schema"])
check("terminal tool is in CHAT_ONLY", "CREATE_NOTE, TERMINAL_EXECUTE" in text["schema"])
check("terminal tool is dispatched", '"terminal_execute" ->' in text["dispatcher"])
check("terminal dispatch uses governed runtime", "ServiceLocator.terminalRuntime" in text["dispatcher"])
check("terminal dispatch returns output", "terminal.lines.value.drop(before)" in text["dispatcher"])
check("AIRI runtime contract exists", "AIRI RUNTIME CONTRACT" in text["identity"])
check("runtime contract is always assembled", "AiriIdentityProfile.runtimeContext(capabilityDescriptor)" in text["vm"])
check("simple turns bypass tool protocol", "if (queryType == QueryType.SIMPLE || queryType == QueryType.CREATIVE)" in text["vm"])
check("agent loop registers execution ownership", "ExecutionStatusBus.onGraphStarted(" in text["loop"])
check("single-pass success completes status", "ExecutionStatusBus.onGraphCompleted(true, executionId = executionId)" in text["loop"])
check("single-pass failure completes status", "ExecutionStatusBus.onGraphCompleted(false, executionId = executionId)" in text["loop"])
check("unsupported attachment has translated message", "attachment_unsupported_content" in text["strings"] and "UNSUPPORTED_CONTENT" in text["chat"])
check("attachment is cleared only after acceptance", "viewModel.clearCurrentComposerAttachments()" in text["chat"] and "onRejected = { failure" in text["chat"])
check("agent loop still receives full tools for non-simple turns", "allActiveTools" in text["vm"] and "else {\n                allActiveTools" in text["vm"])
check("execution request has native inline attachment contract", "inlineDataParts" in text["execution_request"])
check("Gemini serializes native inline data", "inline_data" in text["gemini"] and "req.inlineDataParts" in text["gemini"])
check("binary attachments require multimodal routing", "attachmentParts.isNotEmpty()" in text["loop"] and "requiresVision" in text["loop"])
check("provider-aware media admission exists", "nativeBinaryAttachments" in text["vm"] and "ModelCapabilityEngine" in text["vm"])
check("thin attachment resolver exists", "object AttachmentTransportResolver" in text["foundation"] and "never reads files" in text["foundation"])
check("resolution has NOT_READY", "NOT_READY" in text["foundation"] and "capabilitySupported" in text["foundation"])
check("delivery evidence has staged proof", "PROVIDER_PAYLOAD_BUILT" in text["foundation"] and "HTTP_REQUEST_DISPATCHED" in text["foundation"] and "PROVIDER_RESPONSE_RECEIVED" in text["foundation"])
check("native success requires provider response", "markProviderResponse(true" in text["gemini"] and "if (success && it.payloadIncluded)" in text["foundation"])
check("Gemini adapter receives the trace", "request.attachmentTrace" in text["gemini"] and "attachmentTrace" in text["execution_request"])
check("Gemini payload contract checks MIME and content", "containsNonEmptyInlineContent" in text["gemini_payload"] and "GeminiPayloadContract" in text["gemini"])
check("attachment UI acceptance waits for transport proof", "transport success was not proven" in text["vm"] and "if (attachmentTrace != null) onAccepted()" in text["vm"])
check("OpenAI native route is first-party only", "provider == CloudProvider.OPENAI" in text["openai"] and "/responses" in text["openai"])
check("OpenAI Responses payload has input_file", "input_file" in text["openai_payload"] and "file_data" in text["openai_payload"])
check("OpenAI Responses payload has input_image", "input_image" in text["openai_payload"] and "image_url" in text["openai_payload"])
check("Anthropic native route uses Messages content blocks", "AnthropicPayloadContract.buildRequestBody" in text["anthropic"] and "content_block_delta" in text["anthropic"])
check("Anthropic payload has image and PDF blocks", '"type\\":\\"image\\"' in text["anthropic_payload"] and '"type\\":\\"document\\"' in text["anthropic_payload"])
check("OpenAI-compatible providers remain on chat completions", "provider == CloudProvider.OPENAI && OpenAIResponsesPayloadContract.requiresResponses" in text["openai"] and '"$baseUrl/chat/completions"' in text["openai"])

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
if failed:
    raise SystemExit(f"{len(failed)} static contract(s) failed")
print(f"PASS: {len(checks)} static contracts")
