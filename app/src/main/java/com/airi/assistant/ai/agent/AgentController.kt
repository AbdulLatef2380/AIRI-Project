package com.airi.assistant.ai.agent

import android.content.Context
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillExecutor
import com.airi.assistant.memory.entity.ChatMessage

/**
 * AgentController — legacy skill-dispatch controller.
 *
 *  change: [TaskPlanner] and [TaskExecutor] were removed (zero external
 * callers, delegation shells). The multi-step task planning path (Step 2) has
 * been removed. Only the real SkillExecutor path (Step 1) remains — this handles
 * GitHub, Telegram, Gmail, Calendar, Google Drive skill calls.
 *
 * If no skill matches, returns null → LLM fallback.
 */
class AgentController(private val context: Context) {

    private val skillExecutor = SkillExecutor(context)
    private val traceManager  = AgentTraceManager.instance

    suspend fun handle(
        input: String,
        recentHistory: List<ChatMessage> = emptyList()
    ): AgentResult? {
        val skillContext = buildSkillContext(recentHistory)
        val traceId = traceManager.startTrace(input)

        // ── Step 1: Scored skill matching ─────────────────────────────────────
        val stepStart = System.currentTimeMillis()
        val skillResult = skillExecutor.tryHandle(input, skillContext)
        if (skillResult != null) {
            traceManager.addStep(
                traceId,
                AgentStep(
                    stepIndex     = 0,
                    type          = AgentStepType.SKILL,
                    name          = skillResult.skillName ?: "unknown_skill",
                    inputParams   = mapOf("query" to input.take(120)),
                    outputSummary = if (skillResult.success) skillResult.data.take(200) else "",
                    success       = skillResult.success,
                    error         = skillResult.error,
                    durationMs    = System.currentTimeMillis() - stepStart
                )
            )
            val text = if (skillResult.success) skillResult.data
                       else skillResult.error ?: "Skill execution failed."
            traceManager.finalizeTrace(traceId, text.take(200), skillResult.success)

            return AgentResult(
                text     = text,
                agentTag = skillResult.skillName?.let { friendlySkillName(it) },
                success  = skillResult.success,
                traceId  = traceId
            )
        }

        // ── Step 2: No agent match — LLM handles ──────────────────────────────
        // TaskPlanner/TaskExecutor removed in  (zero external callers).
        traceManager.finalizeTrace(traceId, "LLM fallback", false)
        return null
    }

    fun getSkillExecutor(): SkillExecutor = skillExecutor

    private fun buildSkillContext(history: List<ChatMessage>): SkillContext {
        val recent = history.takeLast(10)
        return SkillContext(
            lastMessages         = recent.map { it.content },
            lastAssistantMessage = recent.lastOrNull { it.role == "assistant" }?.content,
            lastUsedSkill        = null,
            userIntentHint       = null
        )
    }

    private fun friendlySkillName(name: String): String = when (name) {
        "github_guardian"    -> "GitHub"
        "telegram_messenger" -> "Telegram"
        "gmail_assistant"    -> "Gmail"
        "drive_search"       -> "Google Drive"
        "calendar_events"    -> "Calendar"
        else -> name.replace("_", " ").split(" ")
                    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }
}
