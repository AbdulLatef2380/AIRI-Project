---
name: Kotlin Compilation Audit — AIRI Android
description: All known compilation errors fixed across 482 .kt files; summary of what was broken and where.
---

# Kotlin Compilation Fixes

## Fixes applied (cumulative — both sessions)

| File | Fix |
|------|-----|
| `agent/planning/PlanningTypes.kt` | Emptied — was a duplicate stub of types fully defined in `TypedPlanGraph.kt` |
| `agent/workspace/SandboxWorkspace.kt` | Removed duplicate `WorkspaceRegistry` object |
| `agent/workspace/WorkspaceRegistry.kt` | `ws.listSnapshots()` → `ws.snapshot()` (no listSnapshots); `?.timestampMs` → `.artifacts.maxOfOrNull{it.createdAtMs}` (WorkspaceSnapshot has no timestampMs) |
| `agent/scheduler/ScheduledJobOrchestrator.kt` | Removed duplicate `ScheduledAgentWorker` class |
| `accessibility/execution/AccessibilityExecutionEngine.kt` | Added `import kotlinx.coroutines.withContext` |
| `agent/loop/AgentLoop.kt` | Corrected all `ExecutionStatusBus` calls to match actual 7-method API |
| `agent/loop/tool/ToolDispatcher.kt` | `SearchResult`/`AlarmTool`/`NotesTool` API fixes; `CommandResult.message ?: ""` for String? |
| `ui/viewmodel/ChatViewModel.kt` | `_isGenerating` StateFlow, `ModelController` params, local vars, `ConversationSummarizer.summarize()` arg order; added `onDiagnosticsScreenVisible()`/`syncDownloadedModelAvailability()` delegations; `KEY_MODEL_ID/PATH` → `ModelController.KEY_MODEL_ID/PATH`; `AnalyticsService.inferenceStarted()` → `AnalyticsService.modelLoaded()` |
| `ui/screens/ChatScreen.kt` | Removed duplicate `@OptIn(ExperimentalMaterial3Api::class)`; `prov.displayName` → `prov.displayLabel` (`ProviderConfig` field is `displayLabel`) |
| `ui/screens/DeveloperCenterScreen.kt` | Removed `DevCard("Adaptive Intelligence")` block — `ServiceLocator.adaptiveIntelligence` never existed |
| `ui/screens/ObservabilityScreen.kt` | Removed `snapshot.durableTaskQueue` block — `ObservabilitySnapshot` has no such field (only `durableTasksActive/Completed/Failed` counts) |
| `ui/screens/SkillManagerScreen.kt` | Removed invalid `DropdownMenuItem.Companion.Stub()` extension function |

## Critical architecture notes

### PlanningTypes.kt must stay empty
Emptied because any type re-declared here (GoalNode, GraphSnapshot, NodeStatus, RecoveryBranch) with a REDUCED interface cascades into 20+ unresolved-reference errors across the entire codebase — the compiler resolves to the stub definition instead of the full one in TypedPlanGraph.kt.

### ModelController companion constants
`KEY_MODEL_ID`, `KEY_MODEL_PATH`, `KEY_MODEL_REGISTRY`, `KEY_SCANNED_IDS` live in `ModelController.Companion`. Call from ChatViewModel as `ModelController.KEY_MODEL_ID` etc. Do NOT redeclare in ChatViewModel.

### ModelController internal methods — ChatViewModel must delegate
Functions `syncDownloadedModelAvailability()`, `refreshDiagnosticsSnapshot()`, `persistRegistry()`, `refreshModelList()` are `internal fun` on `ModelController`. ChatViewModel accesses them via `private fun … = modelController.…()` one-liner delegations around line 503-510.

### EmbeddedProviderConfig.ProviderConfig field names
- `displayLabel` (not `displayName`) — the cloud provider's human-readable name shown in the model picker

### AgentWorkspace API
- Only `fun snapshot(): WorkspaceSnapshot` — no `listSnapshots()` method exists
- `WorkspaceSnapshot` has `workspaceId`, `artifacts: List<WorkspaceArtifact>`, `edgeCount` — no `timestampMs`
- `WorkspaceArtifact` has `createdAtMs: Long` — use `artifacts.maxOfOrNull{it.createdAtMs}` for recency

### ObservabilitySnapshot durable task fields
Only `durableTasksActive`, `durableTasksCompleted`, `durableTasksFailed` (all `Int`). No `durableTaskQueue` list.

## ExecutionStatusBus actual API (7 methods)
```
onGraphStarted(goalDescription: String, totalNodes: Int)
onWaveStarted(nodeIds: List<String>, nodeActions: List<String>)
onNodeCompleted(nodeId: String, nodesCompleted: Int)
onNodeRecovering(nodeId: String, reason: String, retryCount: Int)
onReflecting()
onGraphCompleted(success: Boolean)
reset()
```
**Why:** Multiple callers used old names or wrong param types.

## ConversationSummarizer.summarize() correct call
```kotlin
ConversationSummarizer.summarize(
    ctx             = appContext,
    sessionId       = sessionId,
    llamaManager    = llamaManager,
    olderTurns      = olderToFold,   // List<com.airi.assistant.memory.entity.ChatMessage>
    previousSummary = ""
)
```
**Why:** Positional call had args in completely wrong order and was missing the 5th param.

## Key type locations
- `GoalNode`, `NodeStatus`, `RecoveryBranch`, `RecoveryDecision`, `GraphSnapshot` → `TypedPlanGraph.kt`
- `WorkspaceRegistry` → `agent/workspace/WorkspaceRegistry.kt` (standalone, NOT inside SandboxWorkspace)
- `BuiltinTools.ALL` → `agent/loop/tool/ToolSchema.kt`
- `AgentLoop.StepEvent.ToolExecuted/FinalAnswer` → inner sealed class of `AgentLoop`
- `EmbeddedProviderConfig.ProviderConfig` → `execution/cloud/EmbeddedProviderConfig.kt`
