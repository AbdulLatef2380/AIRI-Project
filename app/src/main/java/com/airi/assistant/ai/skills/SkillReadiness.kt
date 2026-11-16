package com.airi.assistant.ai.skills

/**
 * A truthful, UI-safe projection of a skill's lifecycle. These flags are
 * deliberately monotonic: a later stage can never be true while its required
 * earlier stage is false.
 */
data class SkillReadiness(
    val registered: Boolean,
    val ready: Boolean,
    val exposed: Boolean,
    val admitted: Boolean,
    val executable: Boolean,
    val blockedReason: BlockedReason? = null,
    val nextAction: NextAction? = null,
) {
    enum class BlockedReason {
        DISABLED,
        CONNECTOR_NOT_READY,
        ACTION_NOT_ALLOWED,
        PERMISSION_REQUIRED,
        RUNTIME_GATE_PENDING,
        INVALID,
    }

    enum class NextAction {
        ENABLE_SKILL,
        CONNECT_SERVICE,
        AUTHORIZE_ACTION,
        GRANT_PERMISSION,
        RETRY_RUNTIME_CHECK,
        INSPECT_SKILL,
    }

    companion object {
        fun evaluate(
            registered: Boolean,
            enabled: Boolean,
            dependenciesReady: Boolean,
            actionsReady: Boolean,
            permissionsReady: Boolean,
            runtimeValidated: Boolean = true,
        ): SkillReadiness {
            if (!registered) return SkillReadiness(false, false, false, false, false, BlockedReason.INVALID, NextAction.INSPECT_SKILL)
            val exposed = enabled
            if (!enabled) return SkillReadiness(true, false, exposed, false, false, BlockedReason.DISABLED, NextAction.ENABLE_SKILL)
            if (!dependenciesReady) return SkillReadiness(true, false, true, false, false, BlockedReason.CONNECTOR_NOT_READY, NextAction.CONNECT_SERVICE)
            if (!actionsReady) return SkillReadiness(true, false, true, false, false, BlockedReason.ACTION_NOT_ALLOWED, NextAction.AUTHORIZE_ACTION)
            if (!permissionsReady) return SkillReadiness(true, false, true, false, false, BlockedReason.PERMISSION_REQUIRED, NextAction.GRANT_PERMISSION)
            if (!runtimeValidated) return SkillReadiness(true, true, true, true, false, BlockedReason.RUNTIME_GATE_PENDING, NextAction.RETRY_RUNTIME_CHECK)
            return SkillReadiness(true, true, true, true, true)
        }
    }
}
