package com.airi.assistant.ai.skills

import com.airi.assistant.ai.skills.impl.EngineeringQualitySkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineeringQualitySkillTest {
    @Test
    fun catalogHasDistinctPerformanceAndQualityFocuses() {
        val specs = EngineeringQualitySkill.SPECS
        assertTrue(specs.size >= 8)
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        assertTrue(specs.any { it.category == "PERFORMANCE" })
        assertTrue(specs.any { it.category == "QUALITY" })
        specs.forEach { spec ->
            assertEquals(3, spec.stages.size)
            assertTrue(spec.stages.all { it.instruction.isNotBlank() && it.outputContract.isNotBlank() })
            assertTrue(spec.limitations.isNotEmpty())
        }
    }

    @Test
    fun performanceRoutingUsesSpecializedKeywords() {
        val profiler = EngineeringQualitySkill(EngineeringQualitySkill.SPECS.first { it.id == "performance_profiler" })
        val quality = EngineeringQualitySkill(EngineeringQualitySkill.SPECS.first { it.id == "complexity_reducer" })
        assertTrue(profiler.score("benchmark latency is too slow", SkillContext()) > 0)
        assertTrue(quality.score("refactor this duplicate code", SkillContext()) > 0)
    }

    @Test
    fun executionRequiresEvidenceAndChainsThreeStages() = runBlocking {
        val spec = EngineeringQualitySkill.SPECS.first()
        val bridge = RecordingBridge()
        val result = EngineeringQualitySkill(spec).execute(
            mapOf(
                "input" to "fun render() { loadDataOnMainThread() }",
                "context" to SkillContext(modelBridge = bridge)
            )
        )
        assertTrue(result.success)
        assertEquals(3, bridge.prompts.size)
        assertEquals("REPORT_3", result.data)
        assertEquals("runtime bottlenecks", result.metadata["analysis_focus"])
        assertTrue(bridge.prompts[1].contains("REPORT_1"))
        assertTrue(bridge.prompts[2].contains("REPORT_2"))
    }

    @Test
    fun stageFailureAndMissingModelAreExplicit() = runBlocking {
        val spec = EngineeringQualitySkill.SPECS.first()
        val failed = EngineeringQualitySkill(spec).execute(
            mapOf("input" to "code", "context" to SkillContext(modelBridge = RecordingBridge(failAt = 2)))
        )
        assertFalse(failed.success)
        assertTrue(failed.error!!.contains("stage 2", ignoreCase = true))

        val missing = EngineeringQualitySkill(spec).execute(
            mapOf("input" to "code", "context" to SkillContext())
        )
        assertFalse(missing.success)
        assertTrue(missing.error!!.contains("active AI model"))
    }

    private class RecordingBridge(private val failAt: Int? = null) : SkillModelBridge {
        val prompts = mutableListOf<String>()

        override suspend fun complete(prompt: String, systemPrompt: String, maxTokens: Int): String {
            prompts += prompt
            if (prompts.size == failAt) error("synthetic stage failure")
            return "REPORT_${prompts.size}"
        }
    }
}
