package com.airi.assistant.agent.subagent

/**
 * Single fail-closed authorization decision used by both automatic routing and
 * explicit agent-id dispatch. Metadata is descriptive until this policy checks it.
 */
object SubAgentAuthorization {
    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
    }

    fun evaluate(
        capability: SubAgentCapability,
        context: SubAgentContext,
        runtimeCapabilities: Set<String> = emptySet(),
    ): Decision {
        val grantedPermissions = context.grantedPermissions.toSet() + runtimeCapabilities
        if (capability.requiresCloud && !context.cloudAllowed) {
            return Decision.Deny("cloud access is not allowed by privacy policy")
        }
        if (capability.accessesPrivateData && !context.privateDataAllowed) {
            return Decision.Deny("private-data consent is not granted")
        }
        val missingPermissions = capability.requiredPermissions.filterNot(grantedPermissions::contains)
        if (missingPermissions.isNotEmpty()) {
            return Decision.Deny("missing permissions: ${missingPermissions.joinToString()}")
        }
        val missingTools = capability.requiredTools.filterNot(context.allowedTools.toSet()::contains)
        if (missingTools.isNotEmpty()) {
            return Decision.Deny("missing tools: ${missingTools.joinToString()}")
        }
        if (capability.requiresCloud &&
            capability.costTier.estimatedTokensPerCall.first > context.remainingCloudTokenBudget
        ) {
            return Decision.Deny("cloud token budget is insufficient")
        }
        return Decision.Allow
    }
}
