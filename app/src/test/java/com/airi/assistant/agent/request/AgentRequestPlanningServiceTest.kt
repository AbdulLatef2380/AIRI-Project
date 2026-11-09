package com.airi.assistant.agent.request

import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.execution.ExecutionRequest.ToolChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestPlanningServiceTest {
    private val schemas = listOf(
        ToolSchema("write_file", "Write a file", emptyMap()),
        ToolSchema("check_syntax", "Check syntax", emptyMap()),
        ToolSchema("web_search", "Search the web", emptyMap()),
    )

    @Test
    fun serviceDelegatesProviderSupportAndReturnsCanonicalCodePlan() {
        val service = AgentRequestPlanningService { ModelToolSupport.NATIVE }
        val plan = service.plan("قم بإنشاء كود آلة حاسبة بلغة python", "ignored", schemas)

        assertEquals(RequestPlan.Kind.CODE_TASK, plan.kind)
        assertEquals(ToolChoice.REQUIRED, plan.toolChoice)
        assertEquals(setOf("write_file"), plan.requiredTools)
        assertTrue("write_file" in plan.exposedTools)
    }

    @Test
    fun unavailableToolsRemainAnExplicitUnfulfillableDecision() {
        val service = AgentRequestPlanningService { ModelToolSupport.NATIVE }
        val plan = service.plan("write a python script", "ignored", listOf(ToolSchema("web_search", "Search", emptyMap())))

        assertEquals(RequestPlan.Unfulfillable.REQUIRED_TOOL_MISSING, plan.unfulfillable)
        assertEquals(RequestPlan.BlockReason.NOT_AVAILABLE, plan.blockedTools["write_file"])
    }
}
