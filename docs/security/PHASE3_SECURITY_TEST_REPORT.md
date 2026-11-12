# AIRI Phase 3 — Security, Privacy, and Adversarial Verification

**Baseline:** `dab52581` (`main`)
**Date:** 2026-11-12
**Status:** `OPEN — not a release closure`

## Scope and evidence rule

This report separates source-level controls, deterministic JVM tests, Android/device tests, and credentialed Firebase/provider evidence. A static guard or unit test is not treated as proof of a deployed Firebase function, a real OAuth provider callback, Android Keystore behavior on a device, or a production sandbox boundary.

No Firebase deployment was performed in this baseline. No service-account key, project credential, OAuth client secret, or production token was created or exposed.

## Initial disposition

| Area | Baseline | Severity | Evidence / next gate |
|---|---|---:|---|
| Shell metacharacters and shell-free argv execution | PASS at source/unit-test level | P0 | `SandboxExecutorSecurityTest`; requires CI execution on the current head |
| Workspace traversal and symlink checks | PASS at source/unit-test level | P0 | `SandboxExecutorSecurityTest`; add Gateway-path equivalents |
| Destructive terminal commands | FAIL-CLOSED but incomplete | P0/P1 | `TerminalExecutionPolicy` returns approval-required/denied; no durable approval continuation |
| Direct `SandboxExecutor` construction | OPEN | P0/P1 | Direct production callers remain in `SandboxManager` and `ProductionCodingRuntimeFactory`; must be hidden behind one gateway or typed capability |
| Terminal correlation and ownership | OPEN | P1 | `TerminalExecutionGateway.executeForAgent` accepts only command and agent ID; task/run/step/project/execution/action/session binding is absent |
| Terminal evidence retention | OPEN | P1 | Agent sessions are ephemeral and `SandboxSession.execLog` is in-memory; no durable start/terminal evidence from the gateway |
| Terminal output redaction | OPEN | P1 | Commands are redacted, but command output/error can still be returned raw to tool/model/UI paths |
| Cancellation/late callback fencing | OPEN | P1 | Existing cancellation is not a complete generation/quiescence contract across Gateway, TerminalRuntime, AgentLoop, and audit |
| SSRF host/redirect checks | PASS at source/unit-test level; runtime evidence pending | P1 | `HostAddressPolicy`, `CustomSkillSecurity`, marketplace/search redirect checks; add IPv4/IPv6/DNS-rebinding regression matrix |
| OAuth state/TTL/single-use | PASS at source/unit-test level; provider/device evidence pending | P1 | `OAuthStateRegistry`; must test replay, expiry, provider mismatch, owner binding, `onCreate`/`onNewIntent`, competing scheme app |
| OAuth code/state logging | OPEN for full-surface proof | P1 | Review all logs, analytics, breadcrumbs, and traces with adversarial values; no production evidence yet |
| SecretVault cold-start gate | PARTIAL | P0/P1 | `SecretHealthChecker` exists; provider-use-before-initialize and process-death/restore tests remain required |
| Keystore invalidation/backup/restore/upgrade | BLOCKED in sandbox | P1 | Requires Android device/emulator matrix and backup/restore evidence |
| Crash/telemetry consent | PARTIAL | P1 | Consent wrappers exist; requires opt-in/opt-out network/runtime verification |
| Firebase Functions deployment | BLOCKED | P0 | Requires authorized Firebase project, deployment identity, review approval, and non-secret deployment evidence |
| App Check/Auth/401/403/TTL/revoke | BLOCKED | P0/P1 | Requires deployed functions and credentialed integration tests |
| Remote Android/Desktop session | BLOCKED | P1 | Requires configured remote backend and paired Android/Desktop runtime |
| Purchase verification/account deletion/retry | PARTIAL/BLOCKED | P1 | Local coordinator and verifier exist; real backend and partial-failure evidence pending |
| Prompt injection/red-team | PARTIAL | P0/P1 | Static policies exist; adversarial fixture corpus and end-to-end tool boundary tests remain |
| Sandbox boundary | LIMITED | P1 | `SandboxExecutor` is a policy sandbox, not an OS sandbox; this must remain an explicit distribution gate |

## Existing deterministic tests to run in CI

```text
:app:testDebugUnitTest --tests '*SandboxExecutorSecurityTest'
:app:testDebugUnitTest --tests '*OAuthStateRegistry*'
:app:testDebugUnitTest --tests '*CustomSkillSecurity*'
:app:testDebugUnitTest --tests '*PrivacyGuard*'
:app:testDebugUnitTest --tests '*SecureApiKeyStore*'
python3 tools/security_scan.py
python3 tools/verify_core_changes.py
```

The exact Gradle invocation remains subject to the repository's Android SDK/CI environment. A locally unavailable SDK or Firebase project is an environment limitation, not a PASS.

## P0/P1 implementation order

1. Remove or capability-gate direct `SandboxExecutor` shell construction and make the shared argv/policy validator the only admission path.
2. Introduce a required `TerminalExecutionRequest` correlation contract and fail closed when ownership fields are absent or stale.
3. Add central output/error redaction before tool results, model context, UI/activity, trace, and audit.
4. Add terminal start/terminal evidence with exactly-one terminal outcome and explicit degraded-audit state.
5. Add generation fencing and bounded stop/quiescence tests.
6. Add adversarial OAuth/secret/SSRF regression fixtures.
7. Execute credentialed Firebase/device gates only with approved project credentials and record hashes/configuration without secrets.

## Release decision

**Not releasable under the Phase 3 criteria at baseline.** The repository has meaningful fail-closed controls and deterministic tests, but the P0/P1 open findings and the missing Firebase/device evidence prevent a security closure claim.
