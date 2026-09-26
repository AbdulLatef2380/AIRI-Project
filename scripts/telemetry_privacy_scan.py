#!/usr/bin/env python3
"""Static guardrails for privacy-safe failover and session telemetry."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPORTER = (ROOT / "app/src/main/java/com/airi/assistant/telemetry/PrivacyTelemetryReporter.kt").read_text()
EVENTS = (ROOT / "app/src/main/java/com/airi/assistant/telemetry/AgentTelemetryEvent.kt").read_text()
POLICY = (ROOT / "app/src/main/java/com/airi/assistant/telemetry/TelemetryTagPolicy.kt").read_text()
CLOUD = (ROOT / "app/src/main/java/com/airi/assistant/execution/backend/CloudBackend.kt").read_text()
CHAT = (ROOT / "app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt").read_text()

checks = []
def check(name, ok, detail):
    checks.append((name, bool(ok), detail))

check("runtime event exists", "data class RuntimeStateChanged" in EVENTS,
      "typed operational event exists")
check("closed vocabulary", all(x in POLICY for x in ("areas", "states", "reasons", "fun area", "fun state", "fun reason")),
      "area/state/reason are allowlisted")
check("runtime tags use policy", all(x in REPORTER for x in ("TelemetryTagPolicy.area", "TelemetryTagPolicy.state", "TelemetryTagPolicy.reason")),
      "runtime tags are not merely regex-sanitized")
check("session dimensions bounded", "TelemetryTagPolicy.dimension(event.deviceTier" in REPORTER and "TelemetryTagPolicy.dimension(event.executionMode" in REPORTER,
      "session dimensions are allowlisted")
check("crash fields sanitized", "val component = sanitize(event.component)" in REPORTER and "val errorTag = sanitize(event.errorTag)" in REPORTER,
      "crash telemetry does not dispatch raw component/tag")
check("failover started metric", 'area = "cloud_failover"' in CLOUD and 'state = "started"' in CLOUD and 'reasonTag = "transient_failure"' in CLOUD,
      "fallback attempt emits a bounded metric")
check("failover success metric", 'state = "succeeded"' in CLOUD and 'reasonTag = "provider_switch"' in CLOUD,
      "successful fallback emits a bounded metric")
check("failover exhausted metric", 'state = "exhausted"' in CLOUD and 'reasonTag = "all_providers_failed"' in CLOUD,
      "exhausted chain emits a bounded metric")
check("session lifecycle metrics", all(x in CHAT for x in ('area = "session_load"', 'state = "stale"', 'reasonTag = "completion_ignored"', '"valid_empty"')),
      "session started/stale/failed/empty/success paths are instrumented")
check("no sensitive fields in runtime event", not re.search(r"RuntimeStateChanged\([^)]*(prompt|content|message|sessionId|user|email|token|secret|payload)", EVENTS, re.I | re.S),
      "runtime event contract carries no prompt, content, identity, or secret field")
runtime_blocks = re.findall(r"RuntimeStateChanged\((.*?)\)\s*\n?\s*\)", CHAT, re.S)
check("no raw session ID in runtime events", not any(re.search(r"\bsessionId\s*=", block, re.I) for block in runtime_blocks),
      "session telemetry does not include sessionId")
check("consent gate retained", "if (!consent.agentTelemetryEnabled) return@launch" in REPORTER,
      "agent telemetry remains opt-in and dropped before dispatch")

failed = [x for x in checks if not x[1]]
for name, ok, detail in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
print(f"summary: {len(checks)-len(failed)}/{len(checks)} checks passed")
sys.exit(1 if failed else 0)
