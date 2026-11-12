package com.airi.assistant.ai.skills

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.agent.loop.tool.TypedArgumentAdapters
import com.airi.assistant.domain.tool.TypedArgumentContract
import com.airi.assistant.domain.permission.PermissionService
import com.airi.assistant.security.UntrustedContentEnvelope
import kotlinx.coroutines.CancellationException
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
 * Every skill invocation emits AIRI log entries so execution can be verified
 * from logcat without needing to attach a debugger:
 *
 *   AIRI SKILL_INVOKE  — tool called, skill matched, context state logged
 *   AIRI SKILL_SUCCESS — execution returned success, result size logged
 *   AIRI SKILL_ERROR   — skill returned success=false, error logged
 *   AIRI SKILL_TIMEOUT — 30s execution budget exceeded
 *   AIRI SKILL_EXCEPTION — unexpected exception during execute()
 *   AIRI SKILL_NO_MATCH — no registered skill found for tool name
 */
class SkillToolBridge(
    private val context:      Context,
    private val registry: SkillRegistry,
    private val modelBridge: SkillModelBridge? = null,
    private val skillCtx: () -> SkillContext = { SkillContext() },
    // A bridge without an injected connector registry must not assume that
    // external dependencies are available; callers can opt in with live state.
    private val isConnectorHealthy: (String) -> Boolean = { false },
    private val readinessCtx: () -> SkillContext = skillCtx,
) {
    private val permissionService = PermissionService(context.applicationContext)
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
    fun asToolSchemas(snapshot: SkillCatalogSnapshot? = null): List<ToolSchema> {
        // Registration/discovery is deliberately separate from authorization.
        // Expose registered skills with a truthful readiness marker; invoke()
        // applies the same policy again and remains fail-closed.
        val skills = snapshot?.skills ?: registry.getRegisteredSkillsForRouting()
        val schemas = mutableListOf<ToolSchema>()
        skills.forEach { skill ->
            val status = when (val decision = readiness(skill)) {
                is SkillInvocationAccessPolicy.Decision.Allow -> null
                is SkillInvocationAccessPolicy.Decision.Deny ->
                    " [READINESS=requires_${decision.reason.name.lowercase()}: ${decision.userMessage}]"
            }
            if (skill.toolDefinitions.isEmpty()) {
                schemas.add(skillToSchema(skill, status))
            } else {
                skill.toolDefinitions.forEach { toolDef ->
                    schemas.add(toolDefToSchema(skill, toolDef, status))
                }
            }
        }
        val uniqueSchemas = schemas.distinctBy { it.name }
        Log.d(TAG, "AIRI SKILL_SCHEMAS_BUILT count=${uniqueSchemas.size} names=${uniqueSchemas.map { it.name }.joinToString()}")
        return uniqueSchemas
    }

    /** Uses the same permission, connector, memory, and model gates as invocation. */
    fun readiness(skill: AiriSkill): SkillInvocationAccessPolicy.Decision {
        val providedContext = readinessCtx()
        val requestedContext = providedContext.copy(modelBridge = modelBridge ?: providedContext.modelBridge)
        return SkillInvocationAccessPolicy.authorize(
            skill = skill,
            context = requestedContext,
            hasPermission = permissionService::hasPermission,
            isConnectorHealthy = isConnectorHealthy,
        )
    }

    private fun skillToSchema(skill: AiriSkill, status: String? = null): ToolSchema = ToolSchema(
        name        = "$PREFIX${skill.skillId}",
        description = "[Skill] " + modelFacingDescription(skill, skill.name + ": " + skill.description) + status.orEmpty(),
        parameters  = skill.parameters.mapValues { (_, desc) ->
            ToolSchema.Param(type = "string", description = desc, required = false)
        },
        category    = ToolSchema.Category.EXTERNAL
    )

    private fun toolDefToSchema(skill: AiriSkill, toolDef: SkillToolDefinition, status: String? = null): ToolSchema = ToolSchema(
        // Namespace definitions by skill. The old name made duplicate actions
        // resolve to whichever skill happened to be registered first.
        name        = "$PREFIX${skill.skillId}_${toolDef.name}",
        description = modelFacingDescription(skill, skill.name + ": " + toolDef.description) + status.orEmpty(),
        parameters  = toolDef.parameters.mapValues { (_, p) ->
            ToolSchema.Param(type = p.type, description = p.description, required = p.required, nullable = p.nullable, defaultValue = p.defaultValue, minLength = p.minLength, maxLength = p.maxLength, minInt = p.minInt, maxInt = p.maxInt, minNumber = p.minNumber, maxNumber = p.maxNumber, allowedValues = p.allowedValues, items = p.items?.let { item -> ToolSchema.Param(item.type, item.description, item.required) }, properties = p.properties.mapValues { (_, child) -> ToolSchema.Param(child.type, child.description, child.required) })
        },
        dangerous   = toolDef.dangerous,
        category    = ToolSchema.Category.EXTERNAL
    )

    // ── Invocation ────────────────────────────────────────────────────────────

    /**
     * Invoke a skill by the tool name the agent emitted.
     *
     * @param toolName  The raw tool name (e.g. "skill_web_search_search" or
     *                  "skill_translate_translate_text").
     * @param args      Arguments the agent provided.
     * @return          The verified, structured skill result for the agent loop.
     */
    suspend fun invoke(
        toolName: String,
        args: Map<String, String>,
        executionId: String? = null,
    ): SkillResult {
        val invokeStart  = System.currentTimeMillis()
        val strippedName = toolName.removePrefix(PREFIX)

        val skill = findSkillForTool(strippedName)
        if (skill == null) {
            val available = asToolSchemas().map { it.name }.joinToString()
            Log.w(TAG, "AIRI SKILL_NO_MATCH toolName=$toolName stripped=$strippedName available=[$available]")
            return SkillResult(
                success = false,
                data = "",
                error = "No skill found for tool: $toolName. Available skill tools: $available",
                skillName = strippedName,
                metadata = mapOf("failure_type" to "skill_not_found")
            )
        }

        val providedContext = skillCtx()
        val requestedContext = providedContext.copy(
            modelBridge = modelBridge ?: providedContext.modelBridge,
            executionId = executionId?.takeIf(String::isNotBlank) ?: providedContext.executionId,
        )
        val accessDecision = SkillInvocationAccessPolicy.authorize(
            skill = skill,
            context = requestedContext,
            hasPermission = permissionService::hasPermission,
            isConnectorHealthy = isConnectorHealthy
        )
        val skillCtxInstance = when (accessDecision) {
            is SkillInvocationAccessPolicy.Decision.Allow -> accessDecision.context
            is SkillInvocationAccessPolicy.Decision.Deny -> {
                Log.w(TAG, "AIRI SKILL_DENIED tool=$toolName skillId=${skill.skillId} reason=${accessDecision.reason}")
                return SkillResult(
                    success = false,
                    data = "",
                    error = accessDecision.userMessage,
                    skillName = skill.skillId,
                    metadata = mapOf(
                        "failure_type" to "authorization",
                        "deny_reason" to accessDecision.reason.name,
                        "error_code" to when (accessDecision.reason) {
                            SkillInvocationAccessPolicy.DenyReason.CONNECTOR_UNHEALTHY -> "auth_required"
                            SkillInvocationAccessPolicy.DenyReason.MISSING_PERMISSION -> "permission_denied"
                            else -> "permission_denied"
                        },
                    )
                )
            }
        }
        if (modelBridge != null && skillCtxInstance.modelBridge == null) {
            Log.d(TAG, "AIRI SKILL_BRIDGE_SUPPRESSED skillId=${skill.skillId} — modelAccess=NONE")
        }

        val definition = skill.toolDefinitions.firstOrNull { definition ->
            toolName == "$PREFIX${skill.skillId}_${definition.name}" || toolName == "$PREFIX${definition.name}"
        }
        val argumentSpecs = definition?.parameters
            ?.let(TypedArgumentAdapters::fromSkill)
            ?: skill.parameters.map { (name, description) ->
                com.airi.assistant.domain.tool.TypedArgumentSpec(name, com.airi.assistant.domain.tool.TypedArgumentSpec.Type.STRING, required = false, description = description)
            }
        val typed = TypedArgumentContract.validate(argumentSpecs, args)
        if (!typed.isValid) {
            val failure = typed.failure!!
            return SkillResult(false, "", "${failure.message} ${failure.repairHint}", skillName = skill.skillId, metadata = mapOf("failure_type" to "invalid_argument", "error_code" to failure.code))
        }

        Log.i(TAG, "AIRI SKILL_INVOKE " +
            "tool=$toolName " +
            "skillId=${skill.skillId} " +
            "argKeys=${args.keys.joinToString()} " +
            "modelAccess=${skill.modelAccess} " +
            "modelBridgeActive=${skillCtxInstance.modelBridge != null} " +
            "memoryActive=${skillCtxInstance.memoryManager != null} " +
            "sessionId=${skillCtxInstance.sessionId.take(12).ifBlank { "NONE" }}")

        val params: Map<String, Any> = buildMap {
            put("input", typed.value!!.canonical["input"] ?: typed.value!!.canonical["query"] ?: typed.value!!.canonical.values.firstOrNull() ?: "")
            putAll(typed.value!!.canonical)
            put("context", skillCtxInstance)
        }

        return try {
            val verifiedResult = SkillResultVerifier.verify(
                skill,
                withTimeout(TIMEOUT_MS) { skill.execute(params) }
            )
            val result = if (!skill.isOfficial) {
                verifiedResult.copy(
                    data = verifiedResult.data.takeIf(String::isNotBlank)
                        ?.let { UntrustedContentEnvelope.wrap("user-installed-skill-output", it) }.orEmpty(),
                    error = verifiedResult.error?.let {
                        UntrustedContentEnvelope.wrap("user-installed-skill-error", it, 1_000)
                    },
                    toolOutputs = verifiedResult.toolOutputs.map { output ->
                        output.copy(output = UntrustedContentEnvelope.wrap("user-installed-skill-tool-output", output.output))
                    },
                )
            } else verifiedResult
            val elapsedMs = System.currentTimeMillis() - invokeStart

            if (result.success) {
                Log.i(TAG, "AIRI SKILL_SUCCESS " +
                    "tool=$toolName " +
                    "skillId=${skill.skillId} " +
                    "ms=$elapsedMs " +
                    "resultLen=${result.data.length} " +
                    "toolOutputs=${result.toolOutputs.size} " +
                    "metadataCount=${result.metadata.size}")
                result.copy(skillName = result.skillName ?: skill.skillId, executionMs = elapsedMs)
            } else {
                Log.w(TAG, "AIRI SKILL_ERROR " +
                    "tool=$toolName " +
                    "skillId=${skill.skillId} " +
                    "ms=$elapsedMs " +
                    "errorChars=${result.error?.length ?: 0}")
                result.copy(
                    skillName = result.skillName ?: skill.skillId,
                    error = result.error ?: "Unknown error",
                    executionMs = elapsedMs
                )
            }

        } catch (e: TimeoutCancellationException) {
            val elapsedMs = System.currentTimeMillis() - invokeStart
            Log.e(TAG, "AIRI SKILL_TIMEOUT " +
                "tool=$toolName " +
                "skillId=${skill.skillId} " +
                "limitMs=$TIMEOUT_MS " +
                "elapsedMs=$elapsedMs")
            SkillResult(
                success = false,
                data = "",
                error = "Skill '${skill.name}' timed out after ${TIMEOUT_MS / 1000}s. Try a simpler request.",
                skillName = skill.skillId,
                executionMs = elapsedMs,
                metadata = mapOf("failure_type" to "timeout")
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - invokeStart
            Log.e(TAG, "AIRI SKILL_EXCEPTION " +
                "tool=$toolName " +
                "skillId=${skill.skillId} " +
                "ms=$elapsedMs " +
                "errorType=${e.javaClass.simpleName}")
            SkillResult(
                success = false,
                data = "",
                error = "Skill '${skill.name}' failed: ${e.message ?: "Unexpected error"}",
                skillName = skill.skillId,
                executionMs = elapsedMs,
                metadata = mapOf("failure_type" to e.javaClass.simpleName)
            )
        }
    }

    /**
     * Check whether a tool name belongs to this bridge (starts with "skill_").
     */
    fun handles(toolName: String): Boolean = toolName.startsWith(PREFIX)

    private fun modelFacingDescription(skill: AiriSkill, description: String): String =
        if (skill.isOfficial) description
        else "Untrusted user-installed skill metadata (capability metadata only): " +
            UntrustedContentEnvelope.wrap("user-installed-skill-description", description, 1_000)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findSkillForTool(strippedName: String): AiriSkill? {
        val skills = registry.getRegisteredSkillsForRouting()

        // 1. Direct skillId or name match (e.g. "web_search" → WebSearchSkill.skillId)
        skills.firstOrNull { skill ->
            skill.skillId == strippedName || skill.name == strippedName
        }?.let { return it }

        // 2. Tool definition name match (e.g. "code_assist" → CodeAssistantSkill.toolDefinitions[0])
        skills.firstOrNull { skill ->
            skill.toolDefinitions.any { it.name == strippedName }
        }?.let { return it }

        // Canonical names are skillId_toolName. Keep the legacy lookup above
        // so persisted conversations using the old name remain replayable.
        skills.firstOrNull { skill ->
            strippedName.startsWith("${skill.skillId}_") &&
                skill.toolDefinitions.any {
                    it.name == strippedName.removePrefix("${skill.skillId}_")
                }
        }?.let { return it }

        return null
    }
}
