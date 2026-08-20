package com.airi.assistant.agent.subagent.impl

import android.Manifest
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.tools.execution.AlarmTool
import com.airi.assistant.tools.execution.CalendarTool
import com.airi.assistant.tools.execution.NotesTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar

/**
 * ProductivityAgent — calendar, alarms, reminders, notes, and tasks.
 *
 * REAL EXECUTION: All tool calls invoke actual system APIs:
 *   - CalendarTool  → CalendarContract ContentProvider (READ_CALENDAR / WRITE_CALENDAR)
 *   - AlarmTool     → AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER intents
 *   - NotesTool     → JSON persistence in internal storage
 *
 * ─────────────────────────────────────────────────────────────────────────
 * LIFECYCLE SAFETY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   - Permission-checked before every tool call (no silent fallback).
 *   - Structured error propagation → AgentEvent.Failed(recoverable=true).
 *   - Cancellation-safe: all tool calls are suspend funs on Dispatchers.IO.
 */
class ProductivityAgent(
    private val calendarTool: CalendarTool,
    private val alarmTool:    AlarmTool,
    private val notesTool:    NotesTool
) : SubAgent {

    override val capability = SubAgentCapability(
        agentId      = "productivity_agent",
        displayName  = "Productivity Agent",
        description  = "Manage calendar events, alarms, reminders, and notes.",
        intentKeywords = listOf(
            "schedule", "meeting", "appointment", "calendar", "event",
            "remind", "reminder", "alarm", "timer", "todo", "task",
            "note", "write down", "draft", "tomorrow", "today",
            "next week", "at 3pm", "at noon", "add to",
            "create event", "set alarm", "set reminder",
            "what's on my calendar", "upcoming events", "today's events"
        ),
        domains            = listOf("productivity", "calendar", "tasks", "notes", "reminders"),
        requiredPermissions = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        ),
        accessesPrivateData = true,
        requiresCloud       = false,
        costTier            = SubAgentCapability.CostTier.LOW,
        latencyProfile      = SubAgentCapability.LatencyProfile.FAST,
        supportsBackground  = true,
        maxParallelSubTasks = 2,
        supportsResume      = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        val lower = input.lowercase()
        return PRODUCTIVITY_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "PRODUCTIVITY_AGENT_EXECUTE inputChars=${input.length}")

        emit(AgentEvent.Progress("Understanding your request…", 10, "parse"))

        val type = detectType(input.lowercase())
        emit(AgentEvent.Progress("Action type: ${type.displayName}", 20, "classify"))

        val events: Flow<AgentEvent> = when (type) {
            ProductivityType.CALENDAR_READ  -> executeCalendarRead(input, context)
            ProductivityType.CALENDAR_WRITE -> executeCalendarWrite(input, context)
            ProductivityType.ALARM_REMINDER -> executeAlarmTask(input)
            ProductivityType.TIMER          -> executeTimerTask(input)
            ProductivityType.NOTE           -> executeNoteTask(input)
            ProductivityType.TASK           -> executeTaskItem(input)
        }

        events.collect { event -> emit(event) }

        emit(AgentEvent.Complete(
            result     = "${type.displayName} handled.",
            durationMs = System.currentTimeMillis() - start,
            toolsUsed  = listOf(type.toolId)
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Calendar — Read
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeCalendarRead(input: String, context: SubAgentContext) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "calendar_tool",
            params    = mapOf("action" to "read", "input" to input),
            reasoning = "Reading calendar events from device"
        ))
        emit(AgentEvent.Progress("Reading your calendar…", 50, "read_calendar"))

        val lower = input.lowercase()
        val events = when {
            lower.contains("today") -> calendarTool.getTodayEvents()
            lower.contains("search") || lower.contains("find") -> {
                val query = extractSearchQuery(input)
                calendarTool.searchEvents(query)
            }
            else -> calendarTool.getUpcomingEvents(7)
        }

        val summary = calendarTool.summarize(events)
        emit(AgentEvent.Progress("Found ${events.size} event(s).", 90, "done"))
        emit(AgentEvent.PartialResult(summary, isFinal = true))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Calendar — Write (create event)
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeCalendarWrite(input: String, context: SubAgentContext) = flow<AgentEvent> {
        val title   = extractEventTitle(input)
        val startMs = extractEventTime(input) ?: defaultEventTime()

        emit(AgentEvent.ToolCall(
            toolName  = "calendar_tool",
            params    = mapOf("action" to "create", "title" to title, "input" to input),
            reasoning = "Creating calendar event: $title"
        ))
        emit(AgentEvent.Progress("Creating calendar event: '$title'…", 60, "create_event"))

        val eventId = calendarTool.createEvent(
            title       = title,
            startMs     = startMs,
            durationMs  = 60 * 60 * 1000L,
            description = "Created by AIRI"
        )

        if (eventId >= 0) {
            val formatted = android.text.format.DateFormat.format("EEE, MMM d 'at' h:mm a", startMs)
            emit(AgentEvent.PartialResult(
                "Calendar event created: \"$title\" on $formatted", isFinal = true
            ))
        } else {
            emit(AgentEvent.Failed(
                reason      = "Could not create calendar event — check Calendar permission.",
                recoverable = true
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Alarm
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeAlarmTask(input: String) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "alarm_tool",
            params    = mapOf("action" to "set_alarm", "input" to input),
            reasoning = "Setting alarm from natural language: $input"
        ))
        emit(AgentEvent.Progress("Setting alarm…", 60, "set_alarm"))

        val parsed = alarmTool.parseTime(input)
        if (parsed == null) {
            emit(AgentEvent.Failed(
                reason      = "Couldn't parse a time from \"$input\". Try: \"set alarm for 7am\".",
                recoverable = true
            ))
            return@flow
        }

        val (hour, minute) = parsed
        val label = extractLabel(input) ?: "AIRI Alarm"
        val result = alarmTool.setAlarmViaIntent(hour, minute, label)

        if (result.success) {
            emit(AgentEvent.PartialResult("Alarm set for ${result.label} — \"$label\"", isFinal = true))
        } else {
            emit(AgentEvent.Failed(reason = result.message, recoverable = true))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timer
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeTimerTask(input: String) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "alarm_tool",
            params    = mapOf("action" to "set_timer", "input" to input),
            reasoning = "Setting timer from natural language: $input"
        ))
        emit(AgentEvent.Progress("Setting timer…", 60, "set_timer"))

        val seconds = alarmTool.parseDuration(input)
        if (seconds == null || seconds <= 0) {
            emit(AgentEvent.Failed(
                reason      = "Couldn't parse a duration from \"$input\". Try: \"set timer for 10 minutes\".",
                recoverable = true
            ))
            return@flow
        }

        val label = extractLabel(input) ?: "AIRI Timer"
        val result = alarmTool.setTimerViaIntent(seconds, label)

        if (result.success) {
            emit(AgentEvent.PartialResult("Timer set for ${result.label} — \"$label\"", isFinal = true))
        } else {
            emit(AgentEvent.Failed(reason = result.message, recoverable = true))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Note
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeNoteTask(input: String) = flow<AgentEvent> {
        val (title, body) = extractTitleAndBody(input, "Note")

        emit(AgentEvent.ToolCall(
            toolName  = "notes_tool",
            params    = mapOf("action" to "create", "title" to title, "body" to body),
            reasoning = "Saving note: $title"
        ))
        emit(AgentEvent.Progress("Saving note…", 60, "save_note"))

        val note = notesTool.createNote(title = title, body = body)
        emit(AgentEvent.PartialResult(
            "Note saved: \"${note.title}\" (${note.formattedDate})", isFinal = true
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task (note with "task" tag)
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeTaskItem(input: String) = flow<AgentEvent> {
        val (title, body) = extractTitleAndBody(input, "Task")

        emit(AgentEvent.ToolCall(
            toolName  = "notes_tool",
            params    = mapOf("action" to "create_task", "title" to title),
            reasoning = "Adding task: $title"
        ))
        emit(AgentEvent.Progress("Adding task…", 60, "add_task"))

        val note = notesTool.createNote(title = title, body = body, tags = listOf("task"))
        emit(AgentEvent.PartialResult("Task added: \"${note.title}\"", isFinal = true))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectType(lower: String): ProductivityType = when {
        lower.contains("timer")                                    -> ProductivityType.TIMER
        lower.contains("alarm") || lower.contains("remind")       -> ProductivityType.ALARM_REMINDER
        lower.contains("note") || lower.contains("write down") ||
        lower.contains("jot")                                     -> ProductivityType.NOTE
        lower.contains("todo") || lower.contains("task")          -> ProductivityType.TASK
        lower.contains("what") || lower.contains("upcoming") ||
        lower.contains("today's") || lower.contains("search") ||
        lower.contains("find")                                    -> ProductivityType.CALENDAR_READ
        else                                                       -> ProductivityType.CALENDAR_WRITE
    }

    private fun extractEventTitle(input: String): String {
        val stripped = input
            .replace(Regex("(?i)(schedule|create|add|new|book)\\s*(a\\s*)?(meeting|event|appointment)?"), "")
            .replace(Regex("(?i)for\\s+\\d{1,2}(:\\d{2})?(am|pm)?"), "")
            .replace(Regex("(?i)(at|on|next|this)\\s+\\w+"), "")
            .trim()
            .trimEnd(',', '.')
        return stripped.ifBlank { "Meeting" }.take(100)
    }

    private fun extractEventTime(input: String): Long? {
        val parsed = alarmTool.parseTime(input) ?: return null
        val (hour, minute) = parsed
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DATE, 1)
        }
        return cal.timeInMillis
    }

    private fun defaultEventTime(): Long =
        System.currentTimeMillis() + 60 * 60 * 1000L   // 1 hour from now

    private fun extractLabel(input: String): String? {
        val match = Regex("""(?i)(?:for|named?|called?|label(?:led)?)\s+["']?(.+?)["']?$""")
            .find(input)
        return match?.groupValues?.getOrNull(1)?.trim()?.take(50)
    }

    private fun extractSearchQuery(input: String): String {
        val stop = setOf("search", "find", "look up", "for", "about", "events")
        return input.lowercase().split(" ")
            .filterNot { it in stop }
            .joinToString(" ")
            .trim()
            .take(80)
    }

    /** Split input into a (title, body) pair for notes/tasks. */
    private fun extractTitleAndBody(input: String, fallbackTitle: String): Pair<String, String> {
        val cleaned = input
            .replace(Regex("(?i)(note|write down|jot|task|todo|add a?|remember to|note that)"), "")
            .trim()
        return if (cleaned.length > 60) {
            val title = cleaned.take(50).trimEnd() + "…"
            title to cleaned
        } else {
            (cleaned.ifBlank { fallbackTitle }) to cleaned
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data
    // ─────────────────────────────────────────────────────────────────────────

    private enum class ProductivityType(val displayName: String, val toolId: String) {
        CALENDAR_READ("Calendar Read",   "calendar_tool"),
        CALENDAR_WRITE("Calendar Write", "calendar_tool"),
        ALARM_REMINDER("Alarm",          "alarm_tool"),
        TIMER("Timer",                   "alarm_tool"),
        NOTE("Note",                     "notes_tool"),
        TASK("Task",                     "notes_tool")
    }

    companion object {
        private const val TAG = "ProductivityAgent"

        private val PRODUCTIVITY_SIGNALS = listOf(
            "schedule", "meeting", "appointment", "calendar", "remind me",
            "set alarm", "set reminder", "set timer", "todo", "task",
            "note down", "write down", "jot", "upcoming", "today's events",
            "what's on my calendar", "alarm for", "timer for"
        )
    }
}
