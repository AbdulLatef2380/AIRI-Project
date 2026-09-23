package com.airi.assistant.ai.skills

import com.airi.assistant.ai.skills.impl.ModelUtilitySkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUtilitySkillTest {
    @Test
    fun catalogHasUniqueRunnableSpecs() {
        val specs = ModelUtilitySkill.SPECS
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        assertTrue(specs.size >= 10)
        specs.forEach { spec ->
            assertTrue(spec.id.matches(Regex("^[a-z][a-z0-9_]{2,63}$")))
            assertTrue(spec.instructions.isNotBlank())
            assertTrue(spec.outputContract.isNotBlank())
            assertTrue(spec.parameters.containsKey("input"))
        }
    }

    @Test
    fun routingKeywordsProducePositiveScores() {
        ModelUtilitySkill.SPECS.forEach { spec ->
            val skill = ModelUtilitySkill(spec)
            assertTrue("${spec.id} did not route", skill.score(spec.keywords.first(), SkillContext()) > 0)
            assertEquals(spec.id, skill.skillId)
            assertFalse(skill.supportsStreaming)
        }
    }

    @Test
    fun executionUsesActiveModelBridgeAndReturnsMetadata() = runBlocking {
        val skill = ModelUtilitySkill(ModelUtilitySkill.SPECS.first())
        val result = skill.execute(
            mapOf(
                "input" to "Summarize this short document.",
                "context" to SkillContext(modelBridge = FakeBridge())
            )
        )
        assertTrue(result.success)
        assertEquals(skill.skillId, result.skillName)
        assertEquals("active_bridge", result.metadata["model_execution"])
        assertTrue(result.data.contains("FAKE_RESULT"))
    }

    @Test
    fun executionFailsClearlyWithoutContextOrModel() = runBlocking {
        val skill = ModelUtilitySkill(ModelUtilitySkill.SPECS.first())
        val noContext = skill.execute(mapOf("input" to "hello"))
        assertFalse(noContext.success)
        assertTrue(noContext.error!!.contains("context"))

        val noModel = skill.execute(mapOf("input" to "hello", "context" to SkillContext()))
        assertFalse(noModel.success)
        assertTrue(noModel.error!!.contains("active AI model"))
    }

    private class FakeBridge : SkillModelBridge {
        override suspend fun complete(prompt: String, systemPrompt: String, maxTokens: Int): String =
            "FAKE_RESULT: ${prompt.substringBefore("User input:").trim()}"
    }
}
