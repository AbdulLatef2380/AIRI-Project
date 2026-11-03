package com.airi.assistant.agent.loop

import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.ai.QueryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAdmissionTest {
    private val catalog = listOf(
        ToolSchema("current_time", "Read the device clock"),
        ToolSchema("connector_github_list_repos", "List GitHub repositories"),
    )

    @Test
    fun directResponseDoesNotCreateVisiblePlanOrUseLoopCeiling() {
        val decision = ExecutionAdmissionPolicy.decide(
            input = "اشرح لي الفرق بين REST وGraphQL",
            queryType = QueryType.ANALYTICAL,
            effectiveTools = catalog,
            attachmentCount = 0,
        )

        assertFalse(decision.admitted)
        assertEquals(0, decision.plannedStepCount)
        assertEquals("direct_response", decision.reason)
    }

    @Test
    fun explicitCapabilityIsAdmittedWithoutPublishingUnknownStepCount() {
        val decision = ExecutionAdmissionPolicy.decide(
            input = "اعرض مستودعاتي في GitHub",
            queryType = QueryType.ACTION,
            effectiveTools = catalog,
            attachmentCount = 0,
        )

        assertTrue(decision.admitted)
        assertEquals(0, decision.plannedStepCount)
        assertEquals("explicit_capability_intent", decision.reason)
    }

    @Test
    fun attachmentAloneDoesNotCreateAVisiblePlan() {
        val decision = ExecutionAdmissionPolicy.decide(
            input = "لخص هذا الملف",
            queryType = QueryType.SIMPLE,
            effectiveTools = emptyList(),
            attachmentCount = 1,
        )

        assertFalse(decision.admitted)
        assertEquals(0, decision.plannedStepCount)
        assertEquals("direct_response_with_attachment", decision.reason)
    }

    @Test
    fun attachmentWithAnExplicitCapabilityIsStillAdmittedByTheCapability() {
        val decision = ExecutionAdmissionPolicy.decide(
            input = "اعرض مستودعاتي في GitHub",
            queryType = QueryType.ACTION,
            effectiveTools = catalog,
            attachmentCount = 2,
        )

        assertTrue(decision.admitted)
        assertEquals("explicit_capability_intent", decision.reason)
    }
}
