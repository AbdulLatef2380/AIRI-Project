package com.airi.assistant.ai.skills

import com.airi.assistant.ai.skills.impl.AdvancedModelSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedModelSkillTest {
    @Test
    fun advancedCatalogHasUniqueTwoStageContracts() {
        val specs = AdvancedModelSkill.SPECS
        assertTrue(specs.size >= 8)
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        specs.forEach { spec ->
            assertTrue(spec.stages.size >= 2)
            assertTrue(spec.stages.all { it.instruction.isNotBlank() && it.outputContract.isNotBlank() })
            assertTrue(spec.id.matches(Regex("^[a-z][a-z0-9_]{2,63}$")))
        }
    }

    @Test
    fun executionChainsEveryStageAndReturnsFinalStage() = runBlocking {
        val spec = AdvancedModelSkill.SPECS.first()
        val bridge = RecordingBridge()
        val result = AdvancedModelSkill(spec).execute(
            mapOf("input" to "Check this claim.", "context" to SkillContext(modelBridge = bridge))
        )
        assertTrue(result.success)
        assertEquals(spec.stages.size, bridge.prompts.size)
        assertEquals("STAGE_${spec.stages.size}", result.data)
        assertEquals(spec.stages.size.toString(), result.metadata["stages"])
        assertTrue(bridge.prompts[1].contains("STAGE_1"))
    }

    @Test
    fun stageFailureStopsPipelineAndIsExplicit() = runBlocking {
        val spec = AdvancedModelSkill.SPECS.first()
        val bridge = RecordingBridge(failAt = 2)
        val result = AdvancedModelSkill(spec).execute(
            mapOf("input" to "Check this claim.", "context" to SkillContext(modelBridge = bridge))
        )
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Stage 2"))
        assertEquals(2, bridge.prompts.size)
    }

    @Test
    fun missingModelDoesNotProduceAFalseSuccess() = runBlocking {
        val skill = AdvancedModelSkill(AdvancedModelSkill.SPECS.first())
        val result = skill.execute(mapOf("input" to "hello", "context" to SkillContext()))
        assertFalse(result.success)
        assertTrue(result.error!!.contains("active AI model"))
    }

    private class RecordingBridge(private val failAt: Int? = null) : SkillModelBridge {
        val prompts = mutableListOf<String>()

        override suspend fun complete(prompt: String, systemPrompt: String, maxTokens: Int): String {
            prompts += prompt
            val stage = prompts.size
            if (stage == failAt) error("synthetic stage failure")
            return "STAGE_$stage"
        }
    }
}
