#!/usr/bin/env python3
"""Checks the long-text attachment flow stays attached to the sent message."""

from __future__ import annotations

import sys
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIEW_MODEL = ROOT / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant" / "ui" / "viewmodel" / "ChatViewModel.kt"


def require(content: str, expected: str, label: str, failures: list[str]) -> None:
    if expected not in content:
        failures.append(label)


def main() -> int:
    content = VIEW_MODEL.read_text(encoding="utf-8")
    # Formatting is not a runtime contract. Normalize whitespace so harmless
    # Kotlin reformatting cannot make a valid attachment path fail CI.
    normalized = re.sub(r"\s+", " ", content)
    failures: list[str] = []

    require(normalized, "private fun sendMessageInternal( input: String, allowLongTextConversion: Boolean,", "Missing explicit long-text conversion boundary", failures)
    require(normalized, "if (allowLongTextConversion && LongTextAttachmentPolicy.shouldAutoConvert(trimmedInput))", "Long-text conversion is not explicitly gated", failures)
    require(normalized, "attachments = listOf( ChatAttachment(", "Long-text conversion does not use the attachment send path", failures)
    require(normalized, "sizeBytes = LongTextAttachmentPolicy.utf8SizeBytes(trimmedInput)", "Long-text attachment lacks source size metadata", failures)
    require(normalized, "fullText, allowLongTextConversion = false,", "Text attachments can be recursively converted", failures)

    conversion_start = content.find("if (allowLongTextConversion && LongTextAttachmentPolicy.shouldAutoConvert(trimmedInput))")
    conversion_end = content.find("// ── Intent classification", conversion_start)
    conversion_block = content[conversion_start:conversion_end]
    if "stageAttachmentUri(fileUri)" in conversion_block:
        failures.append("Long-text conversion still relies on asynchronous UI staging")
    if "return sendMessage(summary)" in conversion_block:
        failures.append("Long-text conversion still recursively sends before attachment persistence")

    if failures:
        for failure in failures:
            print(f"[FAIL] {failure}")
        return 1

    print("[PASS] Long text is sent through the attachment persistence path")
    print("[PASS] Text attachment context is protected from recursive conversion")
    return 0


if __name__ == "__main__":
    sys.exit(main())
