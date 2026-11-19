package com.airi.assistant.ai.skills

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.agent.loop.tool.TypedArgumentAdapters
import com.airi.assistant.domain.tool.TypedArgumentContract
import com.airi.assistant.domain.permission.PermissionService
import com.airi.assistant.security.UntrustedContentEnvelope
import com.airi.assistant.security.PermissionGovernanceLayer
import com.airi.assistant.vault.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Approval is never inferred from a tool call; only a trusted approval UI can authorize it. */
internal object SkillInvocationApprovalPolicy {
    fun mustBlock(
        requiresConfirmation: Boolean,
        toolIsDangerous: Boolean,
        trustedApprovalAvailable: Boolean,
    ): Boolean = (requiresConfirmation || toolIsDangerous) && !trustedApprovalAvailable
}

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
    private val registry: SkillRoutingRegistry,
    private val modelBridge: SkillModelBridge? = null,
    private val skillCtx: () -> SkillContext = { SkillContext() },
    // A bridge without an injected connector registry must not assume that
    // external dependencies are available; callers can opt in with live state.
    private val isConnectorHealthy: (String) -> Boolean = { false },
    private val readinessCtx: () -> SkillContext = skillCtx,
    private val approvalGate: SkillApprovalGate? = null,
    private val executionTimeoutMs: Long = TIMEOUT_MS,
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
        if (requiresUserApproval(skill, toolIsDangerous = skill.toolDefinitions.any { it.dangerous }) && approvalGate == null) {
            return SkillInvocationAccessPolicy.Decision.Deny(
                reason = SkillInvocationAccessPolicy.DenyReason.APPROVAL_UNAVAILABLE,
                userMessage = "This skill requires explicit approval, but a trusted approval flow is not configured. It cannot run yet.",
            )
        }
        val providedContext = readinessCtx()
        val requestedContext = providedContext.copy(modelBridge = modelBridge ?: providedContext.modelBridge)
        return SkillInvocationAccessPolicy.authorize(
            skill = skill,
            context = requestedContext,
            hasPermission = permissionService::hasPermission,
            isConnectorHealthy = isConnectorHealthy,
        )
    }

    private fun requiresUserApproval(skill: AiriSkill, toolIsDangerous: Boolean = false): Boolean {
        val manifest = if (isTrustedOfficialSkill(skill)) OfficialSkillLibrary.manifestFor(skill.skillId) else null
        return skill.requiresConfirmation ||
            toolIsDangerous ||
            skill.riskLevel in setOf(SkillRiskLevel.HIGH, SkillRiskLevel.CRITICAL) ||
            manifest?.requiresConfirmation == true ||
            manifest?.riskLevel == SkillRiskLevel.HIGH ||
            manifest?.riskLevel == SkillRiskLevel.CRITICAL
    }

    private fun isTrustedOfficialSkill(skill: AiriSkill): Boolean =
        skill.isOfficial && OfficialSkillLibrary.manifestFor(skill.skillId) != null

    private fun skillToSchema(skill: AiriSkill, status: String? = null): ToolSchema = ToolSchema(
        name        = "$PREFIX${skill.skillId}",
        description = "[Skill] " + modelFacingDescription(skill, skill.name + ": " + skill.description) + status.orEmpty(),
        parameters  = skill.parameters.mapValues { (_, desc) ->
            ToolSchema.Param(type = "string", description = desc, required = false)
        },
        dangerous = requiresUserApproval(skill),
        category = ToolSchema.Category.EXTERNAL,
        sideEffectPolicy = if (requiresUserApproval(skill)) ToolSchema.SideEffectPolicy.REQUIRES_USER_CONFIRMATION else ToolSchema.SideEffectPolicy.READ_ONLY,
        actionClass = if (requiresUserApproval(skill)) ToolSchema.ActionClass.APPROVAL_REQUIRED else ToolSchema.ActionClass.READ,
    )

    private fun toolDefToSchema(skill: AiriSkill, toolDef: SkillToolDefinition, status: String? = null): ToolSchema = ToolSchema(
        // Namespace definitions by skill. The old name made duplicate actions
        // resolve to whichever skill happened to be registered first.
        name        = "$PREFIX${skill.skillId}_${toolDef.name}",
        description = modelFacingDescription(skill, skill.name + ": " + toolDef.description) + status.orEmpty(),
        parameters  = toolDef.parameters.mapValues { (_, p) ->
            ToolSchema.Param(type = p.type, description = p.description, required = p.required, nullable = p.nullable, defaultValue = p.defaultValue, minLength = p.minLength, maxLength = p.maxLength, minInt = p.minInt, maxInt = p.maxInt, minNumber = p.minNumber, maxNumber = p.maxNumber, allowedValues = p.allowedValues, items = p.items?.let { item -> ToolSchema.Param(item.type, item.description, item.required) }, properties = p.properties.mapValues { (_, child) -> ToolSchema.Param(child.type, child.description, child.required) })
        },
        dangerous   = requiresUserApproval(skill, toolDef.dangerous),
        category    = ToolSchema.Category.EXTERNAL,
        sideEffectPolicy = if (requiresUserApproval(skill, toolDef.dangerous)) ToolSchema.SideEffectPolicy.REQUIRES_USER_CONFIRMATION else ToolSchema.SideEffectPolicy.READ_ONLY,
        actionClass = if (requiresUserApproval(skill, toolDef.dangerous)) ToolSchema.ActionClass.APPROVAL_REQUIRED else ToolSchema.ActionClass.READ,
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
        args: Map<String, Any?>,
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

        val definition = skill.toolDefinitions.firstOrNull { definition ->
            toolName == "$PREFIX${skill.skillId}_${definition.name}" || toolName == "$PREFIX${definition.name}"
        }
        val providedContext = skillCtx()
        val trustedOfficial = isTrustedOfficialSkill(skill)
        val resolvedExecutionId = executionId?.takeIf(String::isNotBlank) ?: providedContext.executionId
        val requestedContext = providedContext.copy(
            modelBridge = modelBridge ?: providedContext.modelBridge,
            executionId = resolvedExecutionId,
            configValues = if (trustedOfficial) providedContext.configValues else emptyMap(),
            secretAccess = if (trustedOfficial && approvalGate != null) createSecretAccess(
                skill = skill,
                toolName = toolName,
                sessionId = providedContext.sessionId,
                executionId = resolvedExecutionId,
            ) else null,
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
                            SkillInvocationAccessPolicy.DenyReason.APPROVAL_UNAVAILABLE -> "approval_required"
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

        // CustomSkillAiriSkillAdapter delegates to CustomSkillExecutor, which
        // performs the authoritative consent check immediately before HTTP I/O.
        if (requiresUserApproval(skill, definition?.dangerous == true) && skill !is CustomSkillAiriSkillAdapter) {
            val gate = approvalGate ?: return SkillResult(
                success = false,
                data = "",
                error = "This skill action requires explicit approval, but no trusted approval flow is configured.",
                skillName = skill.skillId,
                metadata = mapOf("failure_type" to "approval_required", "approval_status" to "unavailable"),
            )
            val risk = when {
                definition?.dangerous == true || skill.riskLevel == SkillRiskLevel.CRITICAL -> PermissionGovernanceLayer.RiskLevel.CRITICAL
                skill.riskLevel == SkillRiskLevel.HIGH -> PermissionGovernanceLayer.RiskLevel.HIGH
                skill.riskLevel == SkillRiskLevel.MEDIUM -> PermissionGovernanceLayer.RiskLevel.MEDIUM
                else -> PermissionGovernanceLayer.RiskLevel.LOW
            }
            val parameterNames = args.keys.sorted().joinToString().take(160).ifBlank { "no arguments" }
            val approved = gate.awaitApproval(
                SkillApprovalRequest(
                    skillId = skill.skillId,
                    toolName = definition?.name ?: skill.skillId,
                    description = "Allow ${skill.displayName} to run '${definition?.name ?: skill.skillId}'? Parameters: $parameterNames. Values are withheld.",
                    riskLevel = risk,
                    sessionId = requestedContext.sessionId,
                    runId = requestedContext.executionId.takeIf(String::isNotBlank),
                ),
            )
            if (!approved) return SkillResult(
                success = false,
                data = "",
                error = "The action was not approved; the skill was not executed.",
                skillName = skill.skillId,
                metadata = mapOf("failure_type" to "approval_denied_or_expired", "approval_status" to "denied"),
            )

            // The invocation may have waited while permissions, connector state,
            // or skill registration changed. Revalidate at the execution boundary.
            if (!skill.isEnabled || findSkillForTool(strippedName) !== skill) {
                return SkillResult(false, "", "The skill changed or was disabled while approval was pending; nothing was executed.", skill.skillId,
                    metadata = mapOf("failure_type" to "authorization_changed"))
            }
            when (val finalAccess = SkillInvocationAccessPolicy.authorize(
                skill = skill,
                context = skillCtxInstance,
                hasPermission = permissionService::hasPermission,
                isConnectorHealthy = isConnectorHealthy,
            )) {
                is SkillInvocationAccessPolicy.Decision.Deny -> return SkillResult(
                    false, "", finalAccess.userMessage, skill.skillId,
                    metadata = mapOf("failure_type" to "authorization_changed", "deny_reason" to finalAccess.reason.name),
                )
                is SkillInvocationAccessPolicy.Decision.Allow -> Unit
            }
        }

        Log.i(TAG, "AIRI SKILL_INVOKE " +
            "tool=$toolName " +
            "skillId=${skill.skillId} " +
            "argKeys=${args.keys.joinToString()} " +
            "modelAccess=${skill.modelAccess} " +
            "modelBridgeActive=${skillCtxInstance.modelBridge != null} " +
            "memoryActive=${skillCtxInstance.memoryManager != null} " +
            "sessionId=${skillCtxInstance.sessionId.take(12).ifBlank { "NONE" }}")

        val validated = typed.value!!
        val runtimeValues: Map<String, Any> = buildMap {
            validated.values.forEach { (name, value) ->
                value.toRuntimeValue()?.let { put(name, it) }
            }
        }
        val params: Map<String, Any> = buildMap {
            put("input", runtimeValues["input"] ?: runtimeValues["query"] ?: runtimeValues.values.firstOrNull() ?: "")
            putAll(runtimeValues)
            put("context", skillCtxInstance)
        }

        return try {
            val verifiedResult = SkillResultVerifier.verify(
                skill,
                withTimeout(executionTimeoutMs) { skill.execute(params) }
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
                "limitMs=$executionTimeoutMs " +
                "elapsedMs=$elapsedMs")
            SkillResult(
                success = false,
                data = "",
                error = "Skill '${skill.name}' timed out after ${executionTimeoutMs / 1000}s. Try a simpler request.",
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

    private fun TypedArgumentContract.Value?.toRuntimeValue(): Any? = when (this) {
        null, TypedArgumentContract.Value.NullValue -> null
        is TypedArgumentContract.Value.Text -> value
        is TypedArgumentContract.Value.Integer -> value
        is TypedArgumentContract.Value.Number -> value
        is TypedArgumentContract.Value.BooleanValue -> value
        is TypedArgumentContract.Value.Url -> value
        is TypedArgumentContract.Value.Date -> value
        is TypedArgumentContract.Value.EnumValue -> value
        is TypedArgumentContract.Value.ObjectValue -> value.mapValues { (_, child) -> child.toRuntimeValue() }
        is TypedArgumentContract.Value.ListValue -> value.map { it.toRuntimeValue() }
    }

    private fun modelFacingDescription(skill: AiriSkill, description: String): String =
        if (skill.isOfficial) description
        else "Untrusted user-installed skill metadata (capability metadata only): " +
            UntrustedContentEnvelope.wrap("user-installed-skill-description", description, 1_000)

    private fun createSecretAccess(
        skill: AiriSkill,
        toolName: String,
        sessionId: String,
        executionId: String,
    ): SkillSecretAccess = object : SkillSecretAccess {
        override suspend fun <T> useSecret(
            secretId: String,
            operation: String,
            consumer: suspend (String) -> T,
        ): SecretVault.CapabilityResult<T> {
            val gate = approvalGate
                ?: return SecretVault.CapabilityResult(SecretVault.CapabilityStatus.DENIED)
            if (!isTrustedOfficialSkill(skill) || operation.isBlank() ||
                !skill.isEnabled || findSkillForTool(skill.skillId) !== skill ||
                readiness(skill) !is SkillInvocationAccessPolicy.Decision.Allow
            ) return SecretVault.CapabilityResult(SecretVault.CapabilityStatus.DENIED)
            if (!SecretVault.hasSkillSecret(skill.skillId, secretId)) {
                return SecretVault.CapabilityResult(SecretVault.CapabilityStatus.MISSING_SECRET)
            }

            val exactOperation = "$toolName:${operation.trim().take(120)}"
            val approved = gate.awaitApproval(
                SkillApprovalRequest(
                    skillId = skill.skillId,
                    toolName = "secret_use:$exactOperation",
                    description = "Allow ${skill.displayName} to use the configured credential '$secretId' for this operation?",
                    riskLevel = PermissionGovernanceLayer.RiskLevel.HIGH,
                    sessionId = sessionId,
                    runId = executionId.takeIf(String::isNotBlank),
                ),
            )
            if (!approved) return SecretVault.CapabilityResult(SecretVault.CapabilityStatus.DENIED)

            val capability = SecretVault.issueSkillCapability(
                skillId = skill.skillId,
                secretId = secretId,
                operation = exactOperation,
                authorizedByPolicy = true,
                taskId = executionId.takeIf(String::isNotBlank),
                ttlMs = 60_000L,
            ) ?: return SecretVault.CapabilityResult(SecretVault.CapabilityStatus.MISSING_SECRET)

            return SecretVault.useSkillCapabilitySuspend(
                token = capability.token,
                skillId = skill.skillId,
                operation = exactOperation,
                consumer = consumer,
            )
        }
    }

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
