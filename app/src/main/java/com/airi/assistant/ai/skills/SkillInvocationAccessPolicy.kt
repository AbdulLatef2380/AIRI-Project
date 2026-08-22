package com.airi.assistant.ai.skills

/**
 * Enforces the declared execution boundary for a skill invocation.
 *
 * The policy is deliberately independent of Android APIs so it can be tested
 * without a device. Android callers provide the permission lookup function.
 * It does not grant permissions or upgrade a skill's declared access.
 */
object SkillInvocationAccessPolicy {

    sealed interface Decision {
        data class Allow(val context: SkillContext) : Decision

        data class Deny(
            val reason: DenyReason,
            val userMessage: String
        ) : Decision
    }

    enum class DenyReason {
        DISABLED,
        MISSING_PERMISSION,
        MEMORY_UNAVAILABLE,
        CONNECTOR_UNHEALTHY
    }

    fun authorize(
        skill: AiriSkill,
        context: SkillContext,
        hasPermission: (String) -> Boolean,
        isConnectorHealthy: (String) -> Boolean = { true }
    ): Decision {
        if (!skill.isEnabled) {
            return Decision.Deny(
                reason = DenyReason.DISABLED,
                userMessage = "Skill '${skill.name}' is disabled."
            )
        }

        val missingPermissions = skill.requiredPermissions.filterNot(hasPermission)
        if (missingPermissions.isNotEmpty()) {
            return Decision.Deny(
                reason = DenyReason.MISSING_PERMISSION,
                userMessage = "Skill '${skill.name}' needs permission before it can run."
            )
        }

        val unavailableConnectors = skill.requiredConnectors.filterNot(isConnectorHealthy)
        if (unavailableConnectors.isNotEmpty()) {
            return Decision.Deny(
                reason = DenyReason.CONNECTOR_UNHEALTHY,
                userMessage = "Skill '${skill.name}' needs a connected service before it can run."
            )
        }

        if (skill.memoryAccess.canRead && context.memoryManager == null) {
            return Decision.Deny(
                reason = DenyReason.MEMORY_UNAVAILABLE,
                userMessage = "Skill '${skill.name}' needs memory access, but memory is unavailable."
            )
        }

        return Decision.Allow(
            context.copy(
                memoryManager = context.memoryManager.takeIf { skill.memoryAccess.canRead },
                modelBridge = context.modelBridge.takeIf { skill.modelAccess != SkillModelAccess.NONE }
            )
        )
    }
}
