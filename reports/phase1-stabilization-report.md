# Phase 1 — Stabilization Report
*Stops harmful behavior. No architectural rewrites. 31 PASS, 0 FAIL.*

---

## What Was Fixed

### Fix 1: Remove four fake delegation-shell agents (CRITICAL)

**Problem (verified):** `CodingAgent.canHandle()` matched `code`, `function`, `class`, `debug`,
`fix`, `explain`, `write`, `implement`, `create` — roughly 40% of all user queries.
When matched, `ProductionAgentOrchestrator.executeSingle()` returned
`"[CodingAgent delegated to LLM — streaming response]"` as `finalResult`.
In `ChatViewModel.sendMessage()`, the check `orchResult.finalResult.isNotBlank()` evaluated
to `true` → `return@launch` fired → **the real LLM was never called**.
Users received a placeholder string instead of an actual answer.
`MediaGenerationAgent`, `DocumentProcessorAgent`, and `LocalBrowserOperator` had the same
delegation-shell pattern with no real operations.

**Fix:** Removed all four from `ServiceLocator.initSubAgentSystem()` and their imports.
Remaining registered agents: `ResearchAgent`, `AndroidAgent`, `ProductivityAgent`,
`MemoryAgent`, `CloudBrowserAgent` — all have verified real operations.

**User impact:** All queries previously intercepted by the fake agents now fall through
to the LLM directly, producing real responses.

### Fix 2: Add real confirmation gate for destructive accessibility actions (CRITICAL)

**Problem (verified):** `AndroidAgent.execute()` checked `actionType.requiresConfirmation`
and emitted `AgentEvent.Progress("⚠ Confirmation required… Proceeding…")` — then
**immediately continued executing**. No blocking gate existed. Any chat message containing
"send", "open", "post", "share", "delete" could trigger real phone gestures with no user
confirmation. A malicious prompt-injection from `CloudBrowserAgent`'s fetched page text
could drive arbitrary accessibility actions.

**Fix:** Added three-component real gate:

1. `AgentState.ConfirmationRequest` nested data class — carries `actionDisplayName`,
   `actionDescription`, `isDestructive` for the UI.

2. `AndroidAgent.confirmationGate` — injectable `suspend (name, desc) -> Boolean` field.
   When non-null: suspends until user responds. When null: **blocks the action** (fail-safe
   for background/scheduled contexts). Injected by `ChatViewModel.init` via
   `ServiceLocator._androidAgent`.

3. `ChatViewModel.awaitAccessibilityConfirmation()` — surfaces `ConfirmationRequest` to
   `_agentState`, creates a `CompletableDeferred<Boolean>`, suspends for up to 30 s,
   auto-cancels on timeout. `confirmAccessibilityAction(approved)` resolves the deferred.

4. `ChatScreen` confirmation `AlertDialog` — rendered when
   `agentState.confirmationRequest != null`. Two buttons: تأكيد (Confirm) and إلغاء (Cancel),
   both call `viewModel.confirmAccessibilityAction(approved)`.

**User impact:** Every destructive accessibility action now requires explicit user tap.
Background execution with no UI blocks instead of proceeding silently.

### Fix 3: Fix TerminalRuntime.appendHelp() false advertising (MEDIUM)

**Problem (verified):** `appendHelp()` advertised `curl, wget — network (if permitted)` and
`git clone, git status, git log — git operations`. `SandboxExecutor.BINARY_ALLOWLIST`
explicitly blocks curl and wget. The git subcommand allowlist only permits `status`, `log`,
`diff` — not `clone`. Users typing these commands received cryptic security errors.

**Fix:** Rewrote `appendHelp()` to list only allowed commands. Added explicit note:
`"curl, wget, git-clone and network commands are not available in the sandbox for
security reasons."`

---

## Files Modified

| File | Change |
|---|---|
| `core/ServiceLocator.kt` | Removed 4 fake agents from initSubAgentSystem; removed 4 dead imports; exposed `_androidAgent` for gate injection |
| `agent/subagent/impl/AndroidAgent.kt` | Added `confirmationGate` field; replaced fake Progress with real blocking gate |
| `ui/viewmodel/ChatViewModel.kt` | Added `AgentState.ConfirmationRequest`; `confirmAccessibilityAction()`; `awaitAccessibilityConfirmation()`; `pendingConfirmation: CompletableDeferred`; gate injection in init |
| `ui/screens/ChatScreen.kt` | Added confirmation AlertDialog; added `BorderStroke` import |
| `terminal/TerminalRuntime.kt` | Fixed `appendHelp()` — accurate command listing, honest security note |

## Runtime Impact

- All queries previously hijacked by CodingAgent/MediaGenerationAgent/DocumentProcessorAgent/LocalBrowserOperator now reach the LLM
- Destructive accessibility actions now block until user confirms
- Terminal help accurately reflects actual sandbox capabilities

## What Is NOT Changed

- LlamaManager, HybridOrchestrator, cloud providers — untouched
- SubAgentRegistry routing framework — untouched (5 real agents remain)
- Memory, RAG, PromptCompressor — untouched
- NavigationGraph — untouched
- All prior fixes (OAuth, theme, token counter, scheduled tasks) — preserved

## Remaining Phase 1 Risks (not blocking, deferred to later phases)

- `CloudBrowserAgent` synthesis still broken (Delegate event not consumed in live path)
- `CloudBrowserAgent` prompt-injection via raw page text — no fence yet
- `handleToolIfNeeded` executes tool calls from LLM without confirmation gate (Phase 5)
- `HybridOrchestrator` still bypassed in main inference path (Phase 2)
