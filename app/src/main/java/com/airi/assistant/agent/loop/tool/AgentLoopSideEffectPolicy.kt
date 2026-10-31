package com.airi.assistant.agent.loop.tool

/**
 * Fail-closed admission for AgentLoop tools that can change a user resource or
 * live device state. A chat loop has no durable task/run/step context today,
 * therefore it must not invoke these adapters directly and then mistake an
 * in-memory confirmation string for a replay-safe approval.
 */
internal object AgentLoopSideEffectPolicy {

    enum class Decision {
        ALLOW_READ,
        ALLOW_TYPED_CALENDAR_CREATE,
        ALLOW_TYPED_TERMINAL,
        DURABLE_CONTEXT_REQUIRED
    }

    /** Canonical admission path for new tools; policy is carried by the schema. */
    fun decide(schema: ToolSchema, hasDurableExecutionContext: Boolean): Decision = when {
        schema.sideEffectPolicy == ToolSchema.SideEffectPolicy.READ_ONLY -> Decision.ALLOW_READ
        schema.sideEffectPolicy == ToolSchema.SideEffectPolicy.REQUIRES_USER_CONFIRMATION &&
            schema.name == "calendar_create" && hasDurableExecutionContext -> Decision.ALLOW_TYPED_CALENDAR_CREATE
        schema.sideEffectPolicy == ToolSchema.SideEffectPolicy.REQUIRES_DURABLE_CONTEXT &&
            schema.name == "terminal_execute" && hasDurableExecutionContext -> Decision.ALLOW_TYPED_TERMINAL
        schema.sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY ->
            Decision.DURABLE_CONTEXT_REQUIRED
        else -> Decision.ALLOW_READ
    }

    /** Compatibility adapter resolves the name through the canonical schema registry. */
    fun decide(toolName: String, hasDurableExecutionContext: Boolean): Decision =
        decide(
            BuiltinTools.ALL.firstOrNull { it.name == toolName }
                ?: ToolSchema(toolName, "Unknown tool", sideEffectPolicy = ToolSchema.SideEffectPolicy.REQUIRES_DURABLE_CONTEXT),
            hasDurableExecutionContext,
        )

    fun blockedMessage(toolName: String): String = when (toolName) {
        "calendar_create" -> "Calendar creation requires a task-owned approval session before it can run."
        "create_note" -> "Creating a note requires a task-owned approval session before it can run."
        "set_alarm" -> "Setting an alarm requires a task-owned approval session before it can run."
        "open_app" -> "Opening an app requires a task-owned approval session before it can run."
        "type_text" -> "Typing text into another app requires a task-owned approval session before it can run."
        "flashlight" -> "Torch control requires a task-owned approval session before it can run."
        "terminal_execute" -> "Terminal execution requires a task-owned approval session before it can run."
        else -> "This device action requires a task-owned approval session before it can run."
    }

    /** Catalog-level blocks apply to device actions; terminal is admitted by the
     * AgentLoop durable-context filter and the gateway's bounded command policy. */
    fun blockedToolOverrides(): Map<String, RuntimeToolCatalog.StatusOverride> =
        BuiltinTools.ALL
            .filter { it.sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY && it.name != "terminal_execute" }
            .associate { tool ->
                tool.name to
                RuntimeToolCatalog.StatusOverride(
                    readiness = RuntimeToolContract.Readiness.BLOCKED,
                    reason = blockedMessage(tool.name),
                )
            }
}
