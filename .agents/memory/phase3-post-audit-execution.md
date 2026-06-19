---
name: Phase 3 Post-Audit Execution
description: All confirmed gaps implemented in Phase 3. Files created/edited, execution order, critical fixes.
---

## Critical Fix (Phase A+B) — Custom/marketplace skills now executable in agent loop

**Problem**: `SkillRegistry.getAvailableSkills()` never included custom skills from
`CustomSkillRepository`. They were advertised in the system prompt but could never be
called by the agent.

**Fix**:
1. Created `CustomSkillAiriSkillAdapter.kt` — wraps `CustomSkill` + `CustomSkillExecutor`
   as an `AiriSkill` so `SkillToolBridge` can invoke it.
2. Modified `SkillRegistry.getAvailableSkills()` to append custom skills via the adapter.
3. Added `SkillRegistry.registerDynamicFromManifest(manifest, endpoint)` — converts a
   `SkillManifest` to `CustomSkill`, persists to `CustomSkillRepository`, auto-discovered
   on next `getAvailableSkills()` call.
4. Modified `MarketplaceRepository.install()` to call `registerDynamicFromManifest` after
   manifest validation (best-effort, install still records on failure).

**Why**: Without this, installing a marketplace skill was cosmetic — it appeared in the
Installed tab but the agent could never invoke it.

## Phase C — SkillManifest extended with 9 new fields
Fields added (all have defaults, backward-compatible):
`airiMinVersion`, `airiTargetVersion`, `createdAt`, `updatedAt`,
`signature`, `checksum`, `supportUrl`, `changelog`, `homepage`

Both `toJson()` and `fromJson()` updated. Existing manifests parse correctly.

## Phase D — SkillPackageVerifier + GitHubSkillImporter integration
- `SkillPackageVerifier.kt`: SHA-256 checksum, airiMinVersion compat check,
  signature awareness, freshness warning (>2yr old).
- `GitHubSkillImporter.importFromUrl()`: calls `verify()` after manifest parse;
  verification failures block import; warnings merged into result.

## Phase E — Circular dependency detection in SkillRegistry
- Added `detectCircularDependencies(skillId)` + private `detectCircularDeps(skillId, chain)`.
- Called from `installSkillWithVersion()` before `validateDependencies()`.
- Returns the full cycle chain for clear error messaging.

## Phase G — Audit logging + per-skill rate limiting
- `SkillAuditLogger.kt`: rolling 1000-event log in SharedPreferences.
  Provides `log()`, `getEvents()`, `getStats()`, `callsInWindow()`.
- `CustomSkillExecutor.execute()`: rate limit guard (60 calls/min per skill,
  ConcurrentHashMap + synchronized LongArray window), then audit log after
  execution result is captured (start/end timing).

## Phase H — FULL_ACCESS differentiation in SkillMemoryBridge
Three FULL_ACCESS-only methods added:
- `exportMemories(limit)` — full dump of session messages
- `getMemoryCount()` — total message count
- `recordTaggedFact(factType, content)` — structured fact with skill-tagged prefix
READ_WRITE skills cannot call these methods (returns empty/"" with a warning log).

## Phase I — modelAccess enforcement in SkillToolBridge
`effectiveBridge = if (skill.modelAccess != NONE) modelBridge else null`
Logged at DEBUG when bridge is suppressed for a NONE-access skill.

## Phase J — Updates tab added to MarketplaceScreen
Tab order: Explore(0), Installed(1), **Updates(2)**, Import(3), Publish(4).
`UpdatesTab` composable shows skills with `hasUpdate = true`, version diff arrow
("v1.0 → v1.1"), and SemanticWarn-colored Update button.
Badge count in tab label: "Updates (3)".

## Cleanup
- `SkillExecutor.kt`: annotated with `@Deprecated(level=WARNING)` + dead-code doc.
- `.gitignore`: hardened with app/build/, local.properties, *.apk, *.aab,
  *.keystore, *.jks, .idea/, android-sdk/, captures/, etc.

## Build verification
BUILD SUCCESSFUL — compileDebugKotlin, 0 errors, 40s.
