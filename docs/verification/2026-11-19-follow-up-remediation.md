# AIRI — Follow-up Remediation and Verification

**Date:** 2026-11-19  
**Branch:** `main`  
**Scope:** Review of the user-provided production-readiness concerns against the checked-out source. This report distinguishes source inspection from executable verification.

## Changes made in this follow-up

- **Concurrent tool idempotency:** `ToolExecutionPipeline` now coordinates in-flight executions by `RequestId`; concurrent duplicate requests await the same result instead of racing past the result ledger. Added a concurrency regression test.
- **Imported skill permissions:** `permissions` from GitHub manifests are now retained in `CustomSkill`, passed by the importer and dynamic registration path, and exposed by `CustomSkillAiriSkillAdapter.requiredPermissions` so the existing access policy can fail closed when a permission is not granted. The new field is nullable for compatibility with old Gson-persisted records.
- **GenUI card actions:** card action callbacks are rebuilt from the original assistant message and use the card's source-authored body; arbitrary callback fields are ignored. The renderer now marks the action `STAGED`, not `SUCCEEDED`, before the ViewModel has accepted it. Added a compiler regression test.
- **GenUI images:** the schema accepts only HTTPS image sources without embedded user information, and the renderer uses Coil `AsyncImage` to display them rather than printing the source URL as text. Added a parser regression test for HTTPS acceptance and `file:` rejection.
- **Secret Manager biometric gate:** entering the screen now requests biometric/device-credential authentication before loading API keys or showing the management list. Failed, unavailable, or unsupported authentication no longer silently succeeds; edit/delete/copy operations are also gated.
- **Profile photo replacement:** the existing UID-scoped local photo upload is implemented; replacement now preserves the previous file in a temporary backup until the new file has been moved successfully, and attempts restoration on failure.
- **Theme references and stale reports:** 47 direct `MaterialTheme.colorScheme` references in app UI sources were changed to `AiriTheme.colorScheme`, which resolves to the active Material color scheme. Historical color-refinement and verification reports now explicitly warn that their results are not a current visual/build verification.

## Findings not closed by this follow-up

- **Memory path:** the production chat path adds `RagRetriever.buildContextBlock()` to the system prompt. However, the separate `MemoryStore`/`PromptCompressor.compose()` fact-injection path has no production call sites, and extraction is asynchronous. A full end-to-end test proving extraction → durable storage → retrieval → final model prompt (including privacy/scope boundaries) remains necessary.
- **Planner, AgentLoop, and multi-tool model behavior:** source review supports the existence of a connected dispatch path, but this session did not run a live-model or Android integration scenario proving real tool calls, sequential calls, or planner-to-execution lifecycle behavior.
- **Theme literals:** direct `MaterialTheme.colorScheme` usage was normalized, but numerous hard-coded literals remain. Some are brand/status colors; some may still be surfaces or text. A semantic inventory plus light/dark/AMOLED screenshot or device review is still required before claiming full color-system unification.
- **Skill isolation:** permissions are now propagated to the runtime policy gate, but the imported skill mechanism still performs remote HTTP execution from the app and trusted first-party skills run in-process. The current sandbox is not a kernel-enforced process/UID sandbox. Do not claim strong isolation for untrusted executable code; a separate isolated service/backend is needed if arbitrary local code execution is introduced.
- **Full-screen biometric coverage:** management content and key loading are now gated, but this UI-level gate is not a hardware-backed cryptographic policy for every underlying secret-store read. Verify navigation recreation, background/foreground behavior, and any alternate secret access paths on-device.

## Verification performed

- `git diff --check`: passed (no whitespace errors).
- Source grep after color-reference normalization: the only remaining `MaterialTheme.colorScheme` occurrence is the canonical alias in `ui/theme/Theme.kt`.
- Skill execution check: current `SkillToolBridge.invoke` is a `suspend` function, and no `runBlocking` reference was found under `ai/skills`; the earlier report's claim about this bridge is stale. This source check is not a runtime verification of the full dispatcher path.
- Attempted `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace`: **not run to compilation**. Android SDK API 36 was installed in this sandbox, but Gradle stopped during project configuration because the installed Java 21 runtime does not provide `JAVA_COMPILER` (`javac` is absent). No external tools were installed after the user's instruction to stop installing tools.
- GitHub Actions for `main` commit `436cb93f`: `AIRI Oracle` and `AIRI Architecture Audit` completed successfully; `AIRI Android CI` and `AIRI Deep Audit` were still in progress at the time of this report. These results are not a local/device test result.
- Therefore no claim is made that the APK builds, unit tests pass, instrumentation tests pass, or the application has been exercised on a device.

## Release/push gate

This follow-up is not a full production-readiness closure. The code changes above should not be represented as a complete resolution of every concern until a JDK with compiler, the build, unit tests, instrumentation tests, and targeted on-device flows pass. The verified residual issues above require additional work before a clean release sign-off.
