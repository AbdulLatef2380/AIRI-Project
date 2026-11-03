# Report 3 remediation — batch 1

Status: written without a Kotlin compiler or network. **Build and run unit tests before merging**:
`./gradlew :app:testDebugUnitTest`

| Step | Item | Change |
|---|---|---|
| 1 | S5 | `SearchTool`: no automatic redirects; every hop re-checked by `SearchSourcePolicy`; `PublicOnlyDns` |
| 2 | S6 | New `HostAddressPolicy` (numeric IPv4, IPv6 ranges, reserved blocks); used by `BrowserNavigationPolicy`, `SkillRegistry` |
| 3 | S8 | External tool output wrapped in `<untrusted_tool_output>` (`AccessibilityPolicyGuard`, `AgentLoop`) |
| 4 | S8 | `terminal_execute` / `write_file` refused after external content was read in the same request. `calendar_create` already needs Trust Center approval (verified) |
| 5 | S7 | Jina Reader opt-in (default OFF, `ExternalReaderPreferences.DEFAULT_JINA_ENABLED`), disclosure text in 4 languages |
| 6 | M1 | `MarketplaceInstallPolicy`: HTTPS + public host, size cap, redirect checks |
| 7 | M1 | Install runs `SkillSecurityScanner` + `TrustScoringEngine`; confirmation dialog shows endpoint host and trust |
| 8 | T1 | `TerminalExecutionPolicy`: per-tool option allowlist (find -fprint/-exec, sed e/w/-i, awk -f, sort -o, git mutation blocked) |
| 9 | S3 | `firestore.rules`: `task_continuity` limited to known fields, types and sizes |
| 10 | Tests/docs | New tests: HostAddressPolicy, BrowserNavigationPolicyHardening, MarketplaceInstallPolicy, UntrustedToolOutput, Terminal T1 cases |

## Behaviour changes to be aware of
- Jina Reader is off until the user enables it; `fetch_url` falls back to direct fetch.
- `sed` accepts only line-addressed scripts (`5p`, `2,9d`, `3q`); `awk` only with `-F`.
- Updating a marketplace skill also asks for endpoint confirmation.

## Batch 2 (remaining items)
| Item | Result |
|---|---|
| S1 | New `functions/` (Cloud Functions v2): `createRemoteSession` / `revokeRemoteSession`, the trusted writer the rules require. Checks both devices are PAIRED and owned by the caller, TTL <= 10 min, App Check enforced. **Not deployed**; deploy with `firebase deploy --only functions` after review. The Android/desktop clients do not call it yet. |
| S2 | **Verified, not changed.** Phone-control tools reach `AndroidAgent` only through `VoiceAgentRouter` (voice) and the orchestrator path; the chat `AgentLoop` never routes to it. Merging the two agent paths is an architectural change that needs a build and device tests. |
| S4 / D1 | `DesktopRemoteControlTarget.confirmRemoteTextRequest(text)` added, **default deny**. `PairedDesktopControl` refuses `SUBMIT_TEXT_REQUEST` unless the desktop target approves it locally. Tests added. The real desktop implementation is still not in this repository: it must implement the approval prompt. |

## Behaviour change
- Remote text requests to the desktop are rejected until a target implements `confirmRemoteTextRequest`.

## Still open (outside this repository)
- Deploying the functions and wiring clients to call them (S1).
- The external desktop implementation of `DesktopRemoteControlTarget` (D1).
- S2 agent-path unification (design decision).
