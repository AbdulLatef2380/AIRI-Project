package com.airi.assistant.agent.loop.tool

/**
 * Transitional contract for the vertical slice. Existing BuiltinTools,
 * skills, and connectors remain the source of truth; this contract gives the
 * agent one vocabulary for exposure/readiness without removing legacy APIs.
 */
data class RuntimeToolContract(
    val schema: ToolSchema,
    val readiness: Readiness,
    val source: Source,
    val reason: String? = null,
    val capabilityId: String? = null,
) {
    enum class Readiness {
        AVAILABLE,
        NOT_READY,
        REQUIRES_AUTH,
        PERMISSION_REQUIRED,
        CONFIGURATION_REQUIRED,
        DEPENDENCY_MISSING,
        DISCONNECTED,
        ERROR,
        UNSUPPORTED,
        BLOCKED,
    }
    enum class Source { BUILTIN, SKILL, CONNECTOR }

    companion object {
        fun builtin(schema: ToolSchema) = RuntimeToolContract(schema, Readiness.AVAILABLE, Source.BUILTIN)
        /** Preserve SkillToolBridge readiness markers at the execution boundary. */
        fun skill(schema: ToolSchema): RuntimeToolContract {
            val marker = Regex("\\[READINESS=requires_([^:]]+):\\s*(.*?)]")
                .find(schema.description)
            if (marker == null) return RuntimeToolContract(schema, Readiness.AVAILABLE, Source.SKILL)
            val readiness = when (marker.groupValues[1].lowercase()) {
                "missing_permission" -> Readiness.PERMISSION_REQUIRED
                "connector_unhealthy" -> Readiness.REQUIRES_AUTH
                "skill_disabled", "not_registered", "dependency_missing",
                "model_unavailable", "model_access_denied" -> Readiness.NOT_READY
                else -> Readiness.NOT_READY
            }
            return RuntimeToolContract(
                schema = schema,
                readiness = readiness,
                source = Source.SKILL,
                reason = marker.groupValues[2].trim().ifBlank { "Skill is not ready" },
                capabilityId = schema.name.removePrefix("skill_").substringBefore('_').ifBlank { null },
            )
        }
        fun connector(schema: ToolSchema, available: Boolean, capabilityId: String? = null) = RuntimeToolContract(
            schema = schema,
            readiness = if (available) Readiness.AVAILABLE else Readiness.NOT_READY,
            source = Source.CONNECTOR,
            reason = if (available) null else "Connector is not connected and healthy",
            capabilityId = capabilityId ?: schema.name
                .removePrefix("connector_")
                .substringBefore('_')
                .ifBlank { null },
        )
    }
}
