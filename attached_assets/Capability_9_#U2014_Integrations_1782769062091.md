## Capability 9 — Integrations
**Status:** Mostly Complete (Legacy Debt)
**Existing Implementation:**
*   `McpConnector` ( handshaking, invoke, teardown).
*   `RemoteLlmConnector` (OpenAI, Anthropic, Gemini).
*   `GithubService`, `TelegramService` (OkHttp REST clients).
*   `ConnectorHealthMonitor` (Live StateFlow).

**Missing Components:**
*   **Critical:** Notion/n8n/Zapier are stubs (non-functional); Legacy `integration` package uses plaintext SharedPreferences.
*   **High:** No pagination for GitHub repos; Three parallel integration code paths (split-brain).

**Architecture Notes:**
Consolidate all integrations under the `connector` architecture. Deprecate the legacy `integration` package. Use `SecureStorage` for all PATs/Tokens.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | Split-brain State | Divergent storage paths for tokens (Plaintext vs Secure). |
| High | Functional Stubs | UI allows configuration of non-functional services (Notion/n8n). |

**Recommendation:**
Delete legacy `integration` package. Implement Notion API via `McpConnector`. Migrate all tokens to `SecureStorage`.

---

## Capability 10 — Monitoring
**Status:** Mostly Complete
**Existing Implementation:**
*   `PrivacyTelemetryReporter` (Consent-gated, sanitized).
*   `AgentActivityBus` (Process-scoped SharedFlow).
*   `AgentTraceManager` (Nested span support).
*   `ExecutionStatusBus` (Global state broadcast).

**Missing Components:**
*   **Critical:** No feedback loop (Monitoring signals don't trigger adaptive behavior).
*   **High:** `AIRI_PROOF` audit trail is logcat-only (lost on exit).
*   **Medium:** FrameTiming/Thermal monitors have no UI surface.

**Architecture Notes:**
Architecture is excellent but reactive only. Connect `ThermalProfiler` to `HybridOrchestrator` to reduce context window under heat.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | Ephemeral Audit | Forensic analysis impossible without ADB connection. |

**Recommendation:**
Persist `AIRI_PROOF` logs to a Room table. Implement adaptive throttling based on monitoring signals.

---

## Capability 11 — Preview
**Status:** Absent (Scaffold Only)
**Existing Implementation:**
*   `PreviewManager` (Empty class).
*   `PreviewType` (HTML, MARKDOWN, IMAGE, CODE).

**Missing Components:**
*   **Critical:** No rendering engine for artifacts (HTML/Markdown).
*   **High:** No WebView sandbox for HTML previews.

**Architecture Notes:**
Preview should be a separate `ui.preview` module. Use a restricted `WebView` for HTML/JS execution to prevent cross-origin leaks.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | WebView XSS | Unsanitized artifact preview could steal app tokens. |

**Recommendation:**
Implement `MarkdownView` (native) and `HtmlView` (isolated WebView). Use `Content-Security-Policy` for all previews.

---

## Capability 12 — Secrets Management
**Status:** Mostly Complete
**Existing Implementation:**
*   `SecureStorage` (EncryptedSharedPreferences).
*   `SecureApiKeyStore` (Typed overlay).
*   `BiometricGatekeeper` (Hardware-backed).

**Missing Components:**
*   **Critical:** InMemory fallback drops secrets silently on process death.
*   **High:** No secret rotation or TTL mechanism.

**Architecture Notes:**
Relying on `EncryptedSharedPreferences` is correct for Android. Ensure `MasterKey` is hardware-backed (TEE/StrongBox).

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | Silent Data Loss | InMemory fallback creates "ghost" login states. |

**Recommendation:**
Remove InMemory fallback; force re-auth if encryption fails. Add "Secret Health" check to startup.

---

## Capability 13 — Security Center
**Status:** Mostly Complete (Critical Bug)
**Existing Implementation:**
*   `ExecutionFirewall` (4-layer model: Global, Agent, Tool, Runtime).
*   `PermissionGovernanceLayer` (Policy evaluation).
*   `AccessibilityScopePolicy` (UI restriction).

**Missing Components:**
*   **CRITICAL:** `getOrElse { true }` fail-open bug in `firewall.allows()`.
*   **High:** No rate limiting on governance evaluation.
*   **Medium:** No certificate pinning for LLM APIs.

**Architecture Notes:**
The architecture is sophisticated but undermined by a single-character fail-open error.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| CRITICAL | Fail-Open Bug | Complete bypass of the security model on any exception. |

**Recommendation:**
**P0: Change `getOrElse { true }` to `getOrElse { false }`.** Implement rate limiting for `evaluate()`. Enable certificate pinning for OkHttp.

---

## Capability 14 — CLI
**Status:** Absent
**Existing Implementation:**
*   `TerminalRuntime` (In-app shell proxy).
*   `SandboxExecutor` (Backend command runner).

**Missing Components:**
*   **Critical:** No external ADB/HTTP/MCP bridge for desktop access.
*   **High:** Dead stubs for `PYTHON_SCRIPT` and `KOTLIN_SCRIPT`.

**Architecture Notes:**
Preferred path: **MCP Server**. Reuses existing MCP logic and avoids custom IPC protocols.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Critical | Remote Access | Remote command execution + Accessibility = Full Device Control. |

**Recommendation:**
Defer CLI to Phase 4. Implement as an MCP Server over HTTP SSE. Remove dead scripting stubs.

---

## Capability 15 — User Settings
**Status:** Mostly Complete (Fragmentation Issue)
**Existing Implementation:**
*   `SettingsScreen` (14+ sub-screens).
*   `UserProfileRepository` (Firestore sync).
*   `LanguageManager` (Runtime locale switching).

**Missing Components:**
*   **Critical:** 5 independent preference stores with no unified facade.
*   **High:** No "Reset to Defaults" that covers all stores.

**Architecture Notes:**
Consolidate `ExecMode`, `Voice`, `Theme`, and `User` preferences into a single `PreferenceCoordinator`.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Medium | Inconsistency | Split-brain state across 5 different storage APIs. |

**Recommendation:**
Implement `PreferenceCoordinator` to unify the 5 backing stores. Add a global reset function.

---

## Capability 16 — VNC / Remote Desktop
**Status:** Absent
**Existing Implementation:**
*   `RemoteDesktopManager` (Empty scaffold).
*   `VncProtocolHandler` (Stub).

**Missing Components:**
*   **Critical:** No screen capture (MediaProjection) or input injection implementation.

**Architecture Notes:**
Requires `MediaProjection` API for capture and `AccessibilityService` for input injection.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Critical | Privacy | Continuous screen capture is a high-risk privacy event. |

**Recommendation:**
Defer to Phase 4. Require explicit user consent per session. Implement via `MediaProjection`.
