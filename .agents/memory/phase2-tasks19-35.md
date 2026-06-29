---
name: Phase 2 Tasks 19–35 Completion
description: Key API facts, wiring decisions, and gotchas from implementing Phase 2 T19–T35.
---

## Critical API Facts

- **AuditLogEntity** — fields are `id`, `tag`, `message`, `level` (enum), `timestampMs`. There is NO `subsystem` field.
- **AuditRepository** — method is `getRecent(limit: Int = 200)`, NOT `getRecentEvents()`.
- **LlmCertPins** pins (`LlmCertPins.kt`) are **placeholder SHA-256 values**. Must be replaced with real SPKI hashes before production release. Incorrect pins block ALL requests to the pinned host.
- **OpenAiProvider.defaultHttpClient()** is the single factory for all three LLM providers — AnthropicProvider and GeminiProvider both call it. Updating it propagates to all three.

## Wiring Decisions

- **ArtifactManager** receives `ArtifactDao` via `ServiceLocator` by calling `AiriDatabase.getDatabase(ctx).artifactDao()` in the lazy initializer.
- **TerminalRuntime** gets context via `ServiceLocator.requireContext()` (new optional constructor param). History persisted to `SharedPreferences("airi_terminal_history")`, max 50 entries.
- **DeveloperCenterScreen** tabs: Runtime / Connectors / Memory / Diagnostics / Health / Audit (index 5). Adding tabs requires updating both the `tabs` list AND the `when(selectedTab)` block.

## Legacy Integration Status

All four legacy integration classes are `@Deprecated(DeprecationLevel.WARNING)`:
- `GithubIntegration`, `TelegramIntegration`, `NotionIntegration`, `IntegrationManager`
- ConnectorBootstrap retains `TelegramIntegration` fallback for no-SecureStorage devices (intentional, documented).
- Phase 3 target: delete the entire `com.airi.assistant.integration` package.

**Why:** Callers still exist on the fallback path; hard-deleting now would break the build.

## Engineering Report

Written to `docs/engineering_report_phase2_tasks19_35.md`.
