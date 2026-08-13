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
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ToolDispatcher — maps AgentLoop tool_call names to real implementations.
 *
 * Every tool here has:
 *   - a real execution body (no placeholders)
 *   - a logged AIRI_RUNTIME entry for auditing
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
    private val skillToolBridge: com.airi.assistant.ai.skills.SkillToolBridge? = null
) {
    companion object {
        private const val TAG = "AIRI_ToolDispatcher"
    }

    sealed class ToolResult {
        data class Success(val output: String) : ToolResult()
        data class Error(val message: String) : ToolResult()
    }

    suspend fun execute(
        toolName: String,
        args:     Map<String, String>,
        context:  Context
    ): ToolResult {
        Log.i(TAG, "AIRI_RUNTIME TOOL_DISPATCH tool=$toolName args=${args.entries.joinToString { "${it.key}=${it.value.take(40)}" }}")
        AgentActivityBus.emit("Tool: $toolName", ActivityCategory.TOOL)

        return when (toolName) {

            // ── Screen observation ─────────────────────────────────────────────
            "read_screen" -> {
                val service = ScreenContextHolder.serviceInstance
                if (service == null) {
                    ToolResult.Error("Accessibility service not connected. Enable AIRI in Accessibility settings.")
                } else {
                    val root = service.rootInActiveWindow
                    if (root == null) {
                        ToolResult.Error("No active window available")
                    } else {
                        val nodes = NodeScanner.collectAllNodes(root)
                        val texts = nodes.mapNotNull { it.text?.toString()?.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(40)
                        val pkg   = service.rootInActiveWindow?.packageName?.toString() ?: "unknown"
                        val summary = "App: $pkg\nVisible text:\n${texts.joinToString("\n").take(800)}"
                        Log.i(TAG, "AIRI_RUNTIME READ_SCREEN pkg=$pkg nodes=${nodes.size} textItems=${texts.size}")
                        ToolResult.Success(summary)
                    }
                }
            }

            // ── App launch ─────────────────────────────────────────────────────
            "open_app" -> {
                val appName = args["app_name"] ?: return ToolResult.Error("Missing app_name")
                val result = AccessibilityCommandBridge.launchApp(appName)
                if (result.success) ToolResult.Success("Launched $appName")
                else ToolResult.Error(result.message ?: "")
            }

            // ── UI interaction ─────────────────────────────────────────────────
            "tap" -> {
                val target = args["target"] ?: return ToolResult.Error("Missing target")
                val result = AccessibilityCommandBridge.click(target)
                if (result.success) ToolResult.Success("Tapped: $target")
                else ToolResult.Error(result.message ?: "")
            }

            "type_text" -> {
                val text = args["text"] ?: return ToolResult.Error("Missing text")
                val result = AccessibilityCommandBridge.typeText(text)
                if (result.success) ToolResult.Success("Typed: ${text.take(60)}")
                else ToolResult.Error(result.message ?: "")
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
                val query = args["query"] ?: return ToolResult.Error("Missing query")
                val searchTool = SearchTool(context, braveApiKey = braveApiKeyProvider?.invoke())

                // Try Brave Search first (real web results + Jina content extraction)
                val braveKey = braveApiKeyProvider?.invoke()
                if (!braveKey.isNullOrBlank()) {
                    val brave = searchTool.searchBrave(query, count = 5, enrich = true)
                    if (brave.success) {
                        Log.i(TAG, "AIRI_RUNTIME WEB_SEARCH_BRAVE query=${query.take(60)} results=${brave.results.size} hasContent=${brave.topContent != null}")
                        return ToolResult.Success(brave.toAgentString())
                    }
                    Log.w(TAG, "Brave search failed (${brave.error}), falling back to DDG")
                }

                // Fallback: DDG Instant Answers (~30% coverage but always free)
                val ddg = searchTool.searchDuckDuckGo(query)
                if (ddg.success) {
                    Log.i(TAG, "AIRI_RUNTIME WEB_SEARCH_DDG query=${query.take(60)} resultLen=${ddg.summary.length}")
                    return ToolResult.Success(ddg.summary)
                }

                // Last resort: open browser (no content returned, but user can see it)
                Log.w(TAG, "AIRI_RUNTIME WEB_SEARCH_FALLBACK query=${query.take(60)} — opening browser")
                searchTool.searchViaIntent(query)
                ToolResult.Success(
                    "Search opened in browser for: $query\n\n" +
                    "Note: No API key configured for Brave Search. " +
                    "Add one in Settings → AI Models → Manage API Keys to enable full web search."
                )
            }

            "fetch_url" -> {
                val url = args["url"] ?: return ToolResult.Error("Missing url parameter")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return ToolResult.Error("Invalid URL — must start with http:// or https://")
                }
                val searchTool = SearchTool(context, braveApiKey = braveApiKeyProvider?.invoke())
                Log.i(TAG, "AIRI_RUNTIME FETCH_URL url=${url.take(80)}")

                // Try Jina Reader first (clean markdown, handles JS-rendered pages)
                val jina = searchTool.fetchViaJina(url, maxChars = 4000)
                if (jina.success && jina.content.isNotBlank()) {
                    Log.i(TAG, "AIRI_RUNTIME FETCH_URL_JINA_OK chars=${jina.content.length}")
                    return ToolResult.Success(
                        "Content from ${url}:\n\n${jina.content}"
                    )
                }

                // Fallback: direct HTTP fetch with HTML stripping
                val direct = searchTool.fetchPageContent(url)
                if (direct.success && direct.content.isNotBlank()) {
                    Log.i(TAG, "AIRI_RUNTIME FETCH_URL_DIRECT_OK chars=${direct.content.length}")
                    return ToolResult.Success("Content from ${url}:\n\n${direct.content}")
                }

                ToolResult.Error("Could not fetch content from: $url (tried Jina Reader and direct fetch)")
            }

            // ── Memory ─────────────────────────────────────────────────────────
            "memory_recall" -> {
                val query = args["query"] ?: return ToolResult.Error("Missing query")
                val manager = memoryManager
                if (manager == null) {
                    ToolResult.Error("Memory not available in this session")
                } else {
                    // P1-1: Use semantic search when embedding model is ready;
                    // fall back to recent messages when no embedding model is loaded.
                    val sessionId = sessionIdProvider?.invoke().orEmpty()
                    if (manager.isSemanticMemoryReady() && sessionId.isNotEmpty()) {
                        val ranked = manager.semanticSearch(sessionId, query, k = 5)
                        Log.i(TAG, "AIRI_RUNTIME MEMORY_RECALL mode=semantic query=${query.take(60)} hits=${ranked.size}")
                        if (ranked.isEmpty()) {
                            ToolResult.Success("No memories found for: $query")
                        } else {
                            val formatted = ranked.joinToString("\n") { item ->
                                "• ${item.message.content.take(200)}"
                            }
                            ToolResult.Success("Memory results (semantic):\n$formatted")
                        }
                    } else {
                        val recent = manager.getRecentMessages(5)
                        Log.i(TAG, "AIRI_RUNTIME MEMORY_RECALL mode=recent query=${query.take(60)} hits=${recent.size}")
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
                    Log.i(TAG, "AIRI_RUNTIME CALENDAR_READ days=$days events=${events.size}")
                    ToolResult.Success("Upcoming events:\n$formatted")
                }
            }

            "calendar_create" -> {
                val title       = args["title"] ?: return ToolResult.Error("Missing title")
                val startTime   = args["start_time"] ?: return ToolResult.Error("Missing start_time")
                val durationMin = args["duration_min"]?.toIntOrNull() ?: 60
                val cal         = CalendarTool(context)
                // Parse start_time: try ISO first, then natural language fallback
                val startMs = parseDateTime(startTime) ?: return ToolResult.Error("Could not parse start_time: $startTime")
                val endMs   = startMs + durationMin * 60_000L
                val eventId = cal.createEvent(title, startMs, endMs)
                if (eventId != null) {
                    Log.i(TAG, "AIRI_RUNTIME CALENDAR_CREATE title=${title.take(40)} startMs=$startMs id=$eventId")
                    ToolResult.Success("Created event: $title at $startTime")
                } else {
                    ToolResult.Error("Failed to create calendar event. Calendar permission may be missing.")
                }
            }

            // ── Alarm ──────────────────────────────────────────────────────────
            "set_alarm" -> {
                val time  = args["time"]  ?: return ToolResult.Error("Missing time")
                val label = args["label"] ?: "AIRI Alarm"
                val alarm = AlarmTool(context)
                val timePair = alarm.parseTime(time)
                    ?: return ToolResult.Error("Could not parse time '$time'. Use format like '7:30am' or '14:00'")
                val result = alarm.setAlarmViaIntent(timePair.first, timePair.second, label)
                if (result.success) {
                    Log.i(TAG, "AIRI_RUNTIME SET_ALARM time=$time label=$label")
                    ToolResult.Success("Alarm set for $time: $label")
                } else {
                    ToolResult.Error(result.message)
                }
            }

            // ── Notes ──────────────────────────────────────────────────────────
            "create_note" -> {
                val title   = args["title"]   ?: return ToolResult.Error("Missing title")
                val content = args["content"] ?: return ToolResult.Error("Missing content")
                val notes   = NotesTool(context)
                val note    = notes.createNote(title, content)
                Log.i(TAG, "AIRI_RUNTIME CREATE_NOTE title=${title.take(40)}")
                ToolResult.Success("Note created: ${note.title}")
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
                    Log.i(TAG, "AIRI_RUNTIME SKILL_TOOL_DISPATCH tool=$toolName")
                    val result = bridge.invoke(toolName, args)
                    ToolResult.Success(result)
                } else {
                    Log.w(TAG, "Unknown tool: $toolName")
                    ToolResult.Error("Unknown tool: $toolName. Available tools: ${BuiltinTools.ALL.map { it.name }.joinToString()}")
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
