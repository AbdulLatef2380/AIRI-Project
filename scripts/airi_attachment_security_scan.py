#!/usr/bin/env python3
"""Static guardrails for AIRI attachment routing and bounded text reads."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT_VIEW_MODEL = ROOT / "app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt"
VISION_IMAGE = ROOT / "app/src/main/java/com/airi/assistant/ai/VisionImage.kt"


def check(name: str, passed: bool, detail: str) -> dict[str, object]:
    return {"name": name, "status": "SOURCE_VERIFIED" if passed else "FAIL", "detail": detail}


def main() -> int:
    chat = CHAT_VIEW_MODEL.read_text(encoding="utf-8")
    vision = VISION_IMAGE.read_text(encoding="utf-8")
    checks = [
        check(
            "no_vision_marker_fallback",
            "VISION_FALLBACK_TEXT_MARKER" not in chat and "respond based on filename only" not in chat,
            "Images without vision must be rejected rather than represented as analyzed filename metadata.",
        ),
        check(
            "bounded_text_attachment_read",
            "reader.readText()" not in chat and "while (bounded.length < remaining)" in chat,
            "Text attachment reads must stop at the context cap before the full file enters memory.",
        ),
        check(
            "image_decode_bounds_guard",
            "ImageAttachmentPolicy.validate(srcW, srcH)" in vision,
            "Image decode must validate source dimensions before allocating a bitmap.",
        ),
    ]
    status = "SOURCE_VERIFIED" if all(item["status"] == "SOURCE_VERIFIED" for item in checks) else "FAIL"
    print(json.dumps({"status": status, "checks": checks}, ensure_ascii=False, indent=2))
    return 0 if status == "SOURCE_VERIFIED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
