# AIRI Skill and MCP Trust Contract

> **Status:** `PARTIAL`. AIRI now rejects connector-bound skills when their declared service is not healthy, and Connector Runtime requires a connector to be both connected and healthy before tool execution. Manifest package integrity and endpoint validation remain install-time checks. Cryptographic publisher trust, durable invocation audit, tool-level continuation approvals, and an MCP transport negotiation surface remain incomplete.

## Runtime skill boundary

A skill may declare `requiredConnectors`. `SkillInvocationAccessPolicy` applies the following checks in order before an invocation receives its context: the skill is enabled, all declared Android permissions are present, every required connector is healthy, and requested memory is available. A successful decision only passes through memory and model capabilities that the skill has declared; it does not grant new capability.

`SkillToolBridge` receives a connector-health lookup from the live `ConnectorRegistry` in `ChatViewModel`. A connector is acceptable only when its runtime state is both `connected` and `healthy`. This keeps the policy independent of Android and enables deterministic unit tests.

| Invocation condition | Result |
|---|---|
| Skill disabled | Rejected before execution. |
| Declared Android permission unavailable | Rejected before execution. |
| Declared connector missing, disconnected, or unhealthy | Rejected before execution. |
| Memory requested but unavailable | Rejected before execution. |
| Declared capability available | Context is capability-reduced to the skill declaration, then the skill runs within the existing timeout. |

## MCP connector boundary

`ConnectorRuntimeManager` now performs a health-aware connection check. A connector that is merely connected but reports `healthy = false` receives one lifecycle `connect()` check before execution; a connector that remains unhealthy returns `unhealthy` and its tool is not called. Retry counts are bounded to three.

Connector broadcasts now await the result from every targeted connector in a structured coroutine scope. They no longer return after a fixed delay that could omit slow yet valid connector results.

## Verification evidence

| Test | Proven behavior |
|---|---|
| `SkillInvocationAccessPolicyTest` | Disabled, permission-missing, memory-unavailable, and connector-unhealthy skills are denied; unrestricted skills are allowed. |
| `ConnectorRuntimeManagerTest` | An unhealthy connector is rechecked before execution, and broadcast waits for all targeted connectors. |

## Explicit remaining work

The manifest verifier currently checks checksum when declared, version compatibility, HTTPS endpoint rules, basic permission limits, and dependency declarations. It does not yet verify publisher signatures. There is no durable per-invocation audit ledger, no signed registry with revocation, and no task-bound approval continuation for dangerous skill tools. Those controls must be added before a third-party marketplace is represented as trusted by default.
