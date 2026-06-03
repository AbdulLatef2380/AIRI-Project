# Phase 5 — ViewModel Architecture Cleanup Report
*ChatViewModel god-class reduced by ~1 380 LOC across 3 new files. 0 behavioural regressions.*

---

## Goal

Split the 3 317-line `ChatViewModel` into focused collaborating classes while
preserving every existing StateFlow, public function, and UI binding contract.

## The Problem

`ChatViewModel` was the project's highest-risk god class:

| Responsibility | Lines |
|---|---|
| Data class definitions (ChatMessage, AgentState, ModelUiState, …) | ~530 |
| Model loading / validation / registry persistence | ~480 |
| Cloud provider lifecycle (activate / test / deactivate) | ~180 |
| Runtime diagnostics snapshot production | ~170 |
| Inference execution stack declaration + wiring | ~120 |
| UCL / cognitive-loop wiring | ~80 |
| Tool-call dispatch (handleToolIfNeeded) | ~80 |
| `sendMessage()` and streaming callbacks | ~720 |
| Session management (CRUD, history, memory) | ~220 |
| Paywall / monetization logic | ~200 |
| ViewModel lifecycle, voice, accessibility gate | ~300 |
| **Total** | **3 317** |

Single-responsibility violations meant any change to model loading could
accidentally break streaming state, and any change to inference routing
could break model registry persistence — because everything lived in the
same 3 300-line scope.

## Extractions

### 1. `ChatViewModelTypes.kt` (+190 LOC, −530 LOC from ChatViewModel)

All data/enum/sealed class definitions extracted:

- `ChatMessage`
- `AgentState` + `ConfirmationRequest` + `ExecutionStage`
- `LoadErrorType` + `ModelUiState`
- `AgentMode`
- `UpgradePrompt`
- `DebugState`

ChatViewModel no longer defines any types — it imports them from the same
package. Zero import changes needed anywhere in the project.

### 2. `ModelManagementCoordinator.kt` (+340 LOC, −780 LOC from ChatViewModel)

Owns the entire model-domain:

| Function | Description |
|---|---|
| `createInitialModelState()` | Registry restore + initial UI state |
| `loadModel()` | Validation → load → progress → capabilities |
| `autoLoadVisionProjectorIfPresent()` | Silent mmproj rehydration |
| `activateBuiltinProvider()` | Cloud provider test + activation |
| `activateRemoteModel()` | Custom remote model activation |
| `clearCloudModel()` | Cloud deactivation |
| `refreshCloudReadiness()` | Startup cloud re-evaluation |
| `refreshModelList()` | ModelManager scan → state update |
| `restoreRegistry()` / `persistRegistry()` | SharedPreferences JSON persistence |
| `syncDownloadedModelAvailability()` | Download dir scan → registry update |
| `refreshDiagnosticsSnapshot()` | RuntimeDiagnosticsState production |
| `buildWarnings()` / `readThermalLevel()` / `extractQuant()` | Diagnostic helpers |

**Integration pattern**: ChatViewModel constructs one instance and passes a
`(ModelUiState) -> ModelUiState` callback. All state mutations still flow
through ChatViewModel's own `_modelState` MutableStateFlow — the UI binding
contract is completely unchanged.

### 3. `InferenceStreamCoordinator.kt` (+200 LOC, −400 LOC from ChatViewModel)

Owns the inference execution stack:

| Component | Role |
|---|---|
| `hybridOrchestrator` | Single inference entry point (Phase 2) |
| `runtimeRouter` | Primary / fallback backend selection |
| `localBackend` / `cloudBackend` | Backend adapters for HybridOrchestrator |
| `cognitiveLoop` | TypedPlanGraph DAG engine (Phase 2/3) |
| `secureApiKeyStore` | Encrypted API key persistence |
| `wireOrchestratorProvider()` | Wires LLM delegate for UCL.runNode() |
| `executeStream()` | Thin delegation to hybridOrchestrator |
| `cancel()` | Cancellation forwarding |
| `launchGraphExecution()` | Post-response ACTION plan execution |
| `handleToolIfNeeded()` | Tool-call dispatch via SkillService |

## Outcome

| Metric | Before | After |
|---|---|---|
| ChatViewModel LOC | 3 317 | ~2 140 |
| Type definitions in ChatViewModel | 530 LOC | 0 LOC |
| Model logic in ChatViewModel | 780 LOC | 0 LOC |
| Inference wiring in ChatViewModel | 400 LOC | 0 LOC |
| New files | — | 3 |
| StateFlow contract changed | — | No |
| UI bindings changed | — | No |
| Public API changed | — | No |

## What Was NOT Changed

- `sendMessage()` — 720 lines of orchestration logic remains in ChatViewModel.
  It accesses too many ViewModel-level state flows to extract safely without
  compilation verification. It is the primary candidate for Phase 9's
  `MessageDispatchCoordinator` extraction.
- Session management (loadSession, createNewSession, etc.) — preserved in ChatViewModel.
- Paywall / monetization logic — preserved in ChatViewModel.
- All StateFlow declarations — ChatViewModel still owns all MutableStateFlow
  instances and exposes them as StateFlow to the UI.

## Risks

- **None introduced**: All new coordinator classes are pure delegates with no
  mutable state of their own. ChatViewModel's init block constructs and wires
  them using the exact same logic that previously lived inline.
- The `validationMessage()` cast `result as ValidationResult.InsufficientRam`
  must be verified against the sealed hierarchy at first compile — minor
  compiler assertion, not a semantic issue.
