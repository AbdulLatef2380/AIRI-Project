---
name: Phase 2 Tasks 3–9 Architecture Decisions
description: Architectural decisions, API surfaces, and critical constraints for Phase 2 Tasks 3–9 of the AIRI engineering blueprint.
---

## Task 3 — AuthService enforcement in UI

**Rule:** All `FirebaseAuth.getInstance()` calls in UI composables are eliminated. Use `ServiceLocator.authService.currentUser()` for read-only user state. Only `LoginScreen` receives `authService: AuthService` as an explicit parameter (it needs `signInWithGoogleCredential` and `signInWithGitHub`).

**Why:** Reduces vendor lock-in at the UI layer; a single swap of `AuthService` now re-routes all auth without touching UI code. `SessionManager.kt` and `CloudSyncCoordinator.kt` still use `FirebaseAuth.getInstance()` directly — those are infrastructure/domain layer, not UI, and are out of Task 3's scope.

**How to apply:** When adding new screens that display user info, always go through `ServiceLocator.authService.currentUser()`. Never call `FirebaseAuth.getInstance()` in a `@Composable` or `Activity`.

## Task 4 — GDPR Account Deletion

**Rule:** `deleteAccount()` on `AuthService` forces a token refresh (`getIdToken(forceRefresh=true)`) before calling `FirebaseAuth.delete()`, then calls server-side revocation, then `signOut()` locally. Callers must surface error messages to the user via snackbar.

**Why:** Re-authentication failures (expired session) are the #1 cause of silent deletion failures. The token refresh maximizes session validity. Server-side revocation + local signOut are belt-and-suspenders.

**How to apply:** `PrivacyDataSettingsScreen` calls `authService.deleteAccount { success, errorMsg -> ... }`. The error branch shows a snackbar with `R.string.delete_account_error_generic` and the raw error message.

## Task 5 — Persistent AIRI_PROOF (AuditLog)

**Rule:** `AuditRepository.log(tag, message, level)` is the single write point. It both writes to Room and emits to logcat. The Room column `level` is stored as TEXT (enum name) via `AuditLogTypeConverters` registered on `AiriDatabase`.

**Critical:** `AuditLogTypeConverters` MUST be registered via `@TypeConverters(AuditLogTypeConverters::class)` on `AiriDatabase`. Without it, Room cannot map the `Level` enum and will throw a runtime exception.

**DB version:** `AiriDatabase` is now v4. `MIGRATION_3_4` adds the `audit_log` table with indices on `timestampMs` and `tag`.

**How to apply:** `ServiceLocator.auditRepository.info("MODULE", "message")` anywhere in the codebase. Retention auto-purges entries older than 30 days every 500 writes.

## Task 6 — PreferenceCoordinator

**Rule:** `PreferenceCoordinator` is a facade over `ExecModePreferences`, `VoicePreferencesStore`, and theme SharedPreferences. `resetAllToDefaults()` covers all three. `UserProfileRepository` is deliberately excluded (would require Firestore deletes).

**How to apply:** `ServiceLocator.preferenceCoordinator.resetAllToDefaults()` in Settings. Individual store reads/writes go through the coordinator's typed properties.

## Task 7 — ArtifactPreviewScreen

**Route:** `AiriRoute.ARTIFACT_PREVIEW` with path `screen_artifact_preview/{type}/{content}`. Content is URL-encoded and truncated to 8192 chars (char-based, not byte-based — minor doc mismatch, non-blocking).

**Security:** WebView has JS=off, file access=off, content access=off, all URL navigation blocked, CSP meta tag injected. Markdown and Code use native Compose (no execution risk).

**How to navigate:** `navController.navigate(AiriRoute.artifactPreview(type, content))`

## Task 8 — NotionMcpConnector

**Rule:** PAT stored in `SecureStorage` under key `integration_token_notion` via the new generic `saveIntegrationToken`/`getIntegrationToken` API. PAT is retrieved per-call — never cached in memory.

**Tools:** `search_pages`, `get_page`, `get_page_blocks`, `create_page`, `query_database`. All use OkHttp against `api.notion.com/v1` with `Notion-Version: 2022-06-28`.

**Fallback:** If `SecureStorage` is unavailable (broken Keystore), `ConnectorBootstrap` falls back to the legacy `NotionIntegration` stub adapter. In practice this should never happen.

**How to connect:** Call `ServiceLocator.secureStorage.saveNotionToken(pat)` from the Integrations settings screen.

## Task 9 — SystemHealthCoordinator

**Rule:** `ThermalProfiler` is auto-started on first access via `ServiceLocator.thermalProfiler`. `SystemHealthCoordinator` is auto-started on first access via `ServiceLocator.systemHealthCoordinator`. Both lazy chains are triggered in `MainActivity.onCreate` via `runCatching { ServiceLocator.systemHealthCoordinator }`.

**API:** `coordinator.contextBudgetFactor` returns `1.0f` (NONE), `0.5f` (REDUCE), `0.0f` (EMERGENCY). `coordinator.isEmergency` for fast gate-checks before starting inference.

**Why:** ThermalProfiler was collecting signals but they were never acted upon. This coordinator closes the feedback loop. The `onThrottleChange` callback in ServiceLocator currently logs to `auditRepository` only — a future task should connect it to `PromptBudgetLedger.setContextFactor(factor)`.

**Known gap:** `onThrottleChange` logs but does not actually reduce the context budget against `HybridOrchestrator` or `PromptBudgetLedger`. This is a known incomplete wire from Task 9 — requires `PromptBudgetLedger` API extension in a follow-up task.
