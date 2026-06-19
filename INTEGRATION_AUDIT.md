# AIRI Skill System — Complete Integration Audit
**Date:** 2026-06-19  
**Scope:** End-to-end execution chain from user message → AgentLoop → ToolDispatcher → SkillToolBridge → AiriSkill.execute()  
**Method:** Direct source inspection of every file in the chain. No assumptions.

---

## 1. Execution Chain — Concrete Code Evidence

### 1.1 Entry Point: ChatViewModel
**File:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`

```kotlin
// ChatViewModel instantiates the bridge and passes it to ToolDispatcher:
val skillToolBridge = SkillToolBridge(
    context  = appContext,
    registry = SkillRegistry(appContext)
)
val dispatcher = ToolDispatcher(
    ...,
    skillToolBridge = skillToolBridge
)
agentLoop.run(...)
```

**Confirmed:** `SkillToolBridge` is instantiated with a live `SkillRegistry`, not a stub. The `SkillRegistry` constructor immediately calls `OfficialSkillLibrary.buildAll()` which returns all 9 official skill instances.

---

### 1.2 AgentLoop
**File:** `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`

The full loop flow:
```
AgentLoop.run()
  └─ callLLM(prompt)                  // calls HybridOrchestrator.executeStream()
       └─ parseToolCall(token)         // regex-extracts <tool_call> XML blocks
            └─ dispatcher.execute(name, args)
```

`parseToolCall` produces a `ToolCall(name, args)` whenever the LLM emits a `<tool_call name="...">` block. The result is passed directly to `dispatcher.execute()`.

**Confirmed:** No intermediate layer between AgentLoop and ToolDispatcher.

---

### 1.3 ToolDispatcher
**File:** `app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt`

```kotlin
fun execute(name: String, args: Map<String, Any>): String {
    // Skill tools take priority — checked first
    if (skillToolBridge != null && skillToolBridge.handles(name)) {
        return skillToolBridge.invoke(name, args)
    }
    return when (name) {
        "web_search"    -> webSearch(args)
        "memory_search" -> memorySearch(args)
        // ... other built-in tools
        else            -> "Unknown tool: $name"
    }
}
```

Key points verified:
- `skillToolBridge` is an optional parameter (`SkillToolBridge? = null`) — never null in production (ChatViewModel always passes it)
- The skill bridge is checked **before** the `when` block, so skill tools shadow any built-in with the same name
- `handles(name)` delegates to `SkillRegistry.hasToolForName(name)`
- `invoke(name, args)` delegates to `SkillRegistry.dispatchTool(name, args)`

---

### 1.4 SkillToolBridge
**File:** `app/src/main/java/com/airi/assistant/ai/skills/SkillToolBridge.kt`

```kotlin
fun handles(toolName: String): Boolean =
    registry.hasToolForName(toolName)

fun invoke(toolName: String, args: Map<String, Any>): String {
    val ctx = skillCtx()
    return runBlocking {
        registry.dispatchTool(toolName, ctx, args)
    }
}
```

`runBlocking` is used intentionally here: `ToolDispatcher.execute()` is synchronous (called from within the AgentLoop coroutine which already manages its own suspension), and the Skill API is suspending. `runBlocking` bridges the gap without leaking a thread.

---

### 1.5 SkillRegistry
**File:** `app/src/main/java/com/airi/assistant/ai/skills/SkillRegistry.kt`

```kotlin
fun hasToolForName(toolName: String): Boolean =
    loadedSkills.any { skill -> skill.manifest.tools.any { it.name == toolName } }

suspend fun dispatchTool(toolName: String, ctx: SkillContext, args: Map<String, Any>): String {
    val skill = loadedSkills.first { s -> s.manifest.tools.any { it.name == toolName } }
    val result = skill.execute(ctx, args)
    return if (result.success) result.output else "Skill error: ${result.error}"
}
```

`loadedSkills` is populated at init by `OfficialSkillLibrary.buildAll(appContext)` — all 9 skills are always available.

---

### 1.6 AiriSkill.execute()
**File:** `app/src/main/java/com/airi/assistant/ai/skills/AiriSkill.kt`

```kotlin
interface AiriSkill {
    val manifest: SkillManifest
    suspend fun execute(ctx: SkillContext, args: Map<String, Any>): SkillResult
}
```

Every `impl/` class implements this. Example — `WebSearchSkill`:
```kotlin
class WebSearchSkill : AiriSkill {
    override val manifest = OfficialSkillLibrary.descriptors[SKILL_ID]!!
    override suspend fun execute(ctx: SkillContext, args: Map<String, Any>): SkillResult {
        val query = args["query"] as? String ?: return SkillResult.error("Missing query")
        // ... HTTP fetch via OkHttp ...
        return SkillResult.success(output = resultText)
    }
}
```

---

## 2. Complete Verified Execution Chain

```
User sends message
       │
       ▼
ChatViewModel.sendMessage()
  └─ agentLoop.run(userMessage, systemPrompt)
       │
       ▼
AgentLoop.callLLM(ExecutionRequest(prompt, systemPrompt))
  └─ HybridOrchestrator.executeStream(request, context, onToken, onComplete, onError)
       │  (streams tokens; onToken accumulates them)
       │
       ▼
AgentLoop.parseToolCall(accumulatedText)
  └─ Regex matches <tool_call name="web_search">{"query":"…"}</tool_call>
  └─ Returns ToolCall("web_search", mapOf("query" to "…"))
       │
       ▼
ToolDispatcher.execute("web_search", args)
  └─ skillToolBridge.handles("web_search") → true  (WebSearchSkill has tool named "web_search")
  └─ skillToolBridge.invoke("web_search", args)
       │
       ▼
SkillToolBridge.invoke()
  └─ runBlocking { registry.dispatchTool("web_search", ctx, args) }
       │
       ▼
SkillRegistry.dispatchTool()
  └─ skill = loadedSkills.first { "web_search" in it.manifest.tools.map { t -> t.name } }
  └─ result = skill.execute(ctx, args)
       │
       ▼
WebSearchSkill.execute(ctx, args) : SkillResult
  └─ Returns SkillResult(success=true, output="…search results…")
       │
       ▼
ToolDispatcher returns result string
AgentLoop appends it as tool output
AgentLoop calls callLLM() again with tool result in context
Final answer streamed to ChatScreen
```

---

## 3. Official Skill Library — All 9 Skills

| Skill | Tool Name(s) | Key Implementation |
|---|---|---|
| `WebSearchSkill` | `web_search` | OkHttp → search API |
| `WebsiteReaderSkill` | `read_website` | OkHttp + HTML strip |
| `DocumentReaderSkill` | `read_document` | ContentResolver + text extraction |
| `TranslatorSkill` | `translate_text` | ModelBridge LLM call |
| `CodeAssistantSkill` | `execute_code`, `analyze_code` | Sandbox execution |
| `ResearchAgentSkill` | `research_topic` | Multi-step web+model |
| `TaskPlannerSkill` | `create_plan`, `update_plan` | Structured output |
| `MemoryManagerSkill` | `store_memory`, `retrieve_memory` | MemoryBridge |
| `FileManagerSkill` | `list_files`, `read_file` | ContentResolver |

All 9 are instantiated in `OfficialSkillLibrary.buildAll()` and registered in `SkillRegistry.init{}`. They are **always available** — no install/enable step required.

---

## 4. Key Infrastructure Files Built

| File | Lines | Purpose |
|---|---|---|
| `ai/skills/SkillManifest.kt` | 186 | Data contract: id, name, tools, permissions, toJson()/fromJson() |
| `ai/skills/AiriSkill.kt` | 101 | Interface: `manifest` + `suspend execute()` |
| `ai/skills/SkillContext.kt` | ~60 | Runtime context passed to every skill call |
| `ai/skills/SkillResult.kt` | ~30 | Typed success/error return value |
| `ai/skills/SkillMemoryAccess.kt` | ~40 | Enum: NONE / READ_ONLY / READ_WRITE / FULL_ACCESS |
| `ai/skills/SkillMemoryBridge.kt` | 131 | Adapter: SkillContext → MemoryManager.semanticSearch() |
| `ai/skills/SkillModelBridge.kt` | 93 | Adapter: SkillContext → HybridOrchestrator.executeStream() |
| `ai/skills/SkillToolBridge.kt` | 126 | Connects ToolDispatcher to SkillRegistry |
| `ai/skills/SkillRegistry.kt` | 461 | Central registry; owns loadedSkills; dispatchTool() |
| `ai/skills/OfficialSkillLibrary.kt` | 353 | Factory for all 9 official skill instances |
| `ai/skills/GitHubSkillImporter.kt` | 273 | Download + validate + install skill.json from GitHub |
| `ai/skills/impl/WebSearchSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/WebsiteReaderSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/DocumentReaderSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/TranslatorSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/CodeAssistantSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/ResearchAgentSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/TaskPlannerSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/MemoryManagerSkill.kt` | — | Official skill implementation |
| `ai/skills/impl/FileManagerSkill.kt` | — | Official skill implementation |

---

## 5. UI Layer

| Screen | Route | Purpose |
|---|---|---|
| `MarketplaceScreen` | `screen_marketplace` | 4 tabs: Explore / Installed / Import / Publish |
| `SkillBuilderScreen` | `screen_skill_builder/{skillId}` | Create/edit API+Webhook custom skills |
| `SkillManagerScreen` | `screen_skill_manager` | List/delete custom skills; import from storage/GitHub |
| `SkillCreationWizardScreen` | `screen_skill_creation_wizard` | **NEW** — 4-step wizard generating skill.json manifests |

The `PublishTab` in `MarketplaceScreen` now shows a "Create with Wizard" CTA card that navigates to the new wizard.

---

## 6. Skill Creation Wizard — Feature Summary

**Route:** `AiriRoute.SKILL_CREATION_WIZARD` → `SkillCreationWizardScreen`  
**Entry point:** Publish tab of MarketplaceScreen → "Create with Wizard" card  
**Steps:**

| Step | Content |
|---|---|
| 1 — Identity | id, name, description, version, author, category picker, emoji icon picker, tags, repo URL, license |
| 2 — Tools | Add/remove tool definitions; each tool has name, description, and typed parameters (name, type, description, required flag) |
| 3 — Permissions | `SkillMemoryAccess` selector (NONE/READ_ONLY/READ_WRITE/FULL_ACCESS); `SkillModelAccess` selector (NONE/CHAT/CHAT_WITH_ROUTING); dependency list |
| 4 — Preview & Export | Rendered skill.json with monospace code view; Copy to clipboard, Share via Android share intent, Edit (go back to step 1) |

**Generated artifact:** A valid `SkillManifest.toJson()` JSON blob ready to paste into the Publish tab's manifest editor or share to a GitHub repo for import.

**Distinction from SkillBuilderScreen:** SkillBuilderScreen creates `CustomSkill` records (API/Webhook with endpoint + headers + bodyTemplate) stored in `CustomSkillRepository`. The Wizard creates `SkillManifest` JSON for the official skill platform — these are distributed as files, not stored locally.

---

## 7. Execution API Verified Signatures

```kotlin
// HybridOrchestrator (called by AgentLoop)
fun executeStream(
    request:    ExecutionRequest,   // .prompt + .systemPrompt
    context:    AgentContext,
    onToken:    (String) -> Unit,
    onComplete: (String) -> Unit,
    onError:    (Throwable) -> Unit
)

// MemoryManager (called by SkillMemoryBridge)
fun semanticSearch(
    sessionId: String,
    query:     String,
    k:         Int
): List<EmbeddingService.RankedMessage>   // .message: ChatMessage

// SkillToolBridge (called by ToolDispatcher)
fun handles(toolName: String): Boolean
fun invoke(toolName: String, args: Map<String, Any>): String

// AiriSkill (implemented by all 9 official skills)
suspend fun execute(ctx: SkillContext, args: Map<String, Any>): SkillResult
```

---

## 8. What Does NOT Exist (Boundaries)

- No `SkillStore` persistence — official skills are always in-memory; only `CustomSkill` records are persisted via `CustomSkillRepository`
- No skill sandboxing — official skills run in the main process; `CodeAssistantSkill` uses a restricted subprocess for code execution only
- No version pinning in `SkillRegistry` — all installed manifests use the version string from their `skill.json`
- No skill-level auth tokens — skills inherit the app's existing API key store via `SkillContext`

---

*Audit complete. All evidence from direct source inspection; zero assumptions.*
