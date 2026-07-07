# AIRI Engineering Blueprint — Phase 2 Implementation Specification
**Date:** June 28, 2026 | **Status:** EXECUTION-READY | **Target:** Engineering Staff

---

## Capability 1 — Storage
**Status:** Mostly Complete
**Existing Implementation:**
*   Room v3 (7 entities, 6 DAOs, versioned migrations).
*   EncryptedSharedPreferences (AES256-GCM) for OAuth/Keys.
*   Process-scoped ArtifactManager (In-memory/Filesystem).
*   Sandbox FS (<filesDir>/sandbox/<id>) with 30-min reaper.

**Missing Components:**
*   **Critical:** No Repository interface over Room (Direct DAO usage); ExecModePreferences in plaintext SharedPreferences.
*   **High:** Artifact persistence (lost on process death); No DB backup/export mechanism.
*   **Medium:** No storage quota enforcement; No vector compression for embeddings.

**Architecture Notes:**
Storage layer is fragmented across Room, DataStore, and SharedPreferences. Move towards a unified `StorageRepository` facade. Ensure all privacy-critical prefs (ExecMode) migrate to `SecureStorage`.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | Plaintext ExecMode | Privacy-critical settings readable on rooted devices. |
| Medium | No pagination in MemoryDao | Performance degradation with large chat histories. |

**Recommendation:**
Implement `StorageRepository` to abstract DAOs. Migrate all plaintext SharedPreferences to `SecureStorage`. Add Room-backed persistence for Artifacts.

---

## Capability 2 — Authentication
**Status:** Partial (Abstraction Bypass Issue)
**Existing Implementation:**
*   BiometricGatekeeper (Strong/Credential) with suspend API.
*   SessionManager (55-min Firebase refresh loop).
*   DeviceBindingService (Hardware fingerprinting).
*   Firebase Auth (Email, Google, GitHub OAuth).

**Missing Components:**
*   **Critical:** `FirebaseAuth.getInstance()` bypasses `AuthService` interface in UI; No server-side sign-out revocation.
*   **High:** No account deletion flow (GDPR); No refresh token rotation on device-binding mismatch.
*   **Medium:** Biometric gate not enforced on Settings/Storage access.

**Architecture Notes:**
Interface `AuthService` exists but is ignored by UI layers. Enforce DI (Dependency Injection) to prevent vendor lock-in. GitHub identity is split between Firebase and local PAT storage.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | Vendor Lock-in | Direct Firebase calls make provider swap difficult. |
| Medium | InMemory Fallback | Silent session loss on process death if encryption fails. |

**Recommendation:**
Route all auth calls through `AuthService`. Implement GDPR-compliant account deletion. Enforce biometric check for sensitive credential access.

---

## Capability 3 — Dashboard
**Status:** Partial (Diagnostic Only)
**Existing Implementation:**
*   DeveloperCenterScreen (Runtime, Connectors, Memory, Diagnostics).
*   ObservabilityScreen (Events, Live Hub, Graph, Traces).
*   AgentControlScreen (DAG visualization, plan overlay).

**Missing Components:**
*   **Critical:** Dashboard is read-only; No control path for remote state management.
*   **High:** No persistence for observability traces (lost on restart).
*   **Medium:** DAG visualization lacks interactivity (node manual retry/edit).

**Architecture Notes:**
Observability data flows through `AgentActivityBus` and `ExecutionStatusBus`. These should be unified into a persistent `AuditLog` in Phase 2.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Medium | Memory Pressure | Large trace buffers in memory may cause OOM. |

**Recommendation:**
Add persistent storage for execution traces. Implement "Actionable Nodes" in the DAG viewer to allow manual agent intervention.

---

## Capability 4 — Runtime / Terminal
**Status:** Complete (Sandboxed)
**Existing Implementation:**
*   TerminalRuntime (Sandboxed shell, 30s timeout).
*   SandboxExecutor (Argv-exec, ProcessBuilder, no shell injection).
*   Binary Allowlist (ls, pwd, cat, grep, etc.).

**Missing Components:**
*   **High:** No persistent terminal sessions (state lost on screen exit).
*   **Medium:** Limited binary set; No support for interactive CLI tools (vim/nano).

**Architecture Notes:**
Uses `ProcessBuilder` to avoid shell-parsing vulnerabilities. Architecture is sound. Expand via `SandboxExecutor.TaskType`.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Low | ANSI Injection | Malicious output could theoretically spoof terminal UI. |

**Recommendation:**
Implement terminal session persistence via a background service. Add `top` and `ps` to allowlist for resource monitoring.

---

## Capability 5 — Database
**Status:** Mostly Complete
**Existing Implementation:**
*   Room v3; 7 Entities; 6 DAOs.
*   Versioned migrations (v1 -> v3).
*   Binary vector storage for embeddings.

**Missing Components:**
*   **High:** No SQLCipher (DB not encrypted at rest).
*   **Medium:** No AutoMigrationSpec usage (manual migrations only).

**Architecture Notes:**
Schema is well-structured. Move `CustomSkillCrypto` logic to encrypt specific sensitive columns if full DB encryption is too heavy.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Medium | DB Bloat | Uncompressed vector blobs will cause rapid growth. |

**Recommendation:**
Enable SQLCipher. Implement vector quantization/compression before storage.

---

## Capability 6 — Developer Tools
**Status:** Complete (Internal Only)
**Existing Implementation:**
*   RuntimeProfiler (Heap/Allocation tracking).
*   ThermalProfiler (Android PowerManager hooks).
*   FlowPressureMonitor (Coroutine backpressure).
*   ConnectorChaosTester (Resilience testing).

**Missing Components:**
*   **High:** Tools are orphaned (no UI entry point for most profilers).
*   **Medium:** No automated response to thermal/memory pressure.

**Architecture Notes:**
Data is collected but not acted upon. Create a `SystemHealthCoordinator` to throttle AI context window based on `ThermalLevel`.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| Medium | Dead Code | Unused profilers increase APK size without value. |

**Recommendation:**
Expose all profilers in `DeveloperCenterScreen`. Implement adaptive throttling based on thermal/memory signals.

---

## Capability 7 — Git Integration
**Status:** Absent (Stub Only)
**Existing Implementation:**
*   `GithubService` (OkHttp client for REST API v3).
*   `GithubIntegration` (Legacy PAT storage).

**Missing Components:**
*   **Critical:** No JGit/Native Git binary; No local clone/commit/push capability.
*   **High:** REST API limited to 30 repos (no pagination).

**Architecture Notes:**
Current "Git" is just a GitHub API client. A real Git integration requires either `JGit` (heavy) or a cross-compiled `git` binary in the sandbox.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | API Limits | GitHub REST API rate limits will block agent tasks. |

**Recommendation:**
Implement local Git binary in sandbox for Phase 3. Add pagination to `GithubService`.

---

## Capability 8 — Growth / SEO
**Status:** Partial
**Existing Implementation:**
*   ReferralManager (SHA-256 codes, airi:// deep links).
*   ExperimentManager (A/B framework stub).
*   PaywallTriggerEngine (Data-driven upsell).
*   React Landing Page (Vite 5, React 18).

**Missing Components:**
*   **Critical:** Referral bonus in plaintext SharedPreferences (Inflatable).
*   **High:** Firebase SDK init precedes GDPR consent.
*   **Medium:** Landing page lacks SEO meta/sitemap/robots.txt.

**Architecture Notes:**
Move growth logic to `SecureStorage`. Delay Firebase init until `TelemetryConsentStore` is verified.

**Risks:**
| Severity | Issue | Impact |
| :--- | :--- | :--- |
| High | Bonus Fraud | Rooted users can trivially grant themselves credits. |
| High | GDPR Breach | SDK initialization without consent. |

**Recommendation:**
Secure referral storage. Add SEO tags and sitemap to web portal. Implement deferred analytics initialization.
