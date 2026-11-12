# AIRI Phase 3.4 — Prompt Injection, SSRF, and Sandbox Red-Team Report

**Assessment date:** 2026-11-12
**Scope:** Phase 3.4 only: prompt injection, SSRF, skill/network boundaries, terminal argument/path/process handling, and sandbox release containment.
**Repository state:** Local `main` based on imported head `92747363`; changes are uncommitted. This report does not claim a remote push, CI run, Android runtime test, or provider test.
**Disposition:** `IMPLEMENTED — LOCAL TESTS BLOCKED BY MISSING ANDROID SDK — SECURITY CLOSURE OPEN`.

## Summary

The implementation adds bounded JSON serialization for untrusted content with common credential redaction; applies it to web/search results, RAG/project context, extracted document text, and non-official skill descriptions/results; and adds a high-priority prompt boundary that explicitly denies authority to those sources. Tool-result metadata uses the shared credential redactor; WebsiteReader retains only the source host; imported skill provenance drops query/user-info and does not persist local absolute paths. Community skill import/ping and custom-skill HTTP requests now use public-host DNS checks and refuse redirects. The terminal policy test suite now supplies the explicit test permit, ensuring the policy itself is exercised rather than only testing the missing-permit gate. Process cleanup now attempts to terminate descendants on runtimes with `ProcessHandle`, with a root-process fallback. Because this remains an application policy sandbox—not OS isolation—Release shell execution is explicitly blocked.

These controls reduce risk but do not prove model obedience or OS isolation. The current device and provider verification remains open.

## Findings and evidence

| Attack surface | Regression/evidence added | Result now | Remaining evidence / severity |
|---|---|---|---|
| Web, search, RAG, project context, document/OCR text, user-installed skill metadata/results | `UntrustedContentEnvelopeTest`; prompt policy in `PromptService`; envelopes in `WebSearchSkill`, `WebsiteReaderSkill`, `RagRetriever`, `DocumentProcessorAgent`, and `SkillToolBridge` | `CODE ADDED; JVM NOT RUN` | A model can still follow hostile text; evaluate each configured model/provider and keep authorization independent. **P1 residual** |
| Credential extraction from untrusted content | Envelope strips common OpenAI/Anthropic/Google/GitHub/Slack/AWS key shapes, Bearer/Basic credentials, JWTs, URL key parameters, and labeled token/secret/password assignments; regression fixture asserts values are absent | `CODE ADDED; JVM NOT RUN` | Pattern redaction is not a DLP proof; unknown formats and secrets split/obfuscated across text can evade it. **P1 residual** |
| Prompt-controlled skill endpoint import and ping | `CommunitySkillHub` calls `SearchSourcePolicy`, `HostAddressPolicy.PublicOnlyDns`, disables redirects, refuses 3xx, bounds streamed manifests to 1 MiB, and suppresses raw exception text | `CODE ADDED; JVM NOT RUN` | No live DNS rebinding or device network interception test. **P1 test pending** |
| Dynamic skill endpoint SSRF/redirect/credential query | `CustomSkillSecurity` rejects private/metadata hosts, URL user-info, and sensitive (including percent-encoded) query parameter names; `CustomSkillExecutor` uses public-only DNS, disables redirects, and treats 3xx as terminal. `sanitizeUrl` logs only the origin. | `CODE ADDED; JVM NOT RUN` | No live controlled endpoint/redirect server test. **P1 test pending** |
| IPv4/IPv6/metadata targets and mixed DNS answer | `HostAddressPolicyTest` simulates a mixed public/private DNS answer and covers obfuscated numeric, loopback, link-local, metadata hostnames; `SearchSourcePolicyTest` covers disallowed URL forms | `CODE ADDED; JVM NOT RUN` | Simulation does not equal real resolver/rebinding testing. **P1 test pending** |
| Shell metacharacters, path traversal, option escapes, approval/project prompt spoofing | Expanded `TerminalExecutionPolicyTest`; corrected `SandboxExecutorSecurityTest` to use `ShellExecutionPermit.forTest()` and exercise policy with a valid permit | `CODE ADDED; JVM NOT RUN` | CI execution required. Terminal commands remain allowlist-only in debug. **P1 test pending** |
| Process tree cleanup | `ProcessTreeTerminator` added to output-cap and cleanup paths; host-JVM test spawns a child and asserts both child and root stop | `CODE ADDED; JVM NOT RUN` | Android `ProcessHandle` support is not established; fallback kills only root. Requires device/process runtime test. **P1 unresolved** |
| FD inheritance, symlink/TOCTOU races, process sandboxing | No proof added for arbitrary inherited descriptors, race-free filesystem operations, or kernel isolation | `OPEN` | `SandboxExecutor` is **not** an OS sandbox. Release shell is blocked until a stronger boundary is implemented and tested. **P0 release gate** |
| API/secret disclosure through model context | Credential-like patterns in bounded untrusted content are masked; prompt and skill boundary asks not to disclose secrets | `PARTIAL` | No guarantee for arbitrary user secrets, unknown token formats, or model-generated disclosure. Enforce secret access separately and test all providers. **P1 residual** |

## Code and regression locations

- `app/src/main/java/com/airi/assistant/security/UntrustedContentEnvelope.kt`
- `app/src/test/java/com/airi/assistant/security/UntrustedContentEnvelopeTest.kt`
- `app/src/test/java/com/airi/assistant/ai/skills/SkillToolResultFormatterTest.kt`
- `app/src/test/java/com/airi/assistant/agent/browser/HostAddressPolicyTest.kt`
- `app/src/test/java/com/airi/assistant/tools/execution/SearchSourcePolicyTest.kt`
- `app/src/test/java/com/airi/assistant/domain/customskill/CustomSkillSecurityTest.kt`
- `app/src/test/java/com/airi/assistant/domain/terminal/TerminalExecutionPolicyTest.kt`
- `app/src/test/java/com/airi/assistant/agent/sandbox/SandboxExecutorSecurityTest.kt`
- `app/src/main/java/com/airi/assistant/domain/prompt/PromptService.kt`
- `app/src/main/java/com/airi/assistant/agent/subagent/impl/DocumentProcessorAgent.kt`
- `app/src/main/java/com/airi/assistant/memory/rag/RagRetriever.kt`
- `app/src/main/java/com/airi/assistant/ai/skills/SkillToolBridge.kt`
- `app/src/main/java/com/airi/assistant/ai/skills/SkillToolResultFormatter.kt`
- `app/src/main/java/com/airi/assistant/community/CommunitySkillHub.kt`
- `app/src/main/java/com/airi/assistant/domain/customskill/CustomSkillExecutor.kt`
- `app/src/main/java/com/airi/assistant/agent/browser/HostAddressPolicy.kt`
- `app/src/main/java/com/airi/assistant/agent/sandbox/SandboxIsolationPolicy.kt`
- `app/src/main/java/com/airi/assistant/agent/sandbox/ProcessTreeTerminator.kt`
- `app/src/test/java/com/airi/assistant/agent/sandbox/ProcessTreeTerminatorTest.kt`
- `app/src/test/java/com/airi/assistant/agent/sandbox/SandboxIsolationPolicyTest.kt`
- `app/src/test/java/com/airi/assistant/ai/skills/SkillToolResultFormatterTest.kt`
- `tools/security_scan.py`

## Execution record

- `git diff --check`: passed on the final static-check run.
- `python3 tools/security_scan.py`: passed on the final run, including new phase 3.4 source checks; no secret literals were reported. A first attempt correctly flagged the deliberate token fixture as a possible leaked key; the fixture was changed to assemble its synthetic token at runtime, then the scan passed.
- `python3 tools/verify_core_changes.py`: passed, `summary: 101/101 checks passed`.
- `python3 scripts/static_chat_runtime_audit.py`: passed, `PASS: 35 static contracts`.
- Targeted Gradle tests: **not executed**. Gradle configuration stopped because Android SDK is unavailable: `SDK location not found` (`ANDROID_HOME`/`local.properties` missing).
- Android device, live DNS, controlled redirect server, model/provider red-team, FD, Keystore, and Firebase evidence: **not executed**.

## Release gates and next required verification

1. Keep release terminal commands blocked by `SandboxIsolationPolicy` until a reviewed OS-level isolation boundary exists; do not change `OS_SANDBOX_AVAILABLE` to true based on application-level argv tests.
2. Run the targeted JVM tests in Android-SDK-equipped CI; fix any compile/test failures before treating the controls as test-verified.
3. Run controlled DNS/redirect tests against localhost, IPv4/IPv6 loopback, link-local/metadata endpoints, mixed DNS answers, and public-to-private redirects.
4. Execute prompt-injection scenarios against every supported model path using attachment, web/Jina, RAG, and user-installed skill payloads, with attempts to disclose keys, switch projects, bypass approval, and launch terminal actions.
5. Separately prove FD/process/file containment on supported Android devices or maintain the release block. Record evidence without secrets or raw prompt/credential payloads.

## Closure decision

**Phase 3.4 is not closed.** Code-level defenses and regression fixtures are added, but targeted tests could not be built in this environment and multiple adversarial runtime checks remain open. No Firebase deployment or 3.1–3.3 production evidence is asserted by this report.
