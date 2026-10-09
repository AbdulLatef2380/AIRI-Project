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
        fun skill(schema: ToolSchema) = RuntimeToolContract(schema, Readiness.AVAILABLE, Source.SKILL)
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
