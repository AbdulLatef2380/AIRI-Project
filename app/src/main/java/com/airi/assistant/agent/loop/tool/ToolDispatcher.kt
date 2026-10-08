package com.airi.assistant.agent.loop.tool

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.execution.command.AccessibilityCommandBridge
import com.airi.assistant.agent.execution.node.NodeScanner
import com.airi.assistant.accessibility.service.ScreenContextHolder
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.tools.execution.AlarmTool
import com.airi.assistant.tools.execution.CalendarTool
import com.airi.assistant.tools.execution.NotesTool
import com.airi.assistant.tools.execution.SearchTool
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.AgentActivityBus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ToolDispatcher — maps AgentLoop tool_call names to real implementations.
 *
 * Every tool here has:
 *   - a real execution body (no placeholders)
 *   - a logged AIRI entry for auditing
 *   - a timeout enforced by the caller (AgentLoop)
 *
 * Adding a new tool: add it to [BuiltinTools], add dispatch case here.
 *
 * Tools whose names start with "skill_" are automatically forwarded to the
 * [SkillToolBridge] which routes them to the matching registered [AiriSkill].
 */
class ToolDispatcher(
    private val memoryManager:      MemoryManager? = null,
    // P1-1: session context for semantic memory search
    private val sessionIdProvider:  (() -> String)? = null,
    // Brave Search API key — injected from SecureApiKeyStore at construction time
    private val braveApiKeyProvider: (() -> String?)? = null,
    // Optional skill tool bridge — handles all "skill_*" tool names
    private val skillToolBridge: com.airi.assistant.ai.skills.SkillToolBridge? = null,
    // Canonical live connector bridge — handles dynamically exposed connector_* tools
    private val connectorToolBridge: com.airi.assistant.connector.ConnectorToolBridge? = null,
) {
    companion object {
        private const val TAG = "AIRI_ToolDispatcher"
    }

    sealed class ToolResult {
        data class Success(
            val output: String,
            val provenance: ToolProvenance = ToolProvenance.BUILTIN,
        ) : ToolResult()

        data class Error(
            val message: String,
            val code: String = ToolErrorCodes.EXECUTION_FAILED,
            val retryable: Boolean = false,
            val provenance: ToolProvenance = ToolProvenance.BUILTIN,
        ) : ToolResult()
    }

    suspend fun execute(
        toolName: String,
        args:     Map<String, String>,
        context:  Context,
        /** Explicit owner supplied by the runtime; never inferred from UI state. */
        executionId: String? = null,
        agentId: String = "terminal",
    ): ToolResult {
        Log.i(TAG, "TOOL_DISPATCH tool=$toolName argCount=${args.size}")
        AgentActivityBus.emit(
            ActivityEvent(
                message = PrivacyGuard.redactForTrace("Tool dispatched: $toolName"),
                executionId = executionId?.takeIf { it.isNotBlank() },
                category = ActivityCategory.TOOL,
            )
        )

        return when (toolName) {

            // ── Screen observation ─────────────────────────────────────────────
            "read_screen" -> {
                val service = ScreenContextHolder.serviceInstance
                if (service == null) {
                    ToolResult.Error(
                        "Accessibility service not connected. Enable AIRI in Accessibility settings.",
                        code = ToolErrorCodes.NOT_CONNECTED,
                    )
                } else {
                    val root = service.rootInActiveWindow
                    if (root == null) {
                        ToolResult.Error("No active window available", code = ToolErrorCodes.UNSUPPORTED)
                    } else {
                        val nodes = NodeScanner.collectAllNodes(root)
                        val texts = nodes.mapNotNull { it.text?.toString()?.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(40)
                        val pkg   = service.rootInActiveWindow?.packageName?.toString() ?: "unknown"
                        val summary = "App: $pkg\nVisible text:\n${texts.joinToString("\n").take(800)}"
                        Log.i(TAG, "AIRI READ_SCREEN pkg=$pkg nodes=${nodes.size} textItems=${texts.size}")
                        ToolResult.Success(summary)
                    }
                }
            }

            // ── App launch ─────────────────────────────────────────────────────
            "open_app" -> {
                val appName = args["app_name"] ?: return ToolResult.Error("Missing app_name", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = AccessibilityCommandBridge.launchApp(appName)
                if (result.success) ToolResult.Success("Launched $appName")
                else ToolResult.Error(result.message ?: "", code = ToolErrorCodes.EXECUTION_FAILED)
            }

            // ── UI interaction ─────────────────────────────────────────────────
            "tap" -> {
                val target = args["target"] ?: return ToolResult.Error("Missing target", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = AccessibilityCommandBridge.click(target)
                if (result.success) ToolResult.Success("Tapped: $target")
                else ToolResult.Error(result.message ?: "", code = ToolErrorCodes.EXECUTION_FAILED)
            }

            "type_text" -> {
                val text = args["text"] ?: return ToolResult.Error("Missing text", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = AccessibilityCommandBridge.typeText(text)
                if (result.success) ToolResult.Success("Typed: ${text.take(60)}")
                else ToolResult.Error(result.message ?: "", code = ToolErrorCodes.EXECUTION_FAILED)
            }

            "scroll_down" -> {
                val result = AccessibilityCommandBridge.scrollDown()
                if (result.success) ToolResult.Success("Scrolled down") else ToolResult.Error(result.message ?: "")
            }

            "go_back" -> {
                val result = AccessibilityCommandBridge.performBack()
                if (result.success) ToolResult.Success("Pressed back") else ToolResult.Error(result.message ?: "")
            }

            // ── Search ─────────────────────────────────────────────────────────
            "web_search" -> {
                val query = args["query"] ?: return ToolResult.Error("Missing query", code = ToolErrorCodes.INVALID_ARGUMENT)
                val searchTool = SearchTool(context, braveApiKey = braveApiKeyProvider?.invoke())

                // Try Brave Search first (real web results + Jina content extraction)
                val braveKey = braveApiKeyProvider?.invoke()
                if (!braveKey.isNullOrBlank()) {
                    val brave = searchTool.searchBrave(query, count = 5, enrich = true)
                    if (brave.success) {
                        Log.i(TAG, "WEB_SEARCH_COMPLETE provider=brave queryChars=${query.length} results=${brave.results.size} hasContent=${brave.topContent != null}")
                        return ToolResult.Success(brave.toAgentString())
                    }
                    Log.w(TAG, "WEB_SEARCH_FALLBACK from=brave to=duckduckgo")
                }

                // Fallback: DDG Instant Answers (~30% coverage but always free)
                val ddg = searchTool.searchDuckDuckGo(query)
                if (ddg.success) {
                    Log.i(TAG, "WEB_SEARCH_COMPLETE provider=duckduckgo queryChars=${query.length} resultChars=${ddg.summary.length}")
                    return ToolResult.Success(ddg.summary)
                }

                // Last resort: open browser (no content returned, but user can see it)
                Log.w(TAG, "WEB_SEARCH_FALLBACK from=duckduckgo to=browser queryChars=${query.length}")
                searchTool.searchViaIntent(query)
                ToolResult.Error(
                    "Search opened in browser for: $query. No result was returned to AIRI. " +
                        "Configure Brave Search in Settings to enable in-agent results.",
                    code = ToolErrorCodes.NETWORK_UNAVAILABLE,
                    retryable = false,
                )
            }

            "fetch_url" -> {
                val url = args["url"] ?: return ToolResult.Error("Missing url parameter", code = ToolErrorCodes.INVALID_ARGUMENT)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return ToolResult.Error("Invalid URL — must start with http:// or https://", code = ToolErrorCodes.INVALID_ARGUMENT)
                }
                val searchTool = SearchTool(context, braveApiKey = braveApiKeyProvider?.invoke())
                Log.i(TAG, "FETCH_URL urlChars=${url.length}")

                // Try Jina Reader first (clean markdown, handles JS-rendered pages)
                val jina = searchTool.fetchViaJina(url, maxChars = 4000)
                if (jina.success && jina.content.isNotBlank()) {
                    Log.i(TAG, "AIRI FETCH_URL_JINA_OK chars=${jina.content.length}")
                    return ToolResult.Success(
                        "Content from ${url}:\n\n${jina.content}"
                    )
                }

                // Fallback: direct HTTP fetch with HTML stripping
                val direct = searchTool.fetchPageContent(url)
                if (direct.success && direct.content.isNotBlank()) {
                    Log.i(TAG, "AIRI FETCH_URL_DIRECT_OK chars=${direct.content.length}")
                    return ToolResult.Success("Content from ${url}:\n\n${direct.content}")
                }

                ToolResult.Error(
                    "Could not fetch content from: $url (tried Jina Reader and direct fetch)",
                    code = ToolErrorCodes.NETWORK_UNAVAILABLE,
                    retryable = true,
                )
            }

            // ── Memory ─────────────────────────────────────────────────────────
            "memory_recall" -> {
                val query = args["query"] ?: return ToolResult.Error("Missing query", code = ToolErrorCodes.INVALID_ARGUMENT)
                val manager = memoryManager
                if (manager == null) {
                    ToolResult.Error(
                        "Memory not available in this session",
                        code = ToolErrorCodes.MEMORY_UNAVAILABLE,
                    )
                } else {
                    // P1-1: Use semantic search when embedding model is ready;
                    // fall back to recent messages when no embedding model is loaded.
                    val sessionId = sessionIdProvider?.invoke().orEmpty()
                    if (manager.isSemanticMemoryReady() && sessionId.isNotEmpty()) {
                        val ranked = manager.semanticSearch(sessionId, query, k = 5)
                        Log.i(TAG, "MEMORY_RECALL mode=semantic queryChars=${query.length} hits=${ranked.size}")
                        if (ranked.isEmpty()) {
                            ToolResult.Success("No memories found for: $query")
                        } else {
                            val formatted = ranked.joinToString("\n") { item ->
                                "• ${item.message.content.take(200)}"
                            }
                            ToolResult.Success("Memory results (semantic):\n$formatted")
                        }
                    } else if (sessionId.isBlank()) {
                        // Never fall back to a database-wide recent query. A
                        // missing session identity is an authorization failure,
                        // not permission to read another conversation.
                        Log.w(TAG, "MEMORY_RECALL_BLOCKED reason=missing_session_scope")
                        ToolResult.Error(
                            "Memory session is unavailable; refusing an unscoped recall.",
                            code = ToolErrorCodes.MEMORY_SCOPE_UNAVAILABLE,
                        )
                    } else {
                        val recent = manager.getRecentMessages(sessionId, 5)
                        Log.i(TAG, "MEMORY_RECALL mode=recent queryChars=${query.length} hits=${recent.size}")
                        if (recent.isEmpty()) {
                            ToolResult.Success("No memories found for: $query")
                        } else {
                            val formatted = recent.joinToString("\n") { "• ${it.content.take(200)}" }
                            ToolResult.Success("Memory results (recent, no embedding model):\n$formatted")
                        }
                    }
                }
            }

            // ── Calendar ───────────────────────────────────────────────────────
            "calendar_read" -> {
                val days = args["days"]?.toIntOrNull() ?: 7
                val cal  = CalendarTool(context)
                val events = cal.getUpcomingEvents(days)
                if (events.isEmpty()) {
                    ToolResult.Success("No events found in the next $days days.")
                } else {
                    val formatted = cal.summarize(events.take(10))
                    Log.i(TAG, "AIRI CALENDAR_READ days=$days events=${events.size}")
                    ToolResult.Success("Upcoming events:\n$formatted")
                }
            }

            "current_time" -> {
                val now = java.time.ZonedDateTime.now()
                val zone = java.time.ZoneId.systemDefault()
                ToolResult.Success(
                    "Current device time: ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} " +
                        "(${zone.id}, UTC${now.offset})"
                )
            }

            "calendar_create" -> {
                // Calendar creation is intentionally not a generic dispatcher action.
                // AgentLoop must use CalendarCreateRuntime, which owns a private
                // proposal plus task/run/step-bound one-shot approval before one
                // provider insert. This prevents raw arguments and live writes from
                // bypassing durable ownership, review, recovery and evidence.
                ToolResult.Error(
                    "Calendar creation requires a task-owned approval session before it can run.",
                    code = ToolErrorCodes.APPROVAL_REQUIRED,
                )
            }

            // ── Alarm ──────────────────────────────────────────────────────────
            "set_alarm" -> {
                val time  = args["time"]  ?: return ToolResult.Error("Missing time", code = ToolErrorCodes.INVALID_ARGUMENT)
                val label = args["label"] ?: "AIRI Alarm"
                val alarm = AlarmTool(context)
                val timePair = alarm.parseTime(time)
                    ?: return ToolResult.Error("Could not parse time '$time'. Use format like '7:30am' or '14:00'", code = ToolErrorCodes.INVALID_ARGUMENT)
                val result = alarm.setAlarmViaIntent(timePair.first, timePair.second, label)
                if (result.success) {
                    Log.i(TAG, "ALARM_SET timeChars=${time.length} labelChars=${label.length}")
                    ToolResult.Success("Alarm set for $time: $label")
                } else {
                    ToolResult.Error(result.message, code = ToolErrorCodes.EXECUTION_FAILED)
                }
            }

            // ── Notes ──────────────────────────────────────────────────────────
            "create_note" -> {
                val title   = args["title"]   ?: return ToolResult.Error("Missing title", code = ToolErrorCodes.INVALID_ARGUMENT)
                val content = args["content"] ?: return ToolResult.Error("Missing content", code = ToolErrorCodes.INVALID_ARGUMENT)
                val notes   = NotesTool(context)
                val note    = notes.createNote(title, content)
                Log.i(TAG, "NOTE_CREATED titleChars=${title.length} contentChars=${content.length}")
                ToolResult.Success("Note created: ${note.title}")
            }

            // ── Restricted terminal ──────────────────────────────────────────
            // TerminalRuntime owns the per-command governance check and sandbox.
            "terminal_execute" -> {
                val command = args["command"]?.trim().orEmpty()
                if (command.isBlank()) return ToolResult.Error("Missing command", code = ToolErrorCodes.INVALID_ARGUMENT)
                val terminal = com.airi.assistant.core.ServiceLocator.terminalRuntime
                val before = terminal.lines.value.size
                terminal.execute(command, agentId = agentId)
                val output = terminal.lines.value.drop(before)
                    .joinToString("\n") { it.text }
                    .trim()
                Log.i(TAG, "TERMINAL_TOOL_COMPLETE commandChars=${command.length} outputChars=${output.length}")
                ToolResult.Success(output.ifBlank { "Command completed without output." })
            }

            // ── Confirmation request (LLM asks user) ──────────────────────────
            // This tool does NOT execute an action — it signals AgentLoop that
            // the LLM wants to pause for user confirmation before continuing.
            // AgentLoop callers (ChatViewModel) must handle StepEvent.ToolExecuted
            // for "ask_confirmation" and show a dialog before resuming the loop.
            "ask_confirmation" -> {
                val action  = args["action"]  ?: "Proceed?"
                val details = args["details"] ?: ""
                // Return a special marker that ChatViewModel can detect
                ToolResult.Success("CONFIRMATION_REQUIRED|$action|$details")
            }

            // ── Skill invocations (skill_*) ────────────────────────────────────
            else -> {
                // Route any "skill_*" prefixed tool call through the SkillToolBridge
                val bridge = skillToolBridge
                if (bridge != null && bridge.handles(toolName)) {
                    Log.i(TAG, "AIRI SKILL_TOOL_DISPATCH tool=$toolName")
                    val result = bridge.invoke(toolName, args)
                    val output = com.airi.assistant.ai.skills.SkillToolResultFormatter.format(result)
                    if (result.success) {
                        ToolResult.Success(output, provenance = ToolProvenance.SKILL)
                    } else {
                        val code = when {
                            result.metadata["failure_type"] == "authorization" -> ToolErrorCodes.PERMISSION_DENIED
                            result.error?.contains("not connected", ignoreCase = true) == true -> ToolErrorCodes.NOT_CONNECTED
                            else -> ToolErrorCodes.SKILL_FAILED
                        }
                        ToolResult.Error(
                            output,
                            code = code,
                            provenance = ToolProvenance.SKILL,
                        )
                    }
                } else if (connectorToolBridge != null && connectorToolBridge.handles(toolName)) {
                    Log.i(TAG, "AIRI CONNECTOR_TOOL_DISPATCH tool=$toolName path=ConnectorRuntimeManager")
                    when (val result = connectorToolBridge.invoke(toolName, args)) {
                        is com.airi.assistant.connector.ConnectorOutput.Success ->
                            ToolResult.Success(result.text, provenance = ToolProvenance.CONNECTOR)
                        is com.airi.assistant.connector.ConnectorOutput.Streaming -> ToolResult.Error(
                            "Streaming connector output is not supported by text ToolDispatcher yet.",
                            code = ToolErrorCodes.UNSUPPORTED,
                            provenance = ToolProvenance.CONNECTOR,
                        )
                        is com.airi.assistant.connector.ConnectorOutput.ApprovalRequired -> ToolResult.Error(
                            result.message,
                            code = ToolErrorCodes.APPROVAL_REQUIRED,
                            provenance = ToolProvenance.CONNECTOR,
                        )
                        is com.airi.assistant.connector.ConnectorOutput.Failure -> ToolResult.Error(
                            result.message,
                            code = ToolErrorCodes.fromConnector(result.code),
                            retryable = result.retryable,
                            provenance = ToolProvenance.CONNECTOR,
                        )
                    }
                } else {
                    Log.w(TAG, "Unknown tool: $toolName")
                    ToolResult.Error(
                        "Unknown tool: $toolName. Available tools: ${BuiltinTools.ALL.map { it.name }.joinToString()}",
                        code = ToolErrorCodes.TOOL_NOT_FOUND,
                    )
                }
            }
        }
    }

    // ── DateTime parsing ───────────────────────────────────────────────────────

    private fun parseDateTime(input: String): Long? {
        // Try ISO-8601 first
        runCatching {
            return java.time.Instant.parse(input).toEpochMilli()
        }
        runCatching {
            val dt = LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        // Natural language: "3pm tomorrow", "9am", "tomorrow at 10"
        val lower = input.lowercase().trim()
        val now   = java.util.Calendar.getInstance()
        runCatching {
            val cal = now.clone() as java.util.Calendar
            when {
                lower.contains("tomorrow") -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                lower.contains("next week") -> cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
            }
            // Extract HH:mm or h am/pm
            val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
            val match = timeRegex.find(lower) ?: return@runCatching
            var hour = match.groupValues[1].toInt()
            val min  = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3]
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, min)
            cal.set(java.util.Calendar.SECOND, 0)
            return cal.timeInMillis
        }
        return null
    }
}
