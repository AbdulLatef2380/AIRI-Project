## Dependency Trees (Concise)

**Core Infrastructure**
Storage ├── Room (SQLite) ├── SecureStorage (AES-GCM) └── ArtifactManager (FS)
Auth ├── Firebase ├── Biometric └── DeviceBinding
Security ├── ExecutionFirewall ├── GovernanceLayer └── AccessibilityPolicy

**Cognitive Engine**
Agent ├── SkillEngine ├── MemoryManager └── HybridOrchestrator
AI Runtime ├── llama.cpp (JNI) ├── RemoteConnectors (OpenAI/Anthropic/Gemini) └── McpConnector

---

## Feature Readiness Matrix (Compact)

| Capability | Status | Score | Blocker |
| :--- | :--- | :--- | :--- |
| Storage | Mostly Complete | 8.5 | Plaintext ExecMode |
| Auth | Partial | 6.0 | Interface Bypass |
| Security | Mostly Complete | 9.0 | **Fail-Open Bug (P0)** |
| AI Engine | Complete | 9.5 | None |
| Voice | Mostly Complete | 8.0 | Wake-word stability |
| CLI / VNC | Absent | 0.0 | Not started |

---

## Implementation Order (50-Step Compressed)

| Step | Task | Priority | Depends On |
| :--- | :--- | :--- | :--- |
| 1 | **Fix P0 Fail-Open Bug** (Security Center) | **CRITICAL** | None |
| 2 | Migrate ExecMode to SecureStorage | **HIGH** | Storage |
| 3 | Enforce AuthService in UI (Remove direct Firebase calls) | **HIGH** | Auth |
| 4 | Implement GDPR Account Deletion | **HIGH** | Auth |
| 5 | Persist `AIRI_PROOF` to Room | **MEDIUM** | Monitoring |
| 6 | Unify 5 Preference Stores into `PreferenceCoordinator` | **MEDIUM** | Settings |
| 7 | Implement isolated WebView for Artifact Preview | **MEDIUM** | Preview |
| 8 | Add Notion API via McpConnector | **MEDIUM** | Integrations |
| 9 | Add Thermal/Memory adaptive throttling | **LOW** | Dev Tools |
| 10 | Implement MCP Server for Desktop CLI access | **LOW** | CLI |

---

## Roadmap (Phases 1–4)

**Phase 1: Stabilization (Weeks 1-2)**
*   Fix P0 Security Bug.
*   Resolve Auth abstraction bypass.
*   Secure all plaintext preferences.
*   GDPR compliance (Delete account).

**Phase 2: Reliability (Weeks 3-4)**
*   Unify preference architecture.
*   Persist audit logs and traces.
*   Implement secure Artifact Preview.
*   Enable SQLCipher for DB encryption.

**Phase 3: Expansion (Weeks 5-8)**
*   Functional Notion/n8n connectors.
*   Local Git binary in sandbox.
*   Adaptive system health throttling.
*   Marketplace integration for skills.

**Phase 4: Remote (Weeks 9-12)**
*   MCP Server for desktop CLI.
*   VNC/Remote Desktop (Optional/Experimental).
*   Multi-device memory sync.

---

## Overall Readiness & Top 10 Priorities

**Overall Readiness:** 72% (Production-ready core, weak surface/integration layer)

**Top 10 Priorities:**
1.  **FIX P0 FAIL-OPEN BUG** (Security)
2.  Secure `ExecModePreferences` (Privacy)
3.  Enforce `AuthService` abstraction (Architecture)
4.  Implement GDPR deletion flow (Legal)
5.  Persist Audit Logs (Security)
6.  Unify 5 Preference Stores (Maintainability)
7.  Add Notion API client (Feature)
8.  Implement isolated Artifact Preview (UX)
9.  Enable SQLCipher (Security)
10. Remove dead scripting stubs (Cleanup)

**Estimated Completion Order:** Security -> Auth -> Storage -> Settings -> Preview -> Integrations -> CLI.
