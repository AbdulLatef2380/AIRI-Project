#!/usr/bin/env python3
"""Collect a source-level observability baseline without external telemetry.

This inventory intentionally measures only repository evidence. It does not
claim to be a device, emulator, or native-model performance benchmark.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def count(pattern: str, text: str) -> int:
    return len(re.findall(pattern, text, flags=re.MULTILINE))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    root = args.root.resolve()
    source_root = root / "app/src/main/java"
    test_root = root / "app/src/test"
    kt_files = list(source_root.rglob("*.kt"))
    test_files = list(test_root.rglob("*.kt")) if test_root.exists() else []
    text = "\n".join(path.read_text(errors="ignore") for path in kt_files)
    buffer = (source_root / "com/airi/assistant/core/ExecutionTraceBuffer.kt").read_text()
    capacity = re.search(r"DEFAULT_MAX_ENTRIES\s*=\s*(\d+)", buffer)

    result = {
        "measurement_scope": "source_inventory_only",
        "kotlin_source_files": len(kt_files),
        "unit_test_source_files": len(test_files),
        "runtime_trace_event_references": count(r"UniversalTraceEventType\.", text),
        "execution_status_bus_references": count(r"ExecutionStatusBus\.(?:on|traceSnapshot)", text),
        "duration_or_latency_references": count(r"(?:durationMs|LatencyMs|latencyMs)", text),
        "trace_buffer_max_entries": int(capacity.group(1)) if capacity else None,
        "privacy_redaction_references": count(r"PrivacyGuard\.(?:redact|safe)", text),
        "correlation_field_references": {
            field: count(rf"\b{field}\b", text)
            for field in ("requestId", "executionId", "stepId", "toolCallId", "traceId", "sessionId")
        },
        "known_runtime_measurements": {
            "first_visible_token_latency_ms": "ChatViewModel",
            "total_generation_latency_ms": "ChatViewModel",
            "p50_p90_latency_ms": "ChatViewModel",
            "tool_duration_ms": "ExecutionTraceEvent/UniversalRuntimeTrace",
            "prompt_stage_timeline": "PreFirstTokenTimeline",
            "bounded_trace_snapshot": "ExecutionTraceBuffer",
        },
        "runtime_measurements_not_available_from_source_scan": [
            "device TTFT distribution",
            "Compose recomposition frequency",
            "memory retrieval/write latency distribution",
            "native memory pressure over time",
            "provider/connector failure rates from production traffic",
        ],
    }
    print(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
