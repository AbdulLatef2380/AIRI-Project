package com.airi.assistant.ai.skills

import com.airi.assistant.ai.skills.impl.DefensiveEngineeringSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefensiveEngineeringSkillTest {
    @Test
    fun catalogContainsOnlyNewRequestedCapabilities() {
        val specs = DefensiveEngineeringSkill.SPECS
        assertEquals(23, specs.size)
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        assertEquals(14, specs.count { it.defensive })
        assertEquals(9, specs.count { !it.defensive })
        specs.forEach { spec ->
            assertEquals(3, spec.stages.size)
            assertTrue(spec.stages.all { it.instruction.isNotBlank() && it.outputContract.isNotBlank() })
            assertTrue(spec.limitations.isNotEmpty())
            assertFalse(spec.id in setOf("code_review_advanced", "performance_profiler", "security_threat_model", "incident_analyzer"))
        }
    }

    @Test
    fun defensiveSkillsAreAnalysisOnlyAndStateTheirBoundary() = runBlocking {
        val spec = DefensiveEngineeringSkill.SPECS.first { it.id == "vulnerability_analysis" }
        val bridge = RecordingBridge()
        val result = DefensiveEngineeringSkill(spec).execute(
            mapOf("input" to "CVE report: affected package and supplied impact", "context" to SkillContext(modelBridge = bridge))
        )
        assertTrue(result.success)
        assertEquals("true", result.metadata["defensive_only"])
        assertTrue(bridge.prompts.all { it.contains("Defensive boundary") })
        assertTrue(bridge.prompts.all { it.contains("Do not output exploit payloads") })
        assertTrue(DefensiveEngineeringSkill(spec).toolDefinitions.all { !it.dangerous })
    }

    @Test
    fun programmingSkillChainsEvidenceThroughAllStages() = runBlocking {
        val spec = DefensiveEngineeringSkill.SPECS.first { it.id == "debugging" }
        val bridge = RecordingBridge()
        val result = DefensiveEngineeringSkill(spec).execute(
            mapOf("input" to "NullPointerException in parser", "context" to SkillContext(modelBridge = bridge))
        )
        assertTrue(result.success)
        assertEquals(3, bridge.prompts.size)
        assertEquals("REPORT_3", result.data)
        assertTrue(bridge.prompts[1].contains("REPORT_1"))
        assertTrue(bridge.prompts[2].contains("REPORT_2"))
        assertEquals("false", result.metadata["defensive_only"])
    }

    @Test
    fun failureAndMissingModelAreExplicit() = runBlocking {
        val spec = DefensiveEngineeringSkill.SPECS.first()
        val failed = DefensiveEngineeringSkill(spec).execute(
            mapOf("input" to "evidence", "context" to SkillContext(modelBridge = RecordingBridge(failAt = 2)))
        )
        assertFalse(failed.success)
        assertTrue(failed.error!!.contains("stage 2", ignoreCase = true))

        val missing = DefensiveEngineeringSkill(spec).execute(
            mapOf("input" to "evidence", "context" to SkillContext())
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
