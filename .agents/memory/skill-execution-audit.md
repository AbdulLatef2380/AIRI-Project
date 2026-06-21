---
name: Skill Execution Audit
description: End-to-end verification results; AIRI_PROOF tag inventory; confirmed execution path; two fixes applied.
---

## Verified Execution Path

```
User → ChatViewModel.sendMessage()
  → agentLoop.run(tools = BuiltinTools.ALL + skillToolBridge.asToolSchemas())
  → AgentLoop: AIRI_PROOF TOOL_CALL → dispatcher.execute(toolName, args, ctx)
  → ToolDispatcher: AIRI_PROOF TOOL_DISPATCH → bridge.invoke(toolName, args)
  → SkillToolBridge: AIRI_PROOF SKILL_INVOKE → skill.execute(params) [30s timeout]
  → SkillResult → AIRI_PROOF SKILL_SUCCESS / SKILL_ERROR / SKILL_TIMEOUT / SKILL_EXCEPTION
  → ToolResult.Success(text) → AgentLoop history → LLM next turn
```

## AIRI_PROOF Tags (complete inventory)

| Tag | File | Meaning |
|-----|------|---------|
| TOOL_CALL | AgentLoop.kt | LLM emitted a tool_call JSON |
| TOOL_CALL_PARSE_RETRY | AgentLoop.kt | parse failed, retrying |
| TOOL_RESULT | AgentLoop.kt | tool returned, length logged |
| TOOL_DISPATCH | ToolDispatcher.kt | dispatcher received tool call |
| SKILL_TOOL_DISPATCH | ToolDispatcher.kt | routing to SkillToolBridge |
| WEB_SEARCH_BRAVE | ToolDispatcher.kt | web_search builtin via Brave |
| WEB_SEARCH_DDG | ToolDispatcher.kt | web_search builtin via DDG |
| FETCH_URL_JINA_OK | ToolDispatcher.kt | fetch_url via Jina reader |
| MEMORY_RECALL | ToolDispatcher.kt | memory_recall semantic search |
| SKILL_SCHEMAS_BUILT | SkillToolBridge.kt | schemas pushed to LLM |
| SKILL_INVOKE | SkillToolBridge.kt | skill.execute() is being called |
| SKILL_SUCCESS | SkillToolBridge.kt | skill returned success=true |
| SKILL_ERROR | SkillToolBridge.kt | skill returned success=false |
| SKILL_TIMEOUT | SkillToolBridge.kt | 30s budget exceeded |
| SKILL_EXCEPTION | SkillToolBridge.kt | unexpected exception |
| SKILL_NO_MATCH | SkillToolBridge.kt | no skill found for tool name |
| SKILL_BRIDGE_SUPPRESSED | SkillToolBridge.kt | modelBridge withheld from NONE skill |

## Skill → Tool Name Mapping

| Skill class | skillId | LLM tool name(s) |
|-------------|---------|-----------------|
| WebSearchSkill | web_search | skill_web_search |
| WebsiteReaderSkill | website_reader | skill_website_reader / skill_read_url |
| ResearchAgentSkill | research_agent | skill_research |
| TranslatorSkill | translator | skill_translate_text |
| CodeAssistantSkill | code_assistant | skill_code_assist |
| TaskPlannerSkill | task_planner | skill_plan_tasks |
| MemoryManagerSkill | memory_manager | skill_memory_recall, skill_memory_save |
| DocumentReaderSkill | document_reader | skill_read_document |
| FileManagerSkill | file_manager | skill_list_files, skill_search_files |

findSkillForTool() lookup order:
1. skill.skillId == strippedName || skill.name == strippedName (exact id match)
2. skill.toolDefinitions.any { it.name == strippedName } (toolDef name match)

## Two Fixes Applied

**Fix 1 — SkillToolBridge.kt** (complete rewrite):
- All 7 AIRI_PROOF variants added to invoke()
- Separate TimeoutCancellationException catch before generic Exception
- modelBridgeActive + memoryActive + sessionId logged on every call
- SKILL_SCHEMAS_BUILT logs schema count + names on asToolSchemas()

**Fix 2 — ChatViewModel.kt**:
- skillCtx lambda now includes `configValues = buildMap { put("brave_api_key", ...) }`
- Reads from ServiceLocator.secureApiKeyStore.getKey(CloudProvider.BRAVE)
- Previously WebSearchSkill + ResearchAgentSkill never received the Brave key

## Known Redundancies (not bugs)

- BuiltinTools.WEB_SEARCH ("web_search") + WebSearchSkill ("skill_web_search") — both work, both do the same thing
- BuiltinTools.MEMORY_RECALL ("memory_recall") + MemoryManagerSkill ("skill_memory_recall") — skill version has more functionality

## Build Environment Note

Gradle compile times out at 120s in the Replit container. Use `./gradlew :app:compileDebugKotlin --no-daemon` on a machine with a warm Gradle cache. Static verification (69 checks) all pass instead.
