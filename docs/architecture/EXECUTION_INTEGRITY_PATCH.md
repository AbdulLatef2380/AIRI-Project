# Execution Integrity, Runtime Capability, and Chat UX Patch

## Scope

This patch hardens the shared execution boundary used by `main` and `cp-foundation`, then applies the requested chat-composer and navigation corrections. It does not change provider credentials, persisted user data, account permissions, or release-signing configuration. The same source changes are maintained in both requested branches.

## Reliability and execution controls

| Area | Implemented control | Verification |
|---|---|---|
| Request identity | `ExecutionIdentity` and backend-boundary resolution ensure every request has a non-blank session, request ID, and execution ID before routing or dispatch. | Identity and boundary unit tests |
| ChatViewModel ↔ AgentLoop identity | Both layers use `ChatExecutionIdentityContract`; retries preserve the same execution identity. | Identity integration tests |
| Lifecycle | `HybridOrchestrator` now publishes lifecycle phases (`PREPARING`, `VALIDATING`, `PREPARING_CONTEXT`, `EXECUTING`, `STREAMING`, and terminal states) through diagnostics. | Lifecycle state-machine tests, compile and unit suite |
| Cancellation | Orchestrator cancellation now has one centralized terminal path. It calls the backend cancellation hook, transitions to `CANCELLED`, and delivers one terminal cancellation callback. Cloud cancellation cancels the active request job so the provider coroutine reaches its `finally` cleanup. | Cancellation terminal-state test; full unit suite |
| Terminal delivery | `TerminalDeliveryGuard` protects completion, failure, and cancellation callbacks from duplicate delivery. The direct vision/image path now uses the same exactly-once guard. | Exactly-once tests and source review |
| Cloud failover | Permanent configuration, authentication, model, and request failures stop at the selected provider instead of being hidden by unrelated fallback attempts. Failover remains available for transient network, timeout, server, and rate-limit classes. | Cloud error mapper/retry tests and source review |
| Tool idempotency | Per-execution tool ledger is keyed by execution, tool, argument hash, and parent step; exact duplicates are suppressed while changed arguments or legitimate steps remain executable. | Tool-ledger tests |
| Privacy | `allowCloud=false` remains fail-closed at routing; cloud candidates receive the sanitized request rather than the original request. | Routing and privacy tests; `verify_core_changes.py` |
| Context recovery | `LlamaManager` snapshots the bounded in-memory history on serialized unload, clears native/session state, and restores a trimmed snapshot after successful reload. Model identity is cleared on unload and recorded on load. | Source review and compile/unit validation; device/native reload still requires an emulator or physical device |
| Local model ownership | Local inference no longer silently executes against a different loaded model than the request selected; it returns an actionable mismatch error instead. | Compile/unit validation and source review |
| Failure history integrity | Generation and vision failures are exposed through a transient `lastExecutionError` projection and are not persisted as assistant conversation messages. Successful assistant replies remain persisted normally. | Source review and full unit suite |
| Typed event correlation | Execution trace events and fallback transitions carry stable correlation IDs and execution ownership. | Correlation-ID tests |

## Requested chat and navigation changes

| Requirement | Implemented behavior |
|---|---|
| 1. Composer size | The collapsed composer height was reduced modestly (`40dp` maximum for the text field and slightly smaller bottom padding) without making the controls cramped. Expanded editing remains available. |
| 2. Live/send action position | The utility/action row now uses an explicit LTR placement layer so the live/send action remains on the right, with the connectors control immediately to its left, independent of the selected app language. |
| 3. Stable model name | The top model selector no longer replaces the selected model name with `generating` while a response is running. Its model identity remains stable; progress is represented elsewhere. |
| 4. Full-screen editor affordance | The full-screen editor icon is hidden for lines one through four and appears automatically from line five onward. |
| 5. Message separation | The existing reverse-layout list keeps the streaming bubble as a separate keyed item and the persisted messages as distinct keyed items with spacing. The assistant/user bubbles remain separate composables and no failure placeholder is inserted into history. |
| 6. Thinking indicator | The English `generation`-style streaming placeholder is replaced by an AIRI avatar plus animated dots. The indicator is visual and localized through the existing generation state rather than exposing raw English lifecycle text. |
| 7. Duplicate templates option and drawer shape | The duplicate Templates menu entry was removed while the Switch Model action remains. The side drawer now has a restrained rounded trailing edge rather than a fully rectangular edge. |

## Architecture foundation completion

The repository-aware foundation map is recorded in [`ARCHITECTURE_FOUNDATION_MAP.md`](ARCHITECTURE_FOUNDATION_MAP.md). The audit confirmed that capability resolution, attachment admission, resource budgeting, context assembly, privacy boundaries, routing, execution, and trace already have owners in AIRI. No parallel router, memory manager, event bus, or execution engine was introduced.

The remaining explicit product gap was completed with `AiriIdentityProfile`. It is a small pure contract consumed by the existing `PromptService` path through `ChatViewModel`. It activates only for identity/about questions, states the required origin accurately, refuses invented biography, and derives model/vision statements from the current `ModelCapabilityDescriptor`. It is not a separate chatbot and it does not claim a feature merely because the product has a corresponding screen or class.

## Validation performed

The following checks passed on `cp-foundation` after the reliability changes and after the UI changes:

- `verify_core_changes.py`: **88/88 checks passed**.
- `verify_airi_changes.py`: **passed for main and cp-foundation**.
- `verify_resources.py`: **1,709 resource references validated**.
- `git diff --check`: passed.
- `:app:testDebugUnitTest`: **BUILD SUCCESSFUL** using JDK 17, Android SDK 36, NDK `25.2.9519653`, in-process Kotlin compilation, and two Gradle workers.
- Targeted `LanguageRuntimeManagerTest`: **BUILD SUCCESSFUL**.
- Targeted `AiriIdentityProfileTest` and `ModelCapabilityEngineTest`: **BUILD SUCCESSFUL**.
- The final post-UI full application unit-test run compiled the changed `ChatScreen.kt`, KSP sources, and unit-test sources successfully and completed `testDebugUnitTest` with exit code 0.

The build produced only pre-existing Compose deprecation warnings for `Divider` and a few non-mirrored icons; these are warnings, not test or compilation failures, and are outside the requested behavior changes.

## Official guidance consulted

The implementation follows Android state-holder and unidirectional-data-flow guidance, Compose layout-direction guidance, and Google Gemini API error-handling guidance:

- [Android state holders](https://developer.android.com/topic/architecture/ui-layer/stateholders)
- [Compose alignment and layout direction](https://developer.android.com/develop/ui/compose/layouts/alignment)
- [Gemini API troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting)
- [Gemini API errors](https://ai.google.dev/gemini-api/docs/api-errors)

## Boundaries and remaining device validation

No claim is made that a sandbox Android device or emulator performed native model inference, real provider streaming, visual screenshot acceptance, or OS accessibility-service validation. The source now has deterministic unit coverage and static checks for the reliability contracts, while native lifecycle timing and final visual acceptance remain appropriate for the project’s normal emulator/device CI environment.

The test runner used for reproducibility is `/home/ubuntu/run_airi_unit_tests.sh`; it explicitly selects JDK 17, Android SDK 36, bounded Gradle workers, and in-process Kotlin compilation to avoid the previous stale-daemon/JRE failure mode.

## Branch synchronization

The completed source and test changes were mirrored between the two requested worktrees, `main` and `cp-foundation`, before final verification. The next repository operation should commit each branch independently and push only after checking the branch heads and clean working-tree contents.

> This document records implemented controls and observed validation results; it does not substitute for emulator screenshots or a real-device native inference run.
