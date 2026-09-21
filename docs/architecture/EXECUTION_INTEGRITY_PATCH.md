# Execution Integrity Patch

## Scope

This patch hardens the shared execution boundary used by `main` and `cp-foundation`. It does not change provider credentials, persisted user data, account permissions, or release signing configuration.

## Implemented controls

| Area | Change | Verification |
|---|---|---|
| Request identity | Added `ExecutionIdentity` with request, session, execution, and deterministic tool-call correlation identifiers. | `ExecutionIntegrityTest.identityKeepsSessionAndBuildsDistinctToolCalls` |
| Lifecycle | Added a deterministic execution lifecycle state machine with explicit terminal states and illegal-transition rejection. | `ExecutionIntegrityTest.stateMachineAllowsNormalLifecycleAndExactlyOneTerminalState` and `stateMachineRejectsIllegalSkip` |
| Terminal delivery | Added a thread-safe exactly-once terminal callback guard and applied it in `HybridOrchestrator`. | `ExecutionIntegrityTest.terminalDeliveryIsExactlyOnce` |
| Tool idempotency | Added a per-execution tool ledger keyed by execution, tool, arguments hash, and parent step; integrated it into `AgentLoop`. | `ExecutionIntegrityTest.toolLedgerBlocksOnlyExactDuplicateAndAllowsChangedArguments` |
| Privacy | Added `allowCloud` to `ExecutionRequest` and made `RoutingPolicy` fail closed to local execution when it is false. | `RoutingPolicyTest.requestPrivacyBoundaryBlocksCloudEvenInHybrid` |
| Provider errors | Added explicit model-not-found classification for HTTP 404 and retryable timeout classification for HTTP 408/504. | `CloudErrorMapperTest` additions |
| Mixed language UI | Routed user, assistant, and streaming bubbles through the existing BiDi-aware Markdown renderer so Arabic/English mixed content and code stay directionally isolated. | Static marker check; runtime UI build remains pending Android SDK availability |
| Validation tooling | Fixed `verify_airi_changes.py` to discover the current checkout rather than relying on a stale `/home/ubuntu/AIRI-cp` path. | Both checkouts pass the script |

## Validation results

The repository’s static verification suite passed on both checkouts:

- `verify_core_changes.py`: **88/88 checks passed**
- `verify_airi_changes.py`: **passed for main and cp-foundation**
- `verify_resources.py`: **1709 resource references validated**
- `git diff --check`: **passed**
- Custom integrity marker checks: **passed on both branches**

The Android unit-test task could not reach compilation because this sandbox has no Android SDK configured. Gradle reported: `SDK location not found`; no source compilation failure was reported before that environment gate.

## Official guidance consulted

The implementation follows Android’s state-holder and unidirectional data-flow guidance, Compose layout-direction guidance, and Google Gemini API error-handling guidance:

- [Android state holders](https://developer.android.com/topic/architecture/ui-layer/stateholders)
- [Compose alignment and layout direction](https://developer.android.com/develop/ui/compose/layouts/alignment)
- [Gemini API troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting)
- [Gemini API errors](https://ai.google.dev/gemini-api/docs/api-errors)

## Remaining environment-dependent check

A full `:app:testDebugUnitTest` and Android build should be rerun on a machine with the project’s required Android SDK/NDK and Gradle toolchain configured. This is an environment prerequisite, not a result that can be honestly marked as passed in the current sandbox.
