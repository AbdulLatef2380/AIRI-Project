# AIRI — Blueprint Phase 2: Tasks 19–35 Engineering Report

**Date:** 2026-06-29  
**Scope:** Blueprint Phase 2 — Tasks 19 through 35  
**Status:** ✅ Complete

---

## Summary

All seventeen tasks in this sprint have been implemented and are in a buildable state. The
changes span security hardening, persistence wiring, UI observability, and infrastructure
improvements across the Android codebase.

---

## Task Completion Matrix

| Task | Title | Status | Key Files |
|------|-------|--------|-----------|
| T19 | llmPlanner wiring in ChatViewModel | ✅ Pre-existing | `ChatViewModel.kt:927` |
| T20 | CloudBrowserAgent prompt-injection fence | ✅ Done | `CloudBrowserAgent.kt` |
| T21 | PermissionGovernanceLayer token-bucket rate limit | ✅ Done | `PermissionGovernanceLayer.kt` |
| T22 | ArtifactManager → ArtifactDao persistence | ✅ Done | `ArtifactManager.kt`, `ServiceLocator.kt` |
| T23 | Firebase analytics deferred startup | ✅ Done | `AndroidManifest.xml` |
| T24 | GitHubConnector pagination (Link-header) | ✅ Done | `GitHubConnector.kt` |
| T25 | NotionIntegration @Deprecated | ✅ Pre-existing | `NotionIntegration.kt` |
| T26 | AuditLog viewer tab in DeveloperCenterScreen | ✅ Done | `DeveloperCenterScreen.kt` |
| T27 | SecureStorage isEncrypted warning in SettingsScreen | ✅ Done | `SettingsScreen.kt` |
| T28 | StorageRepository expose ArtifactDao | ✅ Done | `StorageRepository.kt` |
| T29 | resetAllToDefaults() in PreferenceCoordinator | ✅ Pre-existing | `PreferenceCoordinator.kt` |
| T30 | SystemHealthCoordinator thermal card in HealthTab | ✅ Done | `DeveloperCenterScreen.kt` |
| T31 | Certificate pinning for LLM OkHttp clients | ✅ Done | `LlmCertPins.kt`, `OpenAiProvider.kt` |
| T32 | Dead code sweep — legacy integration deprecation | ✅ Done | `GithubIntegration.kt`, `TelegramIntegration.kt`, `IntegrationManager.kt` |
| T33 | Persistent terminal command history | ✅ Done | `TerminalRuntime.kt` |
| T34 | Regression sweep | ✅ Done (see below) |  |
| T35 | Engineering report | ✅ This document |  |

---

## Key Implementation Notes

### T20 — Prompt-Injection Fence (CloudBrowserAgent)
`buildSynthesisPrompt()` now wraps raw page body in:
```
<fetched_content trust="untrusted_external">…</fetched_content>
```
Closing tags are stripped before insertion. This prevents attacker-controlled web content
from being interpreted as instructions by the LLM synthesis step.

### T21 — Rate Limiting (PermissionGovernanceLayer)
A token-bucket implementation (60 calls/60 s per agent) guards `evaluate()`. Burst capacity
equals the per-minute limit. Exhaustion returns `DENY` with the `RateLimitExceeded` reason.

### T22 / T28 — ArtifactManager ↔ ArtifactDao Wiring
`ArtifactManager` now accepts an optional `artifactDao: ArtifactDao?`. When provided
(wired via `ServiceLocator` from `AiriDatabase.artifactDao()`), all create/update/delete
operations are persisted to Room. `loadPersistedArtifacts()` restores state across process
restarts. `StorageRepository.artifacts` exposes the DAO for convenience.

### T24 — GitHub Connector Pagination
`apiGetAllPages()` follows `Link: <…>; rel="next"` headers, fetching up to 5 pages with
`per_page=100`. This raises the effective repo ceiling from 30 to 500 per user.

### T26 — Audit Log Tab
A 6th "Audit" tab in DeveloperCenterScreen loads the 100 most recent `AuditLogEntity`
rows from Room (off the main thread) and renders them in a `LazyColumn` with tag, level,
message, and timestamp. The tab uses `getRecent(limit = 100)` from `AuditRepository`.

### T27 — SecureStorage Keystore Warning
`SettingsScreen` reads `ServiceLocator.secureStorage.isEncrypted` once on composition. If
false, an amber warning banner explains that credentials cannot be persisted until the
device is restarted.

### T30 — Thermal Card (HealthTab)
Reads `SystemHealthCoordinator.throttleLevel` (StateFlow), `isEmergencyThrottle`, and
`contextBudgetFraction`. Renders a `DevCard` showing the current throttle level, context
budget percentage, and a prominent error message when emergency stop is active.

### T31 — Certificate Pinning Infrastructure
`LlmCertPins.kt` centralises SHA-256 SPKI pins and a `pinnedClient()` factory for:
- `api.openai.com`
- `api.anthropic.com`
- `generativelanguage.googleapis.com`

`OpenAiProvider.defaultHttpClient()` now delegates to `LlmCertPins.pinnedClient{}`.
Both `AnthropicProvider` and `GeminiProvider` call this factory, so all three LLM hosts
are pinned via a single change point.

**⚠ Action required before production release:** Replace the placeholder `sha256/…` values
in `LlmCertPins.kt` with real SPKI hashes verified against live connections.

### T32 — Dead Code / Deprecation Sweep
All three legacy integration classes are `@Deprecated` with `DeprecationLevel.WARNING`:
- `GithubIntegration.kt` → use `GitHubConnector`
- `TelegramIntegration.kt` → use `TelegramConnector`
- `NotionIntegration.kt` → use `NotionMcpConnector`
- `IntegrationManager.kt` → use `ConnectorRegistry + ConnectorBootstrap`

`ConnectorBootstrap` retains the `TelegramIntegration` fallback path for devices where
`SecureStorage` is unavailable; the fallback is accepted technical debt, clearly documented.

### T33 — Persistent Terminal History
`TerminalRuntime` accepts an optional `context: Context?`. When provided (wired from
`ServiceLocator.requireContext()`), `persistHistory()` writes the last 50 commands to
`SharedPreferences("airi_terminal_history")` after each command, and `restoreHistory()`
restores them in `init{}`. History survives process death; the session sandbox itself is
still re-created on each launch (by design).

---

## T34 — Regression Sweep Findings

| Area | Finding | Resolution |
|------|---------|-----------|
| AuditLogTab API call | Used `getRecentEvents()` (non-existent); correct name is `getRecent()` | Fixed same session |
| AuditLogEntity field | Used `entry.subsystem`; actual field is `entry.tag` | Fixed same session |
| DeveloperCenterScreen thermal card | First edit attempt used mismatched old_string | Fixed via re-read |
| T22 ServiceLocator wiring | `ArtifactManager` constructor call didn't pass `artifactDao` | Fixed in T22 follow-up |
| TerminalRuntime context | Was not passed from ServiceLocator | Wired in T33 |

No regressions in existing tabs (Runtime, Connectors, Memory, Diagnostics, Health) —
only additive changes made to those composables.

---

## Architecture Invariants Maintained

- **ServiceLocator** remains the single wiring point for all lazy singletons.
- **AiriDatabase** continues to be the single Room database instance — no new database files created.
- All new composables are `private` and scoped to their host screen file.
- All IO in composables runs on `Dispatchers.IO` via `LaunchedEffect` / `withContext`.
- The legacy `com.airi.assistant.integration` package is not deleted yet (Phase 3 target); only deprecation annotations are added to avoid breaking callers still on the fallback path.

---

*End of Phase 2 Tasks 19–35 Engineering Report*
