package com.airi.assistant.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillResultVerifierTest {
    private val skill = object : AiriSkill {
        override val skillId = "test_skill"
        override val name = "Test Skill"
        override val description = "A test skill"
        override fun score(input: String, context: SkillContext) = 100
        override suspend fun execute(params: Map<String, Any>) =
            SkillResult(success = true, data = "ok")
    }

    @Test
    fun successfulNonBlankResultIsMarkedVerified() {
        val result = SkillResultVerifier.verify(skill, SkillResult(true, "output"))
        assertTrue(result.success)
        assertEquals("true", result.metadata["result_verified"])
        assertEquals("test_skill", result.skillName)
    }

    @Test
    fun blankSuccessIsConvertedToExplicitFailure() {
        val result = SkillResultVerifier.verify(skill, SkillResult(true, ""))
        assertFalse(result.success)
        assertEquals("false", result.metadata["result_verified"])
        assertTrue(result.error!!.contains("without output"))
    }
}
