---
name: Context Budget Architecture
description: Sprints 1-3 design decisions — ContextBudget, PromptBudgetLedger, SessionHandle; migration rules for hardcoded context constants.
---

## Sprint 1 — ContextBudget (single source of truth)

**Rule:** No subsystem may define its own context capacity constant. All values flow from `LlamaNative.getNCtx()` → `ContextBudget` → everything else.

**9 hardcoded constants eliminated:**
| Was | Now |
|-----|-----|
| `CapabilityProfile.LOCAL_CPU.maxContextTokens = 4096` | `ContextBudget.nCtx` |
| `CapabilityProfile.LOCAL_CPU.supportsLongContext = false` | `ContextBudget.isLongContextModel` |
| `LlamaManager.NON_HISTORY_OVERHEAD = 616` | `ContextBudget.nonHistoryOverhead` |
| `LlamaManager.MIN_HISTORY_TOKENS = 256` | `ContextBudget.historyTokens` (coerceAtLeast) |
| `LlamaManager.maxHistoryTokens = (mode.nCtx - 616)` | `contextBudget.historyTokens` (computed property) |
| `AgentLoop requiresLongContext > 8_192` | `contextBudget.longContextThreshold` (nCtx / 2) |
| `PromptService.MAX_RAG_CHARS = 2400` | `contextBudget.ragChars` (fallback to 2400) |
| `PromptService.MAX_SUMMARY_CHARS = 1600` | `contextBudget.summaryChars` (fallback to 1600) |
| `DynamicPromptEngine.DEFAULT_MAX_RAG_TOKENS = 512` | `contextBudget.ragTokens` (fallback to 512) |

**Where `fromNative()` is called in LlamaManager:**
- After `loadModelWithProgress()` sets `isLoaded = true` (primary load path)
- After `loadModel()` result check (legacy load path)
- After `LlamaNative.setRuntimeMode()` in `applyRuntimeMode()`

**Why fromNative after setRuntimeMode, not just using mode.nCtx:**
llama.cpp internally rounds nCtx to its own alignment values — the requested 1536 may become 1600. Always read back the live value.

**CapabilityProfile.forLocalModel():** Use this instead of `LOCAL_CPU.copy()` after model load. The static `LOCAL_CPU.maxContextTokens` is now 1536 (conservative fallback), not 4096.

## Sprint 2 — PromptBudgetLedger (overflow prevention)

**Package:** `com.airi.assistant.ai.prompt.budget.PromptBudgetLedger`

**Key design:** `forBudget(contextBudget)` mints a ledger and pre-claims SYSTEM + GENERATION + SUMMARY + RAG slots. Remaining budget is available for SKILLS, TOOLS, EXTRA_CONTEXT, HISTORY (in that priority order). `claim()` returns granted ≤ requested, never throws on overflow.

**DynamicPromptEngine.build() default change:** `maxRagTokens` parameter default changed from `512` to `-1`. When `-1`, the budget is derived from `contextBudget.ragTokens`. Explicit values override (e.g. `buildFast()` still passes `128`). Call sites using named parameters are unaffected.

## Sprint 3 — SessionHandle (explicit session ownership)

**Package:** `com.airi.assistant.ai.session.SessionHandle`

**Minting location:** `LlamaManager.reconcileSession()` — immediately after `LlamaNative.beginSession()`.  
**Sentinel:** `SessionHandle.NONE` (sessionId = -1) — used before first session.  
**Validation:** `matchesNative()` calls `LlamaNative.nativeGetSessionId()` — must be on llamaDispatcher.

**Future multi-agent hook:** Each agent will hold its own SessionHandle routed to a distinct native slot. No API change needed — the SessionHandle already carries everything required (sessionId, contextBudget, modelPath).

## Backward compatibility rules

- All new params have defaults (`contextBudget = ContextBudget.UNLOADED`) — existing callers compile without change.
- `ContextBudget.UNLOADED.nCtx = 1536` — conservative fallback, not zero, so char/token caps degrade gracefully before model load.
- `AgentLoop` accepts `contextBudgetProvider: () -> ContextBudget = { ContextBudget.UNLOADED }` — default keeps old behaviour for callers that don't pass one.
- `LlamaManager.NON_HISTORY_OVERHEAD` and `MIN_HISTORY_TOKENS` are kept as private companion constants for reference; they are no longer used in any computation.

## Sprint 4 — Multi-Agent Design

Design doc: `docs/MULTI_AGENT_DESIGN.md`  
No implementation. Key insight: llama.cpp separates `llama_model*` (weights, shared) from `llama_context*` (KV cache, per-agent). Multi-agent is feasible on 6GB RAM for 7B+ models by sharing the model weights and allocating N small KV caches.  
New primitives needed: `NativeContextPool`, `AgentSession`, `TaskOrchestrator`, `AgentMessageBus`.
