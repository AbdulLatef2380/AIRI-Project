---
name: Skill System Architecture (Phases 1-12)
description: Complete skill engine wiring — files created, API surfaces used, and integration points between SkillToolBridge, ToolDispatcher, and ChatViewModel.
---

# Skill System Architecture

## What was built

### Core infrastructure (`ai/skills/`)
- `AiriSkill.kt` — interface with `skillId`, `name`, `description`, `parameters`, `toolDefinitions`, `score(input, context)`, `execute(params)`; all new fields have defaults for backward compat with existing skills
- `SkillManifest.kt` — `SkillManifest` data class (id, name, version, author, description, tools, permissions, tier, dependencies, minApiLevel, requiredConnectors, tags, homepage, signature); `SkillManifestValidator.validate()` returns `ValidationResult(isValid, errors, warnings)`
- `SkillContext.kt` — carries userId, sessionId, locale, timezone, connectorIds, memoryAccess, modelAccess, extra; all optional with defaults
- `SkillResult.kt` — `SkillResult(success, data, error, metadata, toolCalls, followUpActions)`; companion `ok()` / `fail()`
- `SkillMemoryAccess.kt` — `SkillMemoryAccess` enum (NONE, READ_ONLY, READ_WRITE, FULL_ACCESS) + `SkillModelAccess` enum (NONE, CHAT, CHAT_WITH_ROUTING)
- `SkillModelBridge.kt` — wraps `HybridOrchestrator`, calls `executeStream(ExecutionRequest(prompt=…, systemPrompt=…), context, onToken, onComplete, onError)`
- `SkillMemoryBridge.kt` — wraps `MemoryManager`; `read(limit)`, `write(role, content, sessionId)`, `search(sessionId, query, k)` returning `List<ChatMessage>`; uses `manager.semanticSearch(sessionId, query, k).map { it.message }`
- `OfficialSkillLibrary.kt` — `object` with `SkillTier` enum (FREE, PRO, ENTERPRISE, BETA), `SkillEntry(manifest, tier, factory)`, `getAll()`, `forId(id)`, `factoryFor(id, context)` covering all 14 skills
- `SkillToolBridge.kt` — `class SkillToolBridge(context, registry, skillCtx)`: `asToolSchemas()`, `invoke(toolName, args)`, `handles(toolName)`; tool names are prefixed `skill_`

### Official skill implementations (`ai/skills/impl/`)
New files: WebSearchSkill, WebsiteReaderSkill, DocumentReaderSkill, TranslatorSkill, CodeAssistantSkill, ResearchAgentSkill, TaskPlannerSkill, MemoryManagerSkill, FileManagerSkill (all take Context as constructor arg)

Pre-existing: GithubGuardianSkill, GmailAssistantSkill, TelegramMessengerSkill, CalendarEventsSkill, DriveSearchSkill

### GitHub import (`marketplace/`)
- `GitHubSkillImporter.kt` — `object` with `importFromUrl(url): ImportResult`; `ImportResult(success, skill, manifest, errors, warnings)`; supports `github.com/user/repo` and raw `skill.json` URLs; validates manifest and creates `CustomSkill`

## Integration points

### ToolDispatcher wiring
`ToolDispatcher` constructor got optional `skillToolBridge: SkillToolBridge? = null`. The `else` branch in `dispatch()` checks `bridge.handles(toolName)` and calls `bridge.invoke(toolName, args)`, returning `ToolResult.Success(result)`.

### ChatViewModel wiring
```kotlin
private val skillToolBridge = SkillToolBridge(
    registry = SkillRegistry(appContext),
    context  = appContext
)
private val toolDispatcher = ToolDispatcher(
    ...,
    skillToolBridge = skillToolBridge
)
```

### MarketplaceScreen tabs
4 tabs: Explore(0), Installed(1), Import(2=GitHubImportTab), Publish(3). `GitHubImportTab(onImported, onError)` composable calls `GitHubSkillImporter.importFromUrl()` in a coroutine.

## Key API rules
- `ExecutionRequest` fields: `prompt`, `systemPrompt` (NOT `userMessage`/`systemMessage`)
- `HybridOrchestrator.executeStream(request, context, onToken, onComplete, onError)` — context is Android `Context`
- `MemoryManager.semanticSearch(sessionId, query, k)` returns `List<EmbeddingService.RankedMessage>` where each item has `.message: ChatMessage`
- `MemoryManager.getRecentMessages(limit)` — no sessionId param
- `SearchTool` is at `com.airi.assistant.tools.execution.SearchTool(Context, braveApiKey?)`

**Why:** The skill system must route `skill_*` tool calls through `SkillToolBridge` rather than fail with "unknown tool". The optional parameter preserves all existing `ToolDispatcher` call sites without modification.

**How to apply:** Any new `ToolDispatcher` instantiation that wants skill routing should pass a `SkillToolBridge(context, SkillRegistry(context))`.
