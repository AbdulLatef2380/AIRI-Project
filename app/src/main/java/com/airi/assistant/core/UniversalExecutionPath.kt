package com.airi.assistant.core

import com.airi.assistant.agent.loop.tool.ToolSchema

/**
 * PHASE 2 route evidence. Resolution is pure and descriptive: it does not
 * dispatch, connect, authenticate, or select a different execution path.
 */
object UniversalExecutionPathResolver {

    fun resolve(
        tool: ToolSchema,
        runtimePath: RuntimeExecutionPath,
        connectorId: String? = null,
    ): UniversalExecutionPath {
        val expected = when {
            tool.name.startsWith("skill_") -> RuntimeExecutionPath.SKILL_BRIDGE
            tool.name.startsWith("connector_") || connectorId != null -> RuntimeExecutionPath.CONNECTOR_RUNTIME
            else -> RuntimeExecutionPath.TOOL_DISPATCHER
        }
        return UniversalExecutionPath(
            toolName = tool.name,
            expectedPath = expected,
            actualPath = runtimePath,
            classification = when {
                expected == runtimePath -> ExecutionPathClassification.CANONICAL
                runtimePath == RuntimeExecutionPath.UNKNOWN -> ExecutionPathClassification.UNRESOLVED
                else -> ExecutionPathClassification.LEGACY_OR_MISMATCH
            },
            connectorId = connectorId,
        )
    }
}

enum class RuntimeExecutionPath {
    TOOL_DISPATCHER,
    SKILL_BRIDGE,
    CONNECTOR_RUNTIME,
    LOCAL_RUNTIME,
    SYSTEM_RUNTIME,
    UNKNOWN,
}

enum class ExecutionPathClassification {
    CANONICAL,
    LEGACY_OR_MISMATCH,
    UNRESOLVED,
}

data class UniversalExecutionPath(
    val toolName: String,
    val expectedPath: RuntimeExecutionPath,
    val actualPath: RuntimeExecutionPath,
    val classification: ExecutionPathClassification,
    val connectorId: String? = null,
)
