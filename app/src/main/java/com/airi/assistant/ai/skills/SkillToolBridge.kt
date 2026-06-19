package com.airi.assistant.ai.skills

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.loop.tool.ToolSchema
import kotlinx.coroutines.withTimeout

/**
 * SkillToolBridge — makes every registered [AiriSkill] available as a named tool
 * that the [com.airi.assistant.agent.loop.AgentLoop] can invoke by name.
 *
 * Each skill's [AiriSkill.toolDefinitions] are mapped to [ToolSchema] entries
 * prefixed with `skill_`. When the agent emits a `skill_<name>` tool_call,
 * [invoke] routes it to the correct skill and returns the output string.
 *
 * This bridges the two execution paths:
 *  1. Direct skill routing: [SkillExecutor.tryHandle] — for conversation-level matching
 *  2. Agent loop tool call: ToolDispatcher → SkillToolBridge.invoke — for explicit tool_call JSON
 */
class SkillToolBridge(
    private val context:      Context,
    private val registry:     SkillRegistry,
    private val modelBridge:  SkillModelBridge? = null,
    private val skillCtx:     () -> SkillContext = { SkillContext() }
) {
    companion object {
        private const val TAG    = "SkillToolBridge"
        private const val PREFIX = "skill_"
        private const val TIMEOUT_MS = 30_000L
    }

    // ── Schema generation ─────────────────────────────────────────────────────

    /**
     * Convert all enabled skills' toolDefinitions into [ToolSchema] entries
     * the [AgentLoop] injects into the system prompt.
     */
    fun asToolSchemas(): List<ToolSchema> {
        val schemas = mutableListOf<ToolSchema>()
        registry.getAvailableSkills().forEach { skill ->
            if (skill.toolDefinitions.isEmpty()) {
                schemas.add(skillToSchema(skill))
            } else {
                skill.toolDefinitions.forEach { toolDef ->
                    schemas.add(toolDefToSchema(skill, toolDef))
                }
            }
        }
        return schemas
    }

    private fun skillToSchema(skill: AiriSkill): ToolSchema = ToolSchema(
        name        = "$PREFIX${skill.skillId}",
        description = "[Skill] ${skill.description}",
        parameters  = skill.parameters.mapValues { (_, desc) ->
            ToolSchema.Param(type = "string", description = desc, required = false)
        },
        category    = ToolSchema.Category.EXTERNAL
    )

    private fun toolDefToSchema(skill: AiriSkill, toolDef: SkillToolDefinition): ToolSchema = ToolSchema(
        name        = "$PREFIX${toolDef.name}",
        description = "[${skill.name}] ${toolDef.description}",
        parameters  = toolDef.parameters.mapValues { (_, p) ->
            ToolSchema.Param(type = p.type, description = p.description, required = p.required)
        },
        dangerous   = toolDef.dangerous,
        category    = ToolSchema.Category.EXTERNAL
    )

    // ── Invocation ────────────────────────────────────────────────────────────

    /**
     * Invoke a skill by the tool name the agent emitted.
     *
     * @param toolName  The raw tool name (e.g. "skill_web_search" or "skill_translate_text").
     * @param args      Arguments the agent provided.
     * @return          The tool result string for the agent loop.
     */
    suspend fun invoke(toolName: String, args: Map<String, String>): String {
        val strippedName = toolName.removePrefix(PREFIX)

        val skill = findSkillForTool(strippedName)
            ?: return "No skill found for tool: $toolName. Available skill tools: ${asToolSchemas().map { it.name }.joinToString()}"

        Log.i(TAG, "Invoking skill '${skill.skillId}' via tool '$toolName' args=${args.keys}")

        // Phase I enforcement: only pass the model bridge to skills that declared model access
        val effectiveBridge = if (skill.modelAccess != SkillModelAccess.NONE) modelBridge else null
        if (modelBridge != null && skill.modelAccess == SkillModelAccess.NONE) {
            Log.d(TAG, "Model bridge suppressed for '${skill.skillId}' — declared modelAccess=NONE")
        }

        val params: Map<String, Any> = buildMap {
            put("input", args["input"] ?: args["query"] ?: args.values.firstOrNull() ?: "")
            putAll(args)
            put("context", skillCtx().copy(modelBridge = effectiveBridge))
        }

        return try {
            val result = withTimeout(TIMEOUT_MS) { skill.execute(params) }
            if (result.success) {
                result.data
            } else {
                "Skill '${skill.name}' error: ${result.error ?: "Unknown error"}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Skill invocation failed for '$toolName': ${e.message}")
            "Skill '${skill.name}' failed: ${e.message ?: "Unexpected error"}"
        }
    }

    /**
     * Check whether a tool name belongs to this bridge (starts with "skill_").
     */
    fun handles(toolName: String): Boolean = toolName.startsWith(PREFIX)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findSkillForTool(strippedName: String): AiriSkill? {
        val skills = registry.getAvailableSkills()

        skills.firstOrNull { skill ->
            skill.skillId == strippedName || skill.name == strippedName
        }?.let { return it }

        skills.firstOrNull { skill ->
            skill.toolDefinitions.any { it.name == strippedName }
        }?.let { return it }

        return null
    }
}
