---
name: Capability Wiring Audit
description: Results of full capability audit — what systems are wired vs disconnected, and the three fixes applied.
---

## Systems confirmed FULLY WIRED (no action needed)
- **AgentLoop** (`agent/loop/AgentLoop.kt`) — runs on every sendMessage call
- **ToolDispatcher** — 14 real tools: read_screen, open_app, tap, type_text, scroll_down, go_back, web_search, fetch_url, memory_recall, calendar_read, calendar_create, set_alarm, create_note, ask_confirmation
- **TerminalScreen** — uses `ServiceLocator.terminalRuntime.execute()` directly
- **SandboxWorkspaceScreen** — uses `SandboxExecutor(session).execute()` directly
- **ConnectorsScreen/ViewModel** — real ConnectorRegistry.connect()/disconnect()
- **Memory/RAG** — RagRetriever injected before every AgentLoop call
- **AgentTasksScreen** — real ScheduledJobOrchestrator + WorkManager

## Three gaps fixed

### Gap 1 (CRITICAL): Skill schemas not in AgentLoop tool list
- **Symptom**: LLM never called `skill_code_assistant`, `skill_research_agent`, `skill_translator`, etc.
- **Root cause**: `agentLoop.run(tools = BuiltinTools.ALL)` — skill schemas from `SkillToolBridge.asToolSchemas()` were never included.
- **Fix**: `val activeTools = BuiltinTools.ALL + skillToolBridge.asToolSchemas()` merged at call site in `ChatViewModel.sendMessage()`.
- **Why**: ToolDispatcher already routes `skill_*` names correctly — the LLM just didn't know these tools existed. The fix advertises them in the system prompt.

### Gap 2 (HIGH): Plan Mode chip disconnected from execution
- **Symptom**: Plan Mode chip animated but had zero effect on LLM behavior.
- **Root cause**: `isPlanModeActive` was local `mutableStateOf` in ChatScreen — never reached ChatViewModel.
- **Fix**: Added `_isPlanModeActive: MutableStateFlow<Boolean>`, `isPlanModeActive: StateFlow<Boolean>`, and `togglePlanMode()` to ChatViewModel. When active, appends `[PLAN MODE ACTIVE]` system prompt instruction requiring step-by-step planning. ChatScreen now collects from ViewModel and calls `viewModel.togglePlanMode()`.

### Gap 3 (MEDIUM): activeToolCount and activeSkillCount always 0
- **Symptom**: Toolbar chips always showed "Tools" and "Skills" with no counts.
- **Root cause**: Both hardcoded as 0 at the AdvancedChatInputBar call site in ChatScreen.
- **Fix**: ChatScreen now passes `activeToolCount = BuiltinTools.ALL.size` (constant 14) and `activeSkillCount = activeSkillCount` (collected from `viewModel.activeSkillCount`). ViewModel computes count once at init via `skillToolBridge.asToolSchemas().size`.

## Key architectural rule
When extending the tool list, always update the `agentLoop.run(tools = ...)` call in `ChatViewModel.sendMessage()`. The ToolDispatcher can handle any tool name routed correctly, but the LLM must see the schema in the system prompt to ever emit that tool_call.
