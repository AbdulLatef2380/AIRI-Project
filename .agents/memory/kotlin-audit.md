---
name: Kotlin Compilation Audit — AIRI Android
description: All known compilation errors fixed across 482 .kt files; summary of what was broken and where.
---

# Kotlin Compilation Fixes

## Fixes applied

| File | Fix |
|------|-----|
| `agent/planning/PlanningTypes.kt` | Emptied — was a duplicate stub of types fully defined in `TypedPlanGraph.kt` |
| `agent/workspace/SandboxWorkspace.kt` | Removed duplicate `WorkspaceRegistry` object (canonical in `WorkspaceRegistry.kt`) |
| `agent/scheduler/ScheduledJobOrchestrator.kt` | Removed duplicate `ScheduledAgentWorker` class |
| `accessibility/execution/AccessibilityExecutionEngine.kt` | Added `import kotlinx.coroutines.withContext` |
| `agent/loop/AgentLoop.kt` | Corrected all `ExecutionStatusBus` calls to match actual 7-method API |
| `agent/loop/tool/ToolDispatcher.kt` | `SearchResult`/`AlarmTool`/`NotesTool` API fixes; `CommandResult.message ?: ""` for String? |
| `ui/viewmodel/ChatViewModel.kt` | `_isGenerating` StateFlow, `ModelController` params, local vars, `ConversationSummarizer.summarize()` arg order |

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
