package com.airi.assistant.ai.skills

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.loop.tool.ToolSchema
import kotlinx.coroutines.TimeoutCancellationException
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
 *
 * ── Execution Instrumentation ──────────────────────────────────────────────────
 * Every skill invocation emits AIRI_RUNTIME log entries so execution can be verified
 * from logcat without needing to attach a debugger:
 *
 *   AIRI_RUNTIME SKILL_INVOKE  — tool called, skill matched, context state logged
 *   AIRI_RUNTIME SKILL_SUCCESS — execution returned success, result size logged
 *   AIRI_RUNTIME SKILL_ERROR   — skill returned success=false, error logged
 *   AIRI_RUNTIME SKILL_TIMEOUT — 30s execution budget exceeded
 *   AIRI_RUNTIME SKILL_EXCEPTION — unexpected exception during execute()
 *   AIRI_RUNTIME SKILL_NO_MATCH — no registered skill found for tool name
 */
class SkillToolBridge(
    private val context:      Context,
    private val registry:     SkillRegistry,
    private val modelBridge:  SkillModelBridge? = null,
    private val skillCtx:     () -> SkillContext = { SkillContext() }
) {
    companion object {
        private const val TAG        = "SkillToolBridge"
        private const val PREFIX     = "skill_"
        private const val TIMEOUT_MS = 30_000L
    }

    // ── Schema generation ─────────────────────────────────────────────────────

    /**
     * Convert all enabled skills' toolDefinitions into [ToolSchema] entries
     * the [AgentLoop] injects into the system prompt.
     */
    fun asToolSchemas(): List<ToolSchema> {
        val skills = registry.getAvailableSkills()
        val schemas = mutableListOf<ToolSchema>()
        skills.forEach { skill ->
            if (skill.toolDefinitions.isEmpty()) {
                schemas.add(skillToSchema(skill))
            } else {
                skill.toolDefinitions.forEach { toolDef ->
                    schemas.add(toolDefToSchema(skill, toolDef))
                }
            }
        }
        Log.d(TAG, "AIRI_RUNTIME SKILL_SCHEMAS_BUILT count=${schemas.size} names=${schemas.map { it.name }.joinToString()}")
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
        val invokeStart  = System.currentTimeMillis()
        val strippedName = toolName.removePrefix(PREFIX)

        val skill = findSkillForTool(strippedName)
        if (skill == null) {
            val available = asToolSchemas().map { it.name }.joinToString()
            Log.w(TAG, "AIRI_RUNTIME SKILL_NO_MATCH toolName=$toolName stripped=$strippedName available=[$available]")
            return "No skill found for tool: $toolName. Available skill tools: $available"
        }

        // Phase I enforcement: only pass the model bridge to skills that declared model access
        val effectiveBridge = if (skill.modelAccess != SkillModelAccess.NONE) modelBridge else null
        if (modelBridge != null && skill.modelAccess == SkillModelAccess.NONE) {
            Log.d(TAG, "AIRI_RUNTIME SKILL_BRIDGE_SUPPRESSED skillId=${skill.skillId} — modelAccess=NONE")
        }

        val skillCtxInstance = skillCtx().copy(modelBridge = effectiveBridge)

        Log.i(TAG, "AIRI_RUNTIME SKILL_INVOKE " +
            "tool=$toolName " +
            "skillId=${skill.skillId} " +
            "argKeys=${args.keys.joinToString()} " +
            "modelAccess=${skill.modelAccess} " +
            "modelBridgeActive=${effectiveBridge != null} " +
            "memoryActive=${skillCtxInstance.memoryManager != null} " +
            "sessionId=${skillCtxInstance.sessionId.take(12).ifBlank { "NONE" }}")

        val params: Map<String, Any> = buildMap {
            put("input", args["input"] ?: args["query"] ?: args.values.firstOrNull() ?: "")
            putAll(args)
            put("context", skillCtxInstance)
        }

        return try {
            val result   = withTimeout(TIMEOUT_MS) { skill.execute(params) }
            val elapsedMs = System.currentTimeMillis() - invokeStart

            if (result.success) {
                Log.i(TAG, "AIRI_RUNTIME SKILL_SUCCESS " +
                    "tool=$toolName " +
                    "skillId=${skill.skillId} " +
                    "ms=$elapsedMs " +
                    "resultLen=${result.data.length} " +
                    "toolOutputs=${result.toolOutputs.size} " +
                    "metadataCount=${result.metadata.size}")
                result.data
            } else {
                Log.w(TAG, "AIRI_RUNTIME SKILL_ERROR " +
                    "tool=$toolName " +
                    "skillId=${skill.skillId} " +
                    "ms=$elapsedMs " +
                    "errorChars=${result.error?.length ?: 0}")
                "Skill '${skill.name}' error: ${result.error ?: "Unknown error"}"
            }

        } catch (e: TimeoutCancellationException) {
            val elapsedMs = System.currentTimeMillis() - invokeStart
            Log.e(TAG, "AIRI_RUNTIME SKILL_TIMEOUT " +
                "tool=$toolName " +
                "skillId=${skill.skillId} " +
                "limitMs=$TIMEOUT_MS " +
                "elapsedMs=$elapsedMs")
            "Skill '${skill.name}' timed out after ${TIMEOUT_MS / 1000}s. Try a simpler request."

        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - invokeStart
            Log.e(TAG, "AIRI_RUNTIME SKILL_EXCEPTION " +
                "tool=$toolName " +
                "skillId=${skill.skillId} " +
                "ms=$elapsedMs " +
                "errorType=${e.javaClass.simpleName}")
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

        // 1. Direct skillId or name match (e.g. "web_search" → WebSearchSkill.skillId)
        skills.firstOrNull { skill ->
            skill.skillId == strippedName || skill.name == strippedName
        }?.let { return it }

        // 2. Tool definition name match (e.g. "code_assist" → CodeAssistantSkill.toolDefinitions[0])
        skills.firstOrNull { skill ->
            skill.toolDefinitions.any { it.name == strippedName }
        }?.let { return it }

        return null
    }
}
