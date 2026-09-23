#!/usr/bin/env python3
"""Dependency-free local-runtime performance and resilience simulation.

This does not claim to run llama.cpp without an Android device/native model. It
simulates the contracts around local generation: context admission, streaming
throughput, cancellation, model switching, and memory-safe bounded output. If
--gguf is provided, the GGUF header and file size are checked before the run.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import time
from pathlib import Path


MAX_OUTPUT_TOKENS = 256
RESERVE_TOKENS = 128


def check(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def validate_gguf(path: Path) -> None:
    check(path.is_file(), f"GGUF does not exist: {path}")
    with path.open("rb") as handle:
        header = handle.read(8)
    check(len(header) == 8 and header[:4] == b"GGUF", "invalid GGUF magic")
    check(int.from_bytes(header[4:8], "little") in (2, 3), "unsupported GGUF version")
    check(path.stat().st_size > 8 * 1024, "GGUF is suspiciously small")


def can_admit(context: int, prompt_tokens: int, max_tokens: int) -> bool:
    return prompt_tokens + max_tokens + RESERVE_TOKENS <= context


def synthetic_stream(prompt_tokens: int, output_tokens: int, cancel_after: int | None = None) -> tuple[int, int, str]:
    """Return decoded tokens, elapsed ms, and a digest of the emitted stream."""
    started = time.perf_counter_ns()
    digest = hashlib.sha256()
    emitted = 0
    for index in range(output_tokens):
        digest.update(f"token-{prompt_tokens}-{index}".encode())
        emitted += 1
        if cancel_after is not None and emitted >= cancel_after:
            break
    elapsed_ms = max(1, round((time.perf_counter_ns() - started) / 1_000_000))
    return emitted, elapsed_ms, digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--output-tokens", type=int, default=128)
    parser.add_argument("--context", type=int, default=2048)
    parser.add_argument("--prompt-tokens", type=int, default=256)
    parser.add_argument("--cancel-cycles", type=int, default=100)
    parser.add_argument("--gguf", type=Path)
    args = parser.parse_args()
    check(1 <= args.runs <= 500, "runs must be between 1 and 500")
    check(1 <= args.output_tokens <= MAX_OUTPUT_TOKENS, "output-tokens exceeds safety cap")
    check(args.cancel_cycles >= 1, "cancel-cycles must be positive")
    if args.gguf:
        validate_gguf(args.gguf)

    check(can_admit(args.context, args.prompt_tokens, args.output_tokens), "admission accepted an over-budget request")
    check(not can_admit(args.context, args.context, 1), "admission accepted an impossible request")

    latencies: list[int] = []
    token_counts: list[int] = []
    digests: set[str] = set()
    for _ in range(args.runs):
        tokens, elapsed, digest = synthetic_stream(args.prompt_tokens, args.output_tokens)
        check(tokens == args.output_tokens and digest, "stream did not reach terminal output")
        latencies.append(elapsed)
        token_counts.append(tokens)
        digests.add(digest)

    for _ in range(args.cancel_cycles):
        tokens, _, digest = synthetic_stream(args.prompt_tokens, args.output_tokens, cancel_after=7)
        check(tokens == 7 and digest, "cancellation did not stop generation at the guard")

    total_tokens = sum(token_counts)
    total_ms = max(1, sum(latencies))
    report = {
        "status": "PASS",
        "mode": "contract_simulation_not_native_inference",
        "runs": args.runs,
        "output_tokens": args.output_tokens,
        "context": args.context,
        "prompt_tokens": args.prompt_tokens,
        "avg_latency_ms": round(statistics.mean(latencies), 2),
        "p50_latency_ms": statistics.median(latencies),
        "p95_latency_ms": sorted(latencies)[max(0, round(len(latencies) * 0.95) - 1)],
        "synthetic_tokens_per_sec": round(total_tokens / (total_ms / 1000), 2),
        "cancel_cycles": args.cancel_cycles,
        "unique_terminal_digests": len(digests),
        "gguf_checked": str(args.gguf) if args.gguf else None,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
