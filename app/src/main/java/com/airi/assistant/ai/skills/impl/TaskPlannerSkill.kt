package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef

class TaskPlannerSkill(private val context: Context) : AiriSkill {

    override val skillId    = "task_planner"
    override val name       = "task_planner"
    override val description = "Break down complex goals into actionable step-by-step plans with priorities and timelines"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "PRODUCTIVITY"
    override val iconEmoji  = "📋"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.READ_WRITE
    override val modelAccess  = SkillModelAccess.CHAT

    override val parameters = mapOf(
        "goal"     to "string — the goal or project to plan",
        "context"  to "string (optional) — background context or constraints",
        "deadline" to "string (optional) — target completion date"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "plan_tasks",
            description = "Create a detailed step-by-step action plan for achieving a goal",
            parameters  = mapOf(
                "goal"     to SkillParamDef("string", "The goal or project to plan", required = true),
                "deadline" to SkillParamDef("string", "Target completion date (optional)", required = false)
            )
        )
    )

    private val planKeywords = listOf(
        "plan", "planning", "roadmap", "steps to", "how to achieve",
        "break down", "organize", "schedule", "project plan", "action items",
        "tasks for", "strategy for", "goal", "milestone", "checklist"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        if (lower.contains("plan") || lower.contains("roadmap")) score += 35
        planKeywords.forEach { kw -> if (lower.contains(kw)) score += 10 }
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start = System.currentTimeMillis()
        val skillCtx = params["context"] as? SkillContext
        val modelBridge = skillCtx?.modelBridge
            ?: return SkillResult(
                false, "",
                "Task planning requires an active AI model. Load a model in Settings → AI Models.",
                skillId
            )

        val input    = params["input"] as? String ?: ""
        val goal     = params["goal"] as? String ?: input
        val deadline = params["deadline"] as? String

        if (goal.isBlank()) {
            return SkillResult(false, "", "Please describe the goal or project you want to plan.", skillId)
        }

        val systemPrompt = """You are a world-class project manager and productivity expert.
Create clear, actionable, realistic plans.
Break down complex goals into specific, measurable steps.
Consider dependencies, risks, and realistic timeframes.
Format output as a numbered list with clear descriptions."""

        val prompt = buildString {
            append("Create a detailed step-by-step action plan for the following goal:\n\n")
            append("Goal: $goal\n")
            if (!deadline.isNullOrBlank()) append("Deadline: $deadline\n")
            append("""
                
Please provide:
1. An overview of the approach
2. A numbered list of concrete action steps (with estimated time per step if possible)
3. Key milestones
4. Potential challenges and how to address them
5. First 3 immediate actions to take today""".trimIndent())
        }

        return try {
            val plan = modelBridge.complete(prompt, systemPrompt, maxTokens = 2048)
            SkillResult(
                success     = true,
                data        = plan,
                skillName   = skillId,
                executionMs = System.currentTimeMillis() - start,
                metadata    = mapOf("goal" to goal.take(80))
            )
        } catch (e: Exception) {
            SkillResult(false, "", "Task planning failed: ${e.message}", skillId)
        }
    }
}
