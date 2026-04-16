package com.airi.assistant.ai.agent

import android.content.Context
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillExecutor
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.memory.entity.ChatMessage

class AgentController(private val context: Context) {

    private val skillExecutor = SkillExecutor(context)
    private val taskPlanner   = TaskPlanner(SecureStorage(context))
    private val taskExecutor  = TaskExecutor(context)
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
                    stepIndex    = 0,
                    type         = AgentStepType.SKILL,
                    name         = skillResult.skillName ?: "unknown_skill",
                    inputParams  = mapOf("query" to input.take(120)),
                    outputSummary = if (skillResult.success) skillResult.data.take(200) else "",
                    success      = skillResult.success,
                    error        = skillResult.error,
                    durationMs   = System.currentTimeMillis() - stepStart
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

        // ── Step 2: Multi-step task planner ───────────────────────────────────
        val task = taskPlanner.plan(input, skillContext)
        if (task != null && task.steps.isNotEmpty()) {
            val result = taskExecutor.execute(task, traceId)
            traceManager.finalizeTrace(traceId, result.summary.take(200), result.success)

            return AgentResult(
                text     = result.summary,
                agentTag = "Agent Task",
                success  = result.success,
                traceId  = traceId
            )
        }

        // ── Step 3: No agent match — close trace, let LLM handle ──────────────
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
