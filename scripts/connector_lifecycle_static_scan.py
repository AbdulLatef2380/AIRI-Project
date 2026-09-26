#!/usr/bin/env python3
"""Static guardrails for connector lifecycle, concurrency, and retention hazards."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "registry": ROOT / "app/src/main/java/com/airi/assistant/connector/ConnectorRegistry.kt",
    "runtime": ROOT / "app/src/main/java/com/airi/assistant/connector/ConnectorRuntimeManager.kt",
    "health": ROOT / "app/src/main/java/com/airi/assistant/connector/ConnectorHealthMonitor.kt",
    "connectors_vm": ROOT / "app/src/main/java/com/airi/assistant/ui/viewmodel/ConnectorsViewModel.kt",
    "integrations_vm": ROOT / "app/src/main/java/com/airi/assistant/ui/viewmodel/IntegrationsViewModel.kt",
    "zapier_screen": ROOT / "app/src/main/java/com/airi/assistant/ui/screens/ZapierIftttScreen.kt",
}

text = {name: path.read_text(encoding="utf-8") for name, path in FILES.items()}
checks = []

def check(name, ok, detail):
    checks.append((name, bool(ok), detail))

r, x, h = text["registry"], text["runtime"], text["health"]
check("registry lifecycle mutex", "private val lifecycleMutex = Mutex()" in r and "lifecycleMutex.withLock" in r,
      "connect/disconnect are serialized")
check("explicit disconnect gate", "explicitlyDisconnected" in r and "isExplicitlyDisconnected" in r and '"not_connected"' in x,
      "runtime fails closed after explicit disconnect")
check("bounded operation history", "MAX_OPERATION_HISTORY" in x and "takeLast(MAX_OPERATION_HISTORY)" in x,
      "operation state cannot grow without bound")
check("inflight finally cleanup", "finally { trackEnd(key) }" in x,
      "timeout/exception/cancellation all release inflight tracking")
check("timeout terminal state", "ConnectorOperationState.TimedOut" in x and "withTimeout" in x,
      "timeout is typed and never success")
check("cancellation propagation", "catch (e: CancellationException)" in x and "throw e" in x,
      "cancellation is not converted to provider success/failure retry")
check("health scope cancellation", "scope.coroutineContext[Job]?.cancel()" in h and "monitorJob?.cancel()" in h,
      "health monitor owns and cancels its scope")
check("health restart safety", "scope = CoroutineScope(Dispatchers.IO + SupervisorJob())" in h,
      "health monitor can restart after stop")
check("bounded health notice state", "ConcurrentHashMap<String, Long>" in h and "lastOfflineNotice.clear()" in h,
      "dynamic connector IDs do not retain notice entries forever")
check("no GlobalScope", not any("GlobalScope" in value for value in text.values()),
      "no unowned global coroutine scope in lifecycle paths")
check("UI routes lifecycle through registry", "registry.connect(id)" in text["connectors_vm"] and "registry.disconnect(id)" in text["connectors_vm"],
      "ConnectorsViewModel does not bypass lifecycle owner")
check("integration disconnect is suspend-safe", "else -> viewModelScope.launch" in text["integrations_vm"],
      "credential cleanup runs in coroutine after connector disconnect")
direct_bypass = re.findall(r"\bconnector\.(connect|disconnect)\s*\(", text["zapier_screen"] + text["connectors_vm"] + text["integrations_vm"])
check("no UI direct lifecycle bypass", not direct_bypass,
      "UI lifecycle actions route through ConnectorRegistry")

failed = [item for item in checks if not item[1]]
for name, ok, detail in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
print(f"summary: {len(checks)-len(failed)}/{len(checks)} checks passed")
sys.exit(1 if failed else 0)
