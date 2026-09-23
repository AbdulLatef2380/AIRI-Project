#!/usr/bin/env python3
"""Live, low-cost smoke test for AIRI's Gemini and OpenAI HTTP contracts.

The script sends one fixed, non-sensitive prompt per selected provider. Keys are
read only from environment variables and are never printed. It deliberately
uses the same REST shapes as AIRI's Android adapters, including SSE parsing.

Examples:
  GEMINI_API_KEY=... OPENAI_API_KEY=... python3 tools/stability/cloud_api_smoke.py
  OPENAI_API_KEY=... python3 tools/stability/cloud_api_smoke.py --provider openai

Exit codes: 0 = every requested provider passed, 2 = missing key, 1 = API/test
failure. Use --allow-missing when a CI job should test configured providers only.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Iterable

PROMPT = "Reply with exactly: AIRI smoke test passed."
DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
DEFAULT_OPENAI_MODEL = "gpt-4o-mini"


@dataclass
class Result:
    provider: str
    status: str
    model: str
    http_code: int | None = None
    latency_ms: int | None = None
    chunks: int = 0
    chars: int = 0
    detail: str = ""


def _json_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def _safe_error(value: str) -> str:
    value = re.sub(r"(?i)(api[-_ ]?key|authorization|bearer)\s*[:=]\s*[^,;\s]+", r"\1=[REDACTED]", value)
    value = re.sub(r"(?i)(incorrect api key provided:\s*)\S+", r"\1[REDACTED]", value)
    value = re.sub(r"\bsk-[A-Za-z0-9_-]{8,}\b", "[REDACTED_OPENAI_KEY]", value)
    value = re.sub(r"\bAIza[A-Za-z0-9_-]{20,}\b", "[REDACTED_GOOGLE_KEY]", value)
    return " ".join(value.replace("\n", " ").split())[:280]


def _request(url: str, headers: dict[str, str], body: object, timeout: float) -> tuple[int, Iterable[str]]:
    request = urllib.request.Request(url, data=_json_bytes(body), headers=headers, method="POST")
    response = urllib.request.urlopen(request, timeout=timeout)
    return response.status, (line.decode("utf-8", "replace") for line in response)


def _post_with_error(url: str, headers: dict[str, str], body: object, timeout: float) -> tuple[int, list[str]]:
    try:
        code, lines = _request(url, headers, body, timeout)
        return code, list(lines)
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {_safe_error(payload)}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"network error: {_safe_error(str(exc.reason))}") from exc


def _openai(timeout: float, model: str) -> Result:
    key = os.getenv("OPENAI_API_KEY", "").strip()
    if not key:
        return Result("openai", "MISSING_KEY", model, detail="OPENAI_API_KEY is not set")
    url = os.getenv("OPENAI_API_URL", "https://api.openai.com/v1/chat/completions").rstrip("/")
    body = {"model": model, "stream": True, "temperature": 0, "max_tokens": 32,
            "messages": [{"role": "user", "content": PROMPT}]}
    started = time.monotonic()
    try:
        code, lines = _post_with_error(url, {"Authorization": f"Bearer {key}", "Content-Type": "application/json", "Accept": "text/event-stream"}, body, timeout)
        text: list[str] = []
        chunks = 0
        done = False
        for line in lines:
            raw = line.strip()
            if not raw.startswith("data:"):
                continue
            payload = raw[5:].strip()
            if payload == "[DONE]":
                done = True
                continue
            if not payload:
                continue
            value = json.loads(payload)
            if value.get("error"):
                raise RuntimeError("provider returned an error event")
            for choice in value.get("choices", []):
                delta = choice.get("delta", {})
                token = delta.get("content") or ""
                if token:
                    text.append(token)
                    chunks += 1
        if not done:
            raise RuntimeError("SSE ended before [DONE]")
        result = "".join(text)
        if not result.strip():
            raise RuntimeError("provider returned an empty stream")
        return Result("openai", "PASS", model, code, round((time.monotonic() - started) * 1000), chunks, len(result))
    except (RuntimeError, json.JSONDecodeError) as exc:
        return Result("openai", "FAIL", model, detail=str(exc))


def _gemini(timeout: float, model: str) -> Result:
    key = (os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY") or "").strip()
    if not key:
        return Result("gemini", "MISSING_KEY", model, detail="GEMINI_API_KEY or GOOGLE_API_KEY is not set")
    base = os.getenv("GEMINI_API_BASE", "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
    url = f"{base}/models/{model}:streamGenerateContent?alt=sse"
    body = {"contents": [{"role": "user", "parts": [{"text": PROMPT}]}],
            "generationConfig": {"temperature": 0, "maxOutputTokens": 32}}
    started = time.monotonic()
    try:
        code, lines = _post_with_error(url, {"x-goog-api-key": key, "Content-Type": "application/json", "Accept": "text/event-stream"}, body, timeout)
        text: list[str] = []
        chunks = 0
        terminal = False
        for line in lines:
            raw = line.strip()
            if not raw.startswith("data:"):
                continue
            payload = raw[5:].strip()
            if not payload:
                continue
            value = json.loads(payload)
            if value.get("error"):
                raise RuntimeError("provider returned an error event")
            if '"finishReason":"' in payload:
                terminal = True
            for candidate in value.get("candidates", []):
                for part in candidate.get("content", {}).get("parts", []):
                    token = part.get("text") or ""
                    if token:
                        text.append(token)
                        chunks += 1
        if not terminal:
            raise RuntimeError("SSE ended before Gemini finishReason")
        result = "".join(text)
        if not result.strip():
            raise RuntimeError("provider returned an empty stream")
        return Result("gemini", "PASS", model, code, round((time.monotonic() - started) * 1000), chunks, len(result))
    except (RuntimeError, json.JSONDecodeError) as exc:
        return Result("gemini", "FAIL", model, detail=str(exc))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", choices=("all", "gemini", "openai"), default="all")
    parser.add_argument("--timeout", type=float, default=45.0)
    parser.add_argument("--gemini-model", default=os.getenv("GEMINI_SMOKE_MODEL", DEFAULT_GEMINI_MODEL))
    parser.add_argument("--openai-model", default=os.getenv("OPENAI_SMOKE_MODEL", DEFAULT_OPENAI_MODEL))
    parser.add_argument("--allow-missing", action="store_true")
    args = parser.parse_args()

    results: list[Result] = []
    if args.provider in ("all", "gemini"):
        results.append(_gemini(args.timeout, args.gemini_model))
    if args.provider in ("all", "openai"):
        results.append(_openai(args.timeout, args.openai_model))

    for result in results:
        payload = {"provider": result.provider, "status": result.status, "model": result.model,
                   "http_code": result.http_code, "latency_ms": result.latency_ms,
                   "chunks": result.chunks, "chars": result.chars}
        if result.detail:
            payload["detail"] = result.detail
        print(json.dumps(payload, ensure_ascii=False))

    missing = any(item.status == "MISSING_KEY" for item in results)
    failed = any(item.status == "FAIL" for item in results)
    if failed or (missing and not args.allow_missing):
        return 1 if failed else 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
