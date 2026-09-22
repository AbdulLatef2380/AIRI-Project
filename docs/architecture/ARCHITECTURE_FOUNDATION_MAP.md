# AIRI Architecture Foundation Map

This map records the repository-aware implementation decision for the architecture evolution sprint. The guiding rule is to extend existing ownership and avoid parallel engines.

| Proposed foundation | Existing AIRI owner | Current state | Repository-appropriate action |
|---|---|---|---|
| Model capability | `ModelCapabilityEngine`, `CapabilityProfile`, `ModelCapabilities`, `RuntimeRouter` | Implemented and connected. Capability, availability, and feasibility are separate typed values. Local multimodal readiness depends on the loaded projector; cloud model support is provider/model-ID based and custom providers remain unknown. | Preserve and extend tests; do not add a second model registry. |
| Request validation | `ExecutionRequest`, `ChatViewModel`, `AttachmentDispatchPolicy`, `ModelCapabilityEngine.check` | Implemented at the attachment send boundary before persistence/execution. Request identity is enforced at the execution boundary. | Preserve the small request envelope and keep validation at existing boundaries. |
| Attachment compatibility | `ChatAttachment`, core `AttachmentPolicy`, `AttachmentDispatchPolicy`, `ModelCapabilityEngine` | Implemented for text/image/video/audio/document/PDF requirement mapping, size/count/MIME limits, model alternatives among currently allowed local/cloud candidates, and fail-closed unknown capability. | Keep the UI as a projection; retain the ViewModel admission gate as source of truth. |
| Feasibility and resources | `ResourceBudgetManager`, `ResourceRegistry`, `DeviceProfiler`, `ModelCapabilityEngine.fromLocal` | Implemented for known local RAM requirements and resource snapshots. Unknown RAM remains `UNKNOWN`; resource warnings remain distinct from feasibility. | Reuse Resource Center; add focused tests only. Avoid a new resource engine. |
| Context assembly | `ContextBudget`, `PromptBudgetLedger`, `PromptService`, `DynamicPromptEngine`, RAG and summary services | Implemented and connected to prompt construction. Live `nCtx` drives RAG, summary, and history budgets. | Preserve current assembly and budget ledger; no second memory manager. |
| Local/cloud data boundary | `PrivacyGuard`, `RoutingPolicy`, `RuntimeRouter`, sanitized request path | Implemented fail-closed for `allowCloud=false` and sanitized cloud requests. | Preserve explicit privacy decisions and add tests when a new data source is connected. |
| Existing router | `RuntimeRouter`, `RoutingPolicy` | Remains routing authority; capability candidates are prepared before routing rather than replaced by a planner. | Extend only for concrete candidate/constraint gaps. |
| Execution lifecycle | `HybridOrchestrator`, `ExecutionIntegrity`, `ExecutionStatusBus`, diagnostics | Integrated with identity, cancellation, terminal guard, event correlation, and stale-generation protection. | Preserve existing ownership. |
| Trace and UI | typed trace buffer, diagnostics, activity bus, Compose chat list | Operational trace is retained; raw hidden reasoning is not exposed. Streaming and persisted messages are separate keyed list items. | Add UI/device tests where infrastructure exists; no global event bus. |
| AIRI identity/about behavior | `PromptService` and new `AiriIdentityProfile` | Newly completed as a canonical, capability-aware prompt contract. It activates only for identity/about questions and uses the current descriptor. | Keep one source of truth; do not create a separate chatbot. |

## Explicit non-goals

This sprint does not rebuild scheduling, skills, permissions, memory, RAG, the router, execution, or the event bus. It does not claim unsupported provider capabilities or invent biography beyond the required origin statement. It does not claim native device inference or visual acceptance without an emulator/device run.

## Validation matrix

| Requirement | Test or verification |
|---|---|
| Text model + text | `ModelCapabilityEngineTest.textOnlyBlocksImageAndAllowsText` |
| Text model + image | Same test; image is blocked before executor |
| Vision model + image | `visionWithProjectorAllowsImage` |
| Missing multimodal artifact | `declaredVisionWithoutProjectorIsUnavailable` |
| Unavailable model | `unavailableModelBlocksBeforeAttachmentCapability` |
| Sufficient RAM | `sufficientResourcesAreFeasible` |
| Insufficient RAM | `capabilityIsNotFeasibility` |
| Unknown RAM | `unknownResourcesRemainUnknownInsteadOfClaimingFeasibility` |
| Attachment staging/session ownership | `AttachmentDispatchPolicyTest` |
| Identity recognition | `AiriIdentityProfileTest.recognizesArabicAndEnglishIdentityQuestions` |
| Canonical origin and no invented biography | `AiriIdentityProfileTest.identityPromptStatesOriginAndDoesNotInventBiography` |
| Runtime artifact truthfulness | `AiriIdentityProfileTest.unavailableVisionIsDescribedAsNotReady` |
| Non-identity isolation | `AiriIdentityProfileTest.nonIdentityInputDoesNotReceiveIdentityInjection` |

## Remaining external verification

A connected Android emulator or device is still required for native llama.cpp inference, real multimodal projector loading, actual provider streaming, and screenshot/accessibility acceptance. Unit, static, and compile checks must not be reported as substitutes for those runtime checks.
