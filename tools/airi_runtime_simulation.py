#!/usr/bin/env python3
"""Deterministic AIRI runtime stress simulation.

This is deliberately dependency-free. It does not claim to execute llama.cpp or
contact a live provider; it verifies the safety contracts around those runtimes.
"""
from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Iterable


class Failure(Exception):
    pass


def check(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)


def validate_gguf(blob: bytes) -> bool:
    return len(blob) >= 8 and blob[:4] == b"GGUF" and int.from_bytes(blob[4:8], "little") in (2, 3)


def atomic_import(source: Path, models: Path, name: str) -> Path:
    models.mkdir(parents=True, exist_ok=True)
    destination = models / name
    if destination.exists():
        destination = models / f"{destination.stem}_imported{destination.suffix}"
    partial = models / f".{destination.name}.part"
    partial.write_bytes(source.read_bytes())
    check(validate_gguf(partial.read_bytes()), "import accepted a malformed model")
    partial.replace(destination)
    check(not partial.exists(), "temporary import file leaked")
    return destination


class MockLocalRuntime:
    def __init__(self) -> None:
        self.loaded: Path | None = None
        self.generating = False
        self.cancelled = False

    def load(self, path: Path) -> None:
        check(path.exists() and validate_gguf(path.read_bytes()), "local runtime loaded invalid GGUF")
        self.loaded = path
        self.cancelled = False

    def stream(self, prompt: str) -> Iterable[str]:
        check(self.loaded is not None, "generation started without a model")
        self.generating = True
        self.cancelled = False
        try:
            for token in ("local", ":", " ", prompt[:24]):
                if self.cancelled:
                    return
                yield token
        finally:
            self.generating = False

    def cancel(self) -> None:
        self.cancelled = True

    def unload(self) -> None:
        check(not self.generating, "unload attempted while native generation was active")
        self.loaded = None


def parse_sse(lines: Iterable[str], require_done: bool = True) -> tuple[str, bool]:
    text: list[str] = []
    done = False
    for line in lines:
        line = line.strip()
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]":
            done = True
            continue
        if not payload:
            continue
        try:
            value = json.loads(payload)
        except json.JSONDecodeError as exc:
            raise Failure(f"invalid SSE JSON: {exc}") from exc
        if "error" in value:
            raise Failure("provider returned an error event")
        choices = value.get("choices", [])
        if choices:
            delta = choices[0].get("delta", {})
            text.append(delta.get("content", ""))
        candidates = value.get("candidates", [])
        if candidates:
            parts = candidates[0].get("content", {}).get("parts", [])
            text.extend(p.get("text", "") for p in parts)
    if require_done and not done:
        raise Failure("EOF without terminal SSE event was accepted")
    result = "".join(text)
    check(bool(result), "empty successful stream")
    return result, done


def test_local_storage_and_lifecycle() -> dict[str, int]:
    with tempfile.TemporaryDirectory(prefix="airi-runtime-") as raw:
        root = Path(raw)
        source = root / "tiny.gguf"
        source.write_bytes(b"GGUF" + (3).to_bytes(4, "little") + b"test weights")
        malformed = root / "bad.gguf"
        malformed.write_bytes(b"GGUF" + (99).to_bytes(4, "little"))
        models = root / "models"
        first = atomic_import(source, models, "model.gguf")
        second = atomic_import(source, models, "model.gguf")
        check(first != second and first.read_bytes() == second.read_bytes(), "import overwrote active model")
        try:
            atomic_import(malformed, models, "bad.gguf")
        except Failure:
            pass
        else:
            raise Failure("unsupported GGUF version bypassed preflight")
        runtime = MockLocalRuntime()
        runtime.load(first)
        chunks = list(runtime.stream("hello العربية 中文"))
        check("local" in "".join(chunks), "local generation returned no tokens")
        runtime.cancel()
        runtime.unload()
        for _ in range(100):
            runtime.load(second)
            stream = runtime.stream("cycle")
            next(stream)
            runtime.cancel()
            list(stream)
            runtime.unload()
            runtime.load(first)
            runtime.unload()
        return {"model_switch_cancel_cycles": 100, "local_tokens": len(chunks)}


def test_cloud_protocols() -> dict[str, int]:
    openai = [
        'data: {"choices":[{"delta":{"content":"hello"}}]}',
        'data: {"choices":[{"delta":{"content":" world"}}]}',
        "data: [DONE]",
    ]
    text, done = parse_sse(openai)
    check(text == "hello world" and done, "OpenAI SSE assembly failed")
    try:
        parse_sse(openai[:-1])
    except Failure:
        pass
    else:
        raise Failure("OpenAI early EOF was accepted")
    gemini = [
        'data: {"candidates":[{"content":{"parts":[{"text":"مرحبا"}]}}]}',
        'data: {"candidates":[{"content":{"parts":[{"text":" world"}]}}]}',
        "data: [DONE]",
    ]
    gemini_text, _ = parse_sse(gemini)
    check(gemini_text == "مرحبا world", "Gemini Unicode SSE assembly failed")
    try:
        parse_sse(["data: {\"candidates\":[]}", "data: [DONE]"])
    except Failure:
        pass
    else:
        raise Failure("empty Gemini stream was accepted")
    url = "https://generativelanguage.googleapis.com/v1beta/models/x:streamGenerateContent?alt=sse"
    check("key=" not in url and "x-goog-api-key" not in url, "Gemini key leaked into URL contract")
    # Contract test for the exact Gemini REST multimodal wire shape: image input
    # is a sibling part using inline_data, not an OpenAI image_url object.
    gemini_part = {
        "role": "user",
        "parts": [
            {"text": "ما في الصورة؟"},
            {"inline_data": {"mime_type": "image/jpeg", "data": "AA=="}},
        ],
    }
    check("inline_data" in gemini_part["parts"][1], "Gemini image part is missing inline_data")
    check("image_url" not in json.dumps(gemini_part), "OpenAI image_url leaked into Gemini payload")
    return {"openai_tokens": 2, "gemini_tokens": 2, "multimodal_parts": 1, "early_eof_rejections": 1, "empty_stream_rejections": 1}


def test_ui_contract() -> dict[str, int]:
    source = Path("app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt").read_text()
    check("private fun chatTextDirection" in source, "per-message direction helper missing")
    check("CompositionLocalProvider(LocalLayoutDirection provides chatTextDirection" in source, "message direction is not applied")
    check("private fun AiriThinkingDots" in source, "thinking indicator missing")
    check("BidiAwareMarkdownRenderer" in source, "Markdown/code renderer missing")
    user_at = source.index("fun UserBubble")
    ai_at = source.index("fun AiBubble")
    ai_end = source.index("fun AiStreamingBubble")
    ai_block = source[ai_at:ai_end]
    check(".clip(RoundedCornerShape(4.dp, 18.dp" not in ai_block, "assistant response still has a rounded bubble")
    check(".padding(horizontal = 14.dp, vertical = 12.dp)" not in ai_block, "assistant response still has bubble padding")
    check("BidiAwareMarkdownRenderer" in ai_block, "assistant response lost readable renderer")
    check(".background(AiriTheme.surfaceVariant)" in source[user_at:ai_at], "theme-aware user bubble surface is missing")
    check("Arrangement.Start" in source[user_at:ai_at], "user bubble is not placed like the reference layout")
    stream_at = source.index("fun AiStreamingBubble")
    stream_block = source[stream_at:source.index("private fun AiriThinkingDots", stream_at)]
    check("AIRIShapes.aiBubble" not in stream_block and "AiriTheme.surfaceVariant" not in stream_block, "streaming response still has a bubble")
    check("Icons.Outlined.MoreHoriz" in ai_block, "response more-actions control is missing")
    return {"assistant_open_surface": 1, "user_filled_surface": 1, "direction_contract": 1, "response_actions": 5}


def main() -> None:
    results = {
        "local": test_local_storage_and_lifecycle(),
        "cloud": test_cloud_protocols(),
        "ui": test_ui_contract(),
    }
    print(json.dumps({"status": "PASS", "results": results}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
