package com.airi.assistant.core

import com.airi.assistant.agent.loop.tool.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class UniversalExecutionPathTest {

    @Test
    fun skillToolUsesCanonicalSkillBridgePath() {
        val route = UniversalExecutionPathResolver.resolve(
            tool = ToolSchema("skill_read", "read"),
            runtimePath = RuntimeExecutionPath.SKILL_BRIDGE,
        )

        assertEquals(RuntimeExecutionPath.SKILL_BRIDGE, route.expectedPath)
        assertEquals(ExecutionPathClassification.CANONICAL, route.classification)
    }

    @Test
    fun builtinToolUsesCanonicalToolDispatcherPath() {
        val route = UniversalExecutionPathResolver.resolve(
            tool = ToolSchema("calendar_read", "read"),
            runtimePath = RuntimeExecutionPath.TOOL_DISPATCHER,
        )

        assertEquals(RuntimeExecutionPath.TOOL_DISPATCHER, route.expectedPath)
        assertEquals(ExecutionPathClassification.CANONICAL, route.classification)
    }

    @Test
    fun connectorBoundToolMismatchIsVisibleWithoutRerouting() {
        val route = UniversalExecutionPathResolver.resolve(
            tool = ToolSchema("external_read", "read"),
            runtimePath = RuntimeExecutionPath.TOOL_DISPATCHER,
            connectorId = "future_connector",
        )

        assertEquals(RuntimeExecutionPath.CONNECTOR_RUNTIME, route.expectedPath)
        assertEquals(ExecutionPathClassification.LEGACY_OR_MISMATCH, route.classification)
        assertEquals("future_connector", route.connectorId)
    }
}
