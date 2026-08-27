package com.airi.assistant.agent.planning

import com.airi.core.planning.PlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGeneratorAdmissionTest {

    private val generator = PlanGenerator()

    @Test
    fun createDAGPlanFromLLM_fallsBackForInvalidJsonAndEmptyPlan() {
        assertFallback(generator.createDAGPlanFromLLM("not valid JSON", "recover"))
        assertFallback(generator.createDAGPlanFromLLM("{not-valid-json}", "recover"))
        assertFallback(generator.createDAGPlanFromLLM("{\"goal\":\"recover\",\"steps\":[]}", "recover"))
    }

    @Test
    fun createDAGPlanFromLLM_fallsBackForDuplicateCycleAndMissingDependencies() {
        assertFallback(generator.createDAGPlanFromLLM("""
            {"goal":"recover","steps":[
              {"id":"same","action":"wait","params":{}},
              {"id":"same","action":"wait","params":{}}
            ]}
        """.trimIndent(), "recover"))
        assertFallback(generator.createDAGPlanFromLLM("""
            {"goal":"recover","steps":[
              {"id":"one","action":"wait","params":{},"depends_on":["two"]},
              {"id":"two","action":"wait","params":{},"depends_on":["one"]}
            ]}
        """.trimIndent(), "recover"))
        assertFallback(generator.createDAGPlanFromLLM("""
            {"goal":"recover","steps":[
              {"id":"one","action":"wait","params":{},"depends_on":["missing"]}
            ]}
        """.trimIndent(), "recover"))
    }

    @Test
    fun createDAGPlanFromLLM_fallsBackWhenStepLimitIsExceeded() {
        val steps = (1..17).joinToString(",") { index ->
            "{\"id\":\"step-$index\",\"action\":\"wait\",\"params\":{}}"
        }

        assertFallback(generator.createDAGPlanFromLLM("{\"goal\":\"recover\",\"steps\":[$steps]}", "recover"))
    }

    private fun assertFallback(plan: com.airi.core.planning.ActionPlan) {
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps.single() is PlanStep.Custom)
        assertEquals("fallback_1", plan.steps.single().id)
    }
}
