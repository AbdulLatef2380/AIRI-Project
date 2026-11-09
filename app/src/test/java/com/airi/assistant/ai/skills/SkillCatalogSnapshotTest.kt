package com.airi.assistant.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillCatalogSnapshotTest {
    @Test fun snapshotRequiresExactlyTheCapturedIds() {
        val skill = object : AiriSkill {
            override val skillId = "demo"
            override val name = "Demo"
            override val description = "A deterministic test skill"
            override fun score(input: String, context: SkillContext) = 0
            override suspend fun execute(params: Map<String, Any>) = SkillResult(true, "ok", null)
        }
        val snapshot = SkillCatalogSnapshot(7L, listOf(skill), setOf("demo"))
        assertEquals(7L, snapshot.version)
        assertEquals(listOf(skill), snapshot.skills)
        assertEquals(setOf("demo"), snapshot.registeredIds)
    }
}
