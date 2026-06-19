---
name: Skill System Security Audit Findings
description: Confirmed security gaps found and fixed in the Skill System — do not re-introduce these patterns.
---

# Skill System Security Audit — Confirmed Gaps Fixed

## Fixes Applied

### HIGH — Plain HTTP allowed in CustomSkillSecurity
**File:** `CustomSkillSecurity.kt`
**Rule:** `isValidEndpoint()` now only accepts `"https"` scheme. HTTP was silently permitted.
**Why:** Third-party custom skill endpoints must always be TLS-encrypted.

### HIGH — Firebase UID injected into every third-party skill request
**File:** `CustomSkillExecutor.kt`
**Rule:** Removed `putIfAbsent("user_id", FirebaseAuth.getInstance().currentUser?.uid)` from `bodyInput`. Also removed unused `FirebaseAuth` import.
**Why:** PII must never be forwarded to arbitrary third-party endpoints.

### FUNCTIONALITY GAP — SkillModelBridge not threaded into SkillToolBridge
**Files:** `SkillToolBridge.kt`, `ChatViewModel.kt`
**Rule:** `SkillToolBridge` now accepts `modelBridge: SkillModelBridge? = null` and passes it via `skillCtx().copy(modelBridge = modelBridge)`. `ChatViewModel` constructs `SkillToolBridge` with `SkillModelBridge.create(hybridOrchestrator, appContext)`.
**Why:** Four official skills (Translator, CodeAssistant, ResearchAgent, TaskPlanner) silently degraded when invoked via the agent loop because the model bridge was always null.
**How to apply:** Any future `SkillToolBridge` instantiation should pass a real model bridge if one is available.

### MEDIUM — SkillRuntime coroutine scope never cancelled
**File:** `SkillRuntime.kt`
**Rule:** Added `fun destroy() { scope.cancel() }`. Called from `ChatViewModel.onCleared()` via `runCatching { ServiceLocator.skillRuntime.destroy() }`.
**Why:** `CoroutineScope(Dispatchers.Default + SupervisorJob())` at line 46 leaked for the process lifetime.

## Architecture Facts (do not re-audit)

- PRIMARY execution: `ChatViewModel` → `AgentLoop` → `ToolDispatcher` → `SkillToolBridge` → `SkillRegistry.getAvailableSkills()` → `skill.execute()`
- `SkillExecutor.tryHandle()` = dead code. 0 callers in hot path.
- `OfficialSkillLibrary` = manifest catalog only. Routing uses `SkillRegistry`.
- `SkillRuntime` = instantiated in ServiceLocator but not used in hot path. Valid infrastructure for future graph-native execution.
- `SkillMemoryAccess` enforcement = CORRECT. Bridge returns null for NONE skills; write() blocked for READ_ONLY.
- `SkillModelAccess` enforcement = now enforced by SkillToolBridge passing real/null bridge based on context.
