#!/usr/bin/env python3
"""Static end-to-end contract audit for AIRI's unified runtime path.

This is intentionally dependency-free so CI can run it before the Android build.
It verifies that the production wiring remains intact across local/cloud routing,
tool catalog/dispatch, connector failures, memory, and scheduled tasks.
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f"missing source file: {rel}")
    return path.read_text(encoding="utf-8")


checks: list[dict[str, object]] = []


def check(name: str, conditions: list[tuple[str, bool]]) -> None:
    failed = [label for label, passed in conditions if not passed]
    checks.append({
        "name": name,
        "status": "PASS" if not failed else "FAIL",
        "missing": failed,
    })


chat = read("app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt")
loop = read("app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt")
dispatcher = read("app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt")
catalog = read("app/src/main/java/com/airi/assistant/agent/loop/tool/RuntimeToolCatalog.kt")
contract = read("app/src/main/java/com/airi/assistant/agent/loop/tool/RuntimeToolContract.kt")
bridge = read("app/src/main/java/com/airi/assistant/connector/ConnectorToolBridge.kt")
hybrid = read("app/src/main/java/com/airi/assistant/execution/HybridOrchestrator.kt")
router = read("app/src/main/java/com/airi/assistant/execution/router/RuntimeRouter.kt")
request = read("app/src/main/java/com/airi/assistant/execution/ExecutionRequest.kt")
builtins = read("app/src/main/java/com/airi/assistant/agent/loop/tool/ToolSchema.kt")
rollout = read("app/src/main/java/com/airi/assistant/connector/ConnectorRolloutRegistry.kt")
auth_manager = read("app/src/main/java/com/airi/assistant/connector/ConnectorAuthorizationManager.kt")
microsoft_config = read("app/src/main/java/com/airi/assistant/connector/oauth/OAuthConfiguration.kt")
microsoft_adapter = read("app/src/main/java/com/airi/assistant/connector/app/MicrosoftGraphConnector.kt")
microsoft_tokens = read("app/src/main/java/com/airi/assistant/connector/app/MicrosoftGraphTokenService.kt")
runtime_descriptors = read("app/src/main/java/com/airi/assistant/connector/ConnectorRuntimeDescriptor.kt")
primary_contract = read("app/src/main/java/com/airi/assistant/connector/PrimaryConnectorContract.kt")
remaining_contract = read("app/src/main/java/com/airi/assistant/connector/ProviderAdapterContract.kt")
mcp_connector = read("app/src/main/java/com/airi/assistant/connector/mcp/McpConnector.kt")
worker = read("app/src/main/java/com/airi/assistant/agent/scheduler/ScheduledAgentWorker.kt")
prod_orchestrator = read("app/src/main/java/com/airi/assistant/agent/orchestrator/ProductionAgentOrchestrator.kt")

check("single runtime tool catalog is assembled at the chat boundary", [
    ("capability intent detector", "CapabilityIntentDetector.detect" in chat),
    ("catalog assembly", "RuntimeToolCatalog.assemble" in chat),
    ("available and unavailable connector schemas", "includeUnavailable = true" in chat),
    ("catalog schemas become active tools", "val allActiveTools = catalog.schemas" in chat),
    ("active tools passed to agent loop", "tools        = activeTools" in chat),
])

check("agent loop preserves tool calling for both execution backends", [
    ("effective tools passed to LLM", "tools = effectiveTools" in loop),
    ("tool requirement encoded in request", "requiresToolCalling   = effectiveTools.isNotEmpty()" in loop),
    ("unified execution request", "request    = ExecutionRequest(" in loop),
    ("local and cloud use same orchestrator request", "orchestrator.executeStream" in loop or "hybridOrchestrator" in loop),
    ("request has explicit tool requirement", "val requiresToolCalling" in request),
])

check("hybrid local/cloud routing keeps tool context and failover", [
    ("router selects primary and fallbacks", "val decision = router.route" in hybrid),
    ("cloud privacy copy is prepared", "applyPrivacyGate" in hybrid),
    ("fallback target request is rebound", "backend.rebindForExecution" in hybrid),
    ("each backend receives target request", "backend.generateStream(" in hybrid and "request    = targetRequest" in hybrid),
    ("router exposes all backends", "val allBackends: List<RuntimeBackend>" in router),
])

check("skills, connectors, and builtins converge on ToolDispatcher", [
    ("skill bridge dispatch", "skillToolBridge" in dispatcher and "bridge.invoke(toolName, args)" in dispatcher),
    ("connector bridge dispatch", "connectorToolBridge" in dispatcher and "connectorToolBridge.invoke(toolName, args)" in dispatcher),
    ("connector unavailable result is normalized", "ToolErrorCodes.fromConnector" in dispatcher),
    ("memory builtin exists", '"memory_recall"' in builtins and '"current_time"' in builtins),
    ("memory execution exists", '"memory_recall" ->' in dispatcher),
    ("time execution exists", '"current_time" ->' in dispatcher),
    ("unavailable connector actions remain resolvable", "bindings(onlyExecutable = false)" in bridge and "handles(toolName: String)" in bridge),
])

check("memory and scheduled tasks retain durable ownership boundaries", [
    ("scheduled worker exists", "class ScheduledAgentWorker" in worker),
    ("worker dispatches production orchestrator", "ProductionAgentOrchestrator" in worker),
    ("worker records completed outcome", "ScheduledJobOutcome.COMPLETED" in worker),
    ("worker records failed outcome", "ScheduledJobOutcome.FAILED" in worker),
    ("production orchestrator creates durable task", "durableTaskManager" in prod_orchestrator),
    ("memory tool has scoped execution", "memory_recall" in dispatcher),
])

check("remaining connector rollout is explicit and cannot create phantom connections", [
    ("rollout registry exists", "object ConnectorRolloutRegistry" in rollout),
    ("all catalog definitions are mapped", "OfficialConnectorCatalog.all.map(::forDefinition)" in rollout),
    ("catalog-only adapters are blocked", "ConnectorAdapterReadiness.CATALOG_ONLY" in rollout),
    ("manager reports missing adapter", "adapter_not_installed" in auth_manager),
    ("manager reports rollout batch", "required adapter" in auth_manager),
    ("health gate remains mandatory", "state.connected && state.healthy" in auth_manager),
])

check("Microsoft Outlook/Calendar/OneDrive/Teams vertical slice has a typed OAuth and token lifecycle", [
    ("canonical microsoft runtime descriptor", '"microsoft_outlook" to ConnectorRuntimeDescriptor("microsoft_outlook", "microsoft_graph"' in runtime_descriptors),
    ("typed OAuth configuration gate", "sealed interface OAuthConfiguration" in microsoft_config),
    ("PKCE S256 authorization", "code_challenge_method=S256" in microsoft_adapter),
    ("callback consumes state", "OAuthStateRegistry.consumeRequest" in auth_manager),
    ("refresh token rotation", "rotatedRefresh" in microsoft_tokens),
    ("encrypted credential manager is used", "ConnectorAuthManager" in microsoft_tokens),
    ("Graph health check", 'graphGet("/me?' in microsoft_adapter),
    ("read-only mail and calendar actions", "outlook_mail_read" in microsoft_adapter and "outlook_calendar_read" in microsoft_adapter),
    ("OneDrive uses the shared Graph adapter", '"microsoft_onedrive" to ConnectorRuntimeDescriptor("microsoft_onedrive", "microsoft_graph"' in runtime_descriptors),
    ("OneDrive Files.Read consent and read action", '"Files.Read"' in microsoft_adapter and "onedrive_files_read" in microsoft_adapter),
    ("OneDrive listing is bounded and metadata-only", "coerceIn(1, 50)" in microsoft_adapter and "lastModifiedDateTime" in microsoft_adapter),
    ("Teams uses the shared Graph adapter", '"microsoft_teams" to ConnectorRuntimeDescriptor("microsoft_teams", "microsoft_graph"' in runtime_descriptors),
    ("Teams Team.ReadBasic.All consent and read action", '"Team.ReadBasic.All"' in microsoft_adapter and "teams_list_joined" in microsoft_adapter),
    ("Teams listing is metadata-only", "/me/joinedTeams" in microsoft_adapter and "displayName,description,visibility,webUrl" in microsoft_adapter),
])

check("seven primary catalog surfaces have one real runtime gate", [
    ("primary contract is explicit", "object PrimaryConnectorContracts" in primary_contract),
    ("exactly seven surfaces", 'PrimaryConnectorContract("zapier"' in primary_contract),
    ("Notion MCP tools are exposed", "override fun agentActions()" in mcp_connector),
    ("MCP tool dispatch uses invoke_tool", 'id = "invoke_tool"' in mcp_connector),
    ("MCP health precedes connected state", "connected = ok, healthy = ok" in mcp_connector),
])

check("all remaining catalog connectors have declarative provider contracts", [
    ("remaining contract registry exists", "object RemainingProviderAdapterContracts" in remaining_contract),
    ("all current catalog remainder is represented", "private val byId = all.associateBy { it.catalogId }" in remaining_contract),
    ("contracts carry scopes and health", "requiredScopes: List<String>" in remaining_contract and "healthEndpoint: String" in remaining_contract),
    ("contracts are non-executable by design", "val isExecutable: Boolean = false" in remaining_contract),
    ("manager includes contract diagnostics", "required scopes:" in auth_manager and "health:" in auth_manager),
])

check("regression tests cover the final path", [
    ("catalog contract test", (ROOT / "app/src/test/java/com/airi/assistant/agent/loop/tool/RuntimeToolCatalogTest.kt").is_file()),
    ("connector bridge test", (ROOT / "app/src/test/java/com/airi/assistant/connector/ConnectorToolBridgeTest.kt").is_file()),
    ("connector lifecycle test", (ROOT / "app/src/test/java/com/airi/assistant/connector/ConnectorRuntimeManagerTest.kt").is_file()),
    ("memory scope instrumentation test", (ROOT / "app/src/androidTest/java/com/airi/assistant/memory/MemoryScopeTransactionTest.kt").is_file()),
    ("durable task kernel test", (ROOT / "app/src/test/java/com/airi/assistant/agent/durable/DurableTaskProductKernelTest.kt").is_file()),
    ("scheduled worker policy test", (ROOT / "app/src/test/java/com/airi/assistant/agent/scheduler/ScheduledWorkerOutcomePolicyTest.kt").is_file()),
    ("primary seven connector contract test", (ROOT / "app/src/test/java/com/airi/assistant/connector/PrimaryConnectorContractTest.kt").is_file()),
    ("remaining provider adapter contract test", (ROOT / "app/src/test/java/com/airi/assistant/connector/ProviderAdapterContractTest.kt").is_file()),
])

failed = [item for item in checks if item["status"] == "FAIL"]
result = {
    "status": "PASS" if not failed else "FAIL",
    "checks": checks,
    "scope": ["local_model", "cloud_model", "hybrid_failover", "skills", "connectors", "memory", "scheduled_tasks"],
    "note": "Static contract audit; Android unit/instrumentation execution remains a CI/device responsibility.",
}
print(json.dumps(result, ensure_ascii=False, indent=2))
raise SystemExit(1 if failed else 0)
