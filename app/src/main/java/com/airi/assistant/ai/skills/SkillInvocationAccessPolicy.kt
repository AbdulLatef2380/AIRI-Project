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
        INVALID_MANIFEST,
        MISSING_PERMISSION,
        MEMORY_UNAVAILABLE,
        MODEL_UNAVAILABLE,
        CONNECTOR_UNHEALTHY
    }

    fun authorize(
        skill: AiriSkill,
        context: SkillContext,
        hasPermission: (String) -> Boolean,
        isConnectorHealthy: (String) -> Boolean = { false }
    ): Decision {
        if (!skill.isEnabled) {
            return Decision.Deny(
                reason = DenyReason.DISABLED,
                userMessage = "Skill '${skill.name}' is disabled."
            )
        }

        // The official manifest is the canonical catalog contract. Some legacy
        // skill instances do not mirror its permission list, so use manifest
        // permissions at the runtime gate rather than silently trusting an empty
        // instance default. Custom skills continue to use their own declaration.
        val officialManifest = if (skill.isOfficial) {
            OfficialSkillLibrary.manifestFor(skill.skillId)?.takeIf { it.isOfficial }
                ?: return Decision.Deny(
                    reason = DenyReason.INVALID_MANIFEST,
                    userMessage = "Skill '${skill.name}' has no valid official manifest."
                )
        } else null
        val declaredPermissions = (skill.requiredPermissions + officialManifest?.permissions.orEmpty()).distinct()
        val missingPermissions = declaredPermissions.distinct().filterNot(hasPermission)
        if (missingPermissions.isNotEmpty()) {
            return Decision.Deny(
                reason = DenyReason.MISSING_PERMISSION,
                userMessage = "Skill '${skill.name}' needs permission before it can run."
            )
        }

        val declaredConnectors = (skill.requiredConnectors + officialManifest?.dependencies.orEmpty()
            .filter { it.startsWith("connector:") }
            .map { it.removePrefix("connector:") })
            .distinct()
        val unavailableConnectors = declaredConnectors.filterNot(isConnectorHealthy)
        if (unavailableConnectors.isNotEmpty()) {
            return Decision.Deny(
                reason = DenyReason.CONNECTOR_UNHEALTHY,
                userMessage = "Skill '${skill.name}' needs a connected service before it can run."
            )
        }

        val needsMemoryRead = skill.memoryAccess.canRead || officialManifest?.memoryAccess?.canRead == true
        val needsMemoryWrite = skill.memoryAccess.canWrite || officialManifest?.memoryAccess?.canWrite == true
        if ((needsMemoryRead || needsMemoryWrite) && context.memoryManager == null) {
            return Decision.Deny(
                reason = DenyReason.MEMORY_UNAVAILABLE,
                userMessage = "Skill '${skill.name}' needs memory access, but memory is unavailable."
            )
        }

        val needsModel = skill.modelAccess != SkillModelAccess.NONE ||
            officialManifest?.modelAccess?.let { it != SkillModelAccess.NONE } == true
        if (needsModel && context.modelBridge == null) {
            return Decision.Deny(
                reason = DenyReason.MODEL_UNAVAILABLE,
                userMessage = "Skill '${skill.name}' needs a model, but model access is unavailable."
            )
        }

        return Decision.Allow(
            context.copy(
                memoryManager = context.memoryManager.takeIf { needsMemoryRead || needsMemoryWrite },
                modelBridge = context.modelBridge.takeIf { needsModel }
            )
        )
    }
}
