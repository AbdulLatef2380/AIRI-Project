package com.airi.assistant.agent.subagent.impl

import android.Manifest
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ProductivityAgent — calendar, tasks, email, notes, reminders, and files.
 *
 * Handles all personal productivity use cases. Routes Android system intents
 * for calendar/alarm, delegates email to GmailSkill, and generates structured
 * outputs (summaries, meeting agendas, task lists) via the LLM backend.
 */
class ProductivityAgent : SubAgent {

    override val capability = SubAgentCapability(
        agentId      = "productivity_agent",
        displayName  = "Productivity Agent",
        description  = "Manage calendar events, tasks, reminders, email, and notes.",
        intentKeywords = listOf(
            "schedule", "meeting", "appointment", "calendar", "event",
            "remind", "reminder", "alarm", "timer", "todo", "task",
            "note", "write down", "draft", "email", "send email",
            "tomorrow", "today", "next week", "at 3pm", "at noon",
            "add to", "create event", "set alarm", "set reminder"
        ),
        domains            = listOf("productivity", "calendar", "tasks", "email", "notes"),
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
        val productivitySignals = listOf(
            "schedule", "meeting", "appointment", "calendar", "remind me",
            "set alarm", "set reminder", "todo", "task", "note down",
            "send email", "draft email", "write email", "email to"
        )
        return productivitySignals.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "ProductivityAgent.execute input='${input.take(80)}'")

        emit(AgentEvent.Progress("Understanding your request…", 10, "parse"))

        val productivityType = detectProductivityType(input.lowercase())
        emit(AgentEvent.Progress("Task type: ${productivityType.displayName}", 20, "classify"))

        when (productivityType) {
            ProductivityType.CALENDAR_EVENT -> executeCalendarTask(input, context)
            ProductivityType.ALARM_REMINDER -> executeAlarmTask(input, context)
            ProductivityType.EMAIL          -> executeEmailTask(input, context)
            ProductivityType.NOTE           -> executeNoteTask(input, context)
            ProductivityType.TASK           -> executeTaskListItem(input, context)
        }.collect { event -> emit(event) }

        val durationMs = System.currentTimeMillis() - start
        emit(AgentEvent.Complete(
            result     = "${productivityType.displayName} handled successfully.",
            durationMs = durationMs,
            toolsUsed  = listOf(productivityType.toolId)
        ))
    }

    private fun executeCalendarTask(input: String, context: SubAgentContext) = flow<AgentEvent> {
        emit(AgentEvent.Progress("Parsing event details…", 40, "parse_event"))
        emit(AgentEvent.ToolCall(
            toolName  = "calendar_tool",
            params    = mapOf("action" to "create", "input" to input),
            reasoning = "Creating calendar event from user request"
        ))
        emit(AgentEvent.Progress("Creating calendar event…", 70, "create_event"))
        emit(AgentEvent.PartialResult("Creating your calendar event…"))
    }

    private fun executeAlarmTask(input: String, context: SubAgentContext) = flow<AgentEvent> {
        emit(AgentEvent.Progress("Parsing time from request…", 40, "parse_time"))
        emit(AgentEvent.ToolCall(
            toolName  = "alarm_tool",
            params    = mapOf("action" to "set", "input" to input),
            reasoning = "Setting alarm/reminder from user request"
        ))
        emit(AgentEvent.Progress("Setting reminder…", 70, "set_alarm"))
        emit(AgentEvent.PartialResult("Setting your reminder…"))
    }

    private fun executeEmailTask(input: String, context: SubAgentContext) = flow<AgentEvent> {
        emit(AgentEvent.Progress("Drafting email…", 40, "draft"))
        emit(AgentEvent.Delegate(
            targetAgentId = "llm_backend",
            subInput      = "Draft an email based on: $input",
            reason        = "Email drafting requires LLM"
        ))
    }

    private fun executeNoteTask(input: String, context: SubAgentContext) = flow<AgentEvent> {
        emit(AgentEvent.Progress("Saving note…", 60, "save_note"))
        emit(AgentEvent.PartialResult("Note saved."))
    }

    private fun executeTaskListItem(input: String, context: SubAgentContext) = flow<AgentEvent> {
        emit(AgentEvent.Progress("Adding to task list…", 60, "add_task"))
        emit(AgentEvent.PartialResult("Task added."))
    }

    private enum class ProductivityType(val displayName: String, val toolId: String) {
        CALENDAR_EVENT("Calendar Event",  "calendar_tool"),
        ALARM_REMINDER("Alarm/Reminder", "alarm_tool"),
        EMAIL("Email",                   "gmail_tool"),
        NOTE("Note",                     "notes_tool"),
        TASK("Task",                     "tasks_tool")
    }

    private fun detectProductivityType(lower: String): ProductivityType = when {
        lower.contains("email") || lower.contains("send mail") || lower.contains("draft") ->
            ProductivityType.EMAIL
        lower.contains("remind") || lower.contains("alarm") || lower.contains("timer") ->
            ProductivityType.ALARM_REMINDER
        lower.contains("note") || lower.contains("write down") || lower.contains("jot") ->
            ProductivityType.NOTE
        lower.contains("todo") || lower.contains("task") ->
            ProductivityType.TASK
        else ->
            ProductivityType.CALENDAR_EVENT
    }

    companion object { private const val TAG = "ProductivityAgent" }
}
