# Multi-Agent Architecture Design

**Status:** Design only — no implementation. Sprint 4 deliverable.  
**Date:** 2026-06-25  
**Author:** Architecture team (AIRI)  
**Prerequisite sprints:** Sprint 1 (ContextBudget), Sprint 2 (PromptBudgetLedger), Sprint 3 (SessionHandle)

---

## Executive Summary

This document specifies the architecture for extending AIRI from a single-agent to a multi-agent system where independent AI agents run concurrently, each owning an isolated native inference context. The design is a **continuation** of the three preceding sprints — every new API introduced in Sprints 1–3 was designed with this document's requirements in mind. No breaking API changes are needed to implement this design.

---

## 1. Problem Statement

### Current architecture (post-Sprint 3)

```
User ──► ChatViewModel ──► AgentLoop ──► HybridOrchestrator
                                               │
                                    LlamaManager (1 context)
                                               │
                                    LlamaBridge.cpp
                                    ├── g_llm_ctx  (one llama_context*)
                                    ├── g_kv_cache (managed by context)
                                    └── g_n_past   (one position counter)
```

Single-session constraint: only one agent can generate tokens at a time. The native layer has one global `llama_context*`. Concurrent requests must queue behind `LlamaManager.lifecycleLock`.

### Target architecture (this design)

```
TaskOrchestrator
├── PrimaryAgent    ──► SessionHandle(id=1) ──► NativeContext(slot=0)
├── ResearchAgent   ──► SessionHandle(id=2) ──► NativeContext(slot=1)
└── MemoryAgent     ──► SessionHandle(id=3) ──► NativeContext(slot=2)
```

Each agent holds a `SessionHandle` (already implemented) and generates into its own isolated KV cache. Tool results, web searches, and memory writes flow between agents via a structured message bus — no agent accesses another's context directly.

---

## 2. Design Goals

| Goal | Constraint |
|------|-----------|
| True parallel token generation (N agents, N native contexts) | Memory budget: each context costs `nCtx × n_layers × element_size` bytes |
| No shared mutable state between agents | All cross-agent communication via typed messages only |
| Primary agent can delegate subtasks and await results | Delegation must not block the primary context's KV cache |
| Graceful degradation on OOM | Spin down lowest-priority agent, reclaim its context slot |
| Zero breaking changes to Sprints 1–3 APIs | `ContextBudget`, `SessionHandle`, `PromptBudgetLedger` are unchanged |

---

## 3. New Components

### 3.1 NativeContextPool

**Location:** `com.airi.assistant.ai.pool.NativeContextPool`

```kotlin
class NativeContextPool(val maxSlots: Int) {
    fun acquire(budget: ContextBudget): ContextSlot?   // null if all slots used
    fun release(slot: ContextSlot)
    fun slotsInUse(): Int
    fun remainingMemoryBytes(): Long
}

data class ContextSlot(
    val slotId:  Int,
    val budget:  ContextBudget,
    val session: SessionHandle
)
```

**Behaviour:**
- `maxSlots` is determined at startup from `HardwareProfiler.availableRamMb()` divided by the estimated per-context cost.
- `acquire()` calls the new native function `nativeCreateContext(nCtx, slotId)` which allocates a distinct `llama_context*` stored in `g_ctx_pool[slotId]`.
- `release()` calls `nativeDestroyContext(slotId)` and removes the entry.
- Thread-safe: protected by a `Mutex`.

**Memory model:**

```
Total available RAM (e.g. 6 GB)
├── OS + Android overhead                = 1.5 GB  (reserved)
├── Model weights (shared, read-only)    = 2.0 GB  (Mistral-7B Q4_K_M)
└── KV cache pool                        = 2.5 GB  remaining
     ├── Slot 0 (primary, nCtx=4096)     = 0.5 GB
     ├── Slot 1 (research, nCtx=2048)    = 0.25 GB
     └── Slot 2 (memory, nCtx=1024)      = 0.125 GB
     └── [headroom]                      = 1.625 GB
```

Key insight: model weights are loaded once and shared across all slots — only the KV caches are per-slot. This is why multi-agent on a 6 GB device is feasible even for 7B+ models.

### 3.2 AgentDescriptor

**Location:** `com.airi.assistant.ai.agent.AgentDescriptor`

```kotlin
data class AgentDescriptor(
    val agentId:     String,
    val role:        AgentRole,
    val budgetHint:  ContextBudgetHint = ContextBudgetHint.COMPACT,
    val priority:    Int               = 5,   // 1=highest, 10=lowest
    val maxIdleSec:  Int               = 120  // evict after this many idle seconds
)

enum class AgentRole { PRIMARY, RESEARCH, MEMORY, BROWSER, TOOL_EXECUTOR }

enum class ContextBudgetHint {
    MINIMAL,   // 1024 tokens — memory agent, tool-only tasks
    COMPACT,   // 2048 tokens — research subtasks
    STANDARD,  // 4096 tokens — default conversational agent
    EXTENDED   // 8192 tokens — complex long-document tasks
}
```

### 3.3 AgentSession

**Location:** `com.airi.assistant.ai.agent.AgentSession`

```kotlin
class AgentSession(
    val descriptor:  AgentDescriptor,
    val slot:        ContextSlot,
    val inbox:       Channel<AgentMessage>,
    val outbox:      Channel<AgentMessage>
) {
    val handle: SessionHandle get() = slot.session

    suspend fun send(message: AgentMessage)    // put to outbox
    suspend fun receive(): AgentMessage        // take from inbox (suspending)
    fun isAlive(): Boolean                     // slot still in pool
}
```

### 3.4 TaskOrchestrator

**Location:** `com.airi.assistant.ai.orchestration.TaskOrchestrator`

The top-level multi-agent coordinator. Replaces direct `AgentLoop` invocation for complex tasks.

```kotlin
class TaskOrchestrator(
    private val pool:      NativeContextPool,
    private val registry:  AgentRegistry,
    private val bus:       AgentMessageBus
) {
    /**
     * Execute a task that may require multiple agents.
     * 
     * Simple tasks: routed to PRIMARY agent only (identical to current behaviour).
     * Complex tasks: PRIMARY delegates sub-tasks; results flow back through bus.
     */
    suspend fun execute(task: UserTask): TaskResult

    /** Spawn a new agent for a subtask. Returns its session or null if pool is full. */
    suspend fun spawnAgent(descriptor: AgentDescriptor): AgentSession?

    /** Await a specific agent's result with a timeout. */
    suspend fun awaitResult(session: AgentSession, timeoutMs: Long): AgentMessage?

    /** Gracefully terminate an agent session, releasing its context slot. */
    suspend fun terminateAgent(session: AgentSession)
}
```

**Task routing logic:**

```
UserTask arrives
    │
    ├── complexity = SIMPLE → dispatch to PRIMARY agent directly
    │                         (zero allocation overhead, identical to current)
    │
    └── complexity = COMPLEX → TaskOrchestrator.decompose(task)
                               ├── subtask_1 → spawnAgent(RESEARCH)
                               ├── subtask_2 → spawnAgent(TOOL_EXECUTOR)
                               └── primary handles synthesis
```

### 3.5 AgentMessageBus

**Location:** `com.airi.assistant.ai.orchestration.AgentMessageBus`

All inter-agent communication is typed and serializable (for future process isolation).

```kotlin
sealed class AgentMessage {
    data class Task(
        val taskId:      String,
        val fromAgent:   String,
        val toAgent:     String,
        val instruction: String,
        val context:     List<ChatMessage> = emptyList()
    ) : AgentMessage()

    data class Result(
        val taskId:    String,
        val fromAgent: String,
        val toAgent:   String,
        val content:   String,
        val success:   Boolean,
        val latencyMs: Long
    ) : AgentMessage()

    data class Interrupt(
        val reason: InterruptReason
    ) : AgentMessage()

    data class HeartBeat(val agentId: String, val timestampMs: Long) : AgentMessage()
}

enum class InterruptReason { USER_CANCEL, OOM_EVICTION, TIMEOUT, PARENT_CANCELLED }
```

**Design constraint:** No agent may write to another agent's `ContextSlot`. Cross-agent information transfer is ONLY via `AgentMessage.Result` content strings — never via shared memory or shared KV cache entries.

---

## 4. Native Layer Changes

### 4.1 Context pool in C++

Current `LlamaBridge.cpp` state:

```cpp
// Global — single context
static llama_context* g_llm_ctx = nullptr;
static int            g_n_past  = 0;
static long           g_session_id = 0;
```

Required change — context pool:

```cpp
// Per-slot state
struct ContextSlot {
    llama_context* ctx      = nullptr;
    int            n_past   = 0;
    long           slot_id  = -1;
    bool           in_use   = false;
};

static std::array<ContextSlot, MAX_CTX_SLOTS> g_ctx_pool;
static std::mutex g_pool_mutex;
```

New JNI functions required:

```
nativeCreateContext(nCtx: Int, slotId: Int): Boolean
nativeDestroyContext(slotId: Int)
nativeGenerateInSlot(slotId: Int, tokens: IntArray, maxNew: Int, ...): String
nativeGetKvPositionInSlot(slotId: Int): Int
nativeBeginSessionInSlot(slotId: Int)
nativeGetSessionIdInSlot(slotId: Int): Long
```

The existing JNI API (`nativeGenerate`, `getNCtx`, `nativeGetSessionId`, etc.) continues to operate on `g_ctx_pool[0]` (the primary slot) for full backward compatibility.

### 4.2 Model weight sharing

llama.cpp already separates `llama_model*` (weights, shared) from `llama_context*` (KV cache + runtime state, per-session). The multi-agent design exploits this:

```cpp
// Loaded once
static llama_model* g_model = nullptr;

// One per agent slot
ContextSlot::ctx = llama_new_context_with_model(g_model, ctx_params);
```

This is not a new capability — it is already how llama.cpp is designed internally. The change is exposing it through the JNI boundary.

---

## 5. Sprint 1–3 API Integration

All Sprint 1–3 APIs are forward-compatible with this design:

### ContextBudget (Sprint 1)

`ContextBudget` becomes per-slot. Each `ContextSlot` carries its own `ContextBudget` derived from `nativeGetNCtxInSlot(slotId)` after context creation. No API change.

```kotlin
// After nativeCreateContext(nCtx=2048, slotId=1):
val budget = ContextBudget.fromNativeSlot(slotId = 1)
// → ContextBudget(nCtx=2048, ragTokens=128, summaryTokens=64, historyTokens=...)
```

### SessionHandle (Sprint 3)

`SessionHandle` already carries `slotId` implicitly via `sessionId` (which will be `slotId * BASE + counter`). The `matchesNative()` method becomes `nativeGetSessionIdInSlot(slot.slotId) == sessionId`. No data-class change.

### PromptBudgetLedger (Sprint 2)

Each agent has its own `PromptBudgetLedger` minted from its slot's `ContextBudget`. No structural change. Multiple ledgers exist simultaneously but never share state.

---

## 6. Memory Safety & OOM Handling

### OOM detection

```kotlin
fun onLowMemory() {
    // Evict agents by ascending priority (highest priority number = lowest priority)
    pool.slotsInUse()
        .sortedByDescending { it.descriptor.priority }
        .drop(1) // keep at least PRIMARY
        .forEach { slot ->
            bus.send(slot.agentId, AgentMessage.Interrupt(InterruptReason.OOM_EVICTION))
            pool.release(slot)
        }
}
```

### Slot acquisition failure

When `pool.acquire()` returns `null`, `TaskOrchestrator` falls back to sequential execution on the primary slot — the subtask is queued rather than spawned. This degrades performance but never crashes.

### Context budget guardrail

Before spawning an agent, `TaskOrchestrator` calls:

```kotlin
val estimatedCost = ContextBudgetHint.toBytes(descriptor.budgetHint)
if (pool.remainingMemoryBytes() < estimatedCost * SAFETY_FACTOR) {
    return null  // don't spawn, fall back to sequential
}
```

---

## 7. Backward Compatibility Guarantee

For all tasks that do not require multi-agent delegation:

1. `TaskOrchestrator.execute()` routes to PRIMARY agent's slot (slot 0).
2. Slot 0 is always pre-allocated at startup (identical to current `LlamaManager`).
3. The primary `AgentLoop` runs with exactly one `SessionHandle` and one `ContextBudget` — no change to the hot path.
4. Users see zero behavioral difference for simple tasks.
5. All existing `AIRI_PROOF` log tags continue firing in the same positions.

The only observable change for simple tasks: `TaskOrchestrator` adds one log line before delegation: `AIRI_PROOF TASK_ROUTE agentId=primary complexity=SIMPLE`.

---

## 8. Implementation Roadmap

This is a design document only. No code is implemented in Sprint 4.

| Phase | Work | Prerequisite |
|-------|------|-------------|
| P4-A  | `NativeContextPool` + `nativeCreateContext` / `nativeDestroyContext` JNI | Sprint 3 merged |
| P4-B  | `AgentSession` + `AgentMessageBus` + typed messages | P4-A |
| P4-C  | `TaskOrchestrator` with simple/complex routing | P4-B |
| P4-D  | Spawn `RESEARCH` agent role for web + document tasks | P4-C |
| P4-E  | OOM watchdog + slot eviction | P4-C |
| P4-F  | `MEMORY` agent role (persistent background embedding) | P4-D |

Estimated incremental risk: LOW for P4-A through P4-C (additive, no changes to hot path). MEDIUM for P4-D onward (new agent roles require prompt engineering and result synthesis logic).

---

## 9. Open Questions

1. **Token budget for inter-agent messages:** Each `AgentMessage.Result` injected into the primary context consumes tokens. Should results be summarised before injection? The `PromptBudgetLedger.TOOLS` slot is the natural destination, but tool results can be long (e.g. full webpage content from `BROWSER` agent).

2. **llama.cpp batch inference:** llama.cpp supports `llama_decode()` with a shared batch across multiple sequences in a single context. Should we use one context with multiple sequences (llama.cpp's native multi-sequence mode) instead of multiple contexts? Trade-offs: fewer contexts = less memory but sequences share the cache eviction budget.

3. **Concurrency model:** Should multiple slots generate simultaneously on separate threads, or should the native layer remain single-threaded with cooperative time-slicing (each slot generates N tokens, then yields)? True parallelism requires careful benchmark work — llama.cpp's matrix operations are already multi-threaded internally.

4. **Session persistence across app restarts:** `SessionHandle.sessionId` is currently ephemeral (resets on every app launch). For background `MEMORY` agents that should survive restarts, session state would need serialisation. Interaction with `DurableTaskManager` (already in ServiceLocator) is the likely integration point.
