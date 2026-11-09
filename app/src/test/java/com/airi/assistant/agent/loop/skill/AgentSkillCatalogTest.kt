package com.airi.assistant.agent.loop.skill

import com.airi.assistant.agent.loop.tool.BuiltinTools
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillCatalogTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (dir != null && File(dir, "skills").isDirectory && File(dir, "app").isDirectory) return dir!!
            dir = dir?.parentFile
        }
        error("Repository root with skills/ and app/ not found from ${System.getProperty("user.dir")}")
    }

    private fun skillFiles(): List<File> =
        File(repoRoot(), "skills").listFiles { f -> f.isDirectory }.orEmpty()
            .flatMap { category -> category.listFiles { f -> f.isDirectory }.orEmpty().toList() }
            .map { File(it, "SKILL.md") }
            .filter { it.isFile }

    @Test
    fun catalogHasTheTwoPythonSkills() {
        assertTrue(AgentSkillCatalog.names.containsAll(listOf("python_app", "pydroid3_compat")))
    }

    @Test
    fun promptCatalogCarriesNamesAndDescriptionsOnly() {
        val catalog = AgentSkillCatalog.promptCatalog()
        AgentSkillCatalog.skills.forEach { skill ->
            assertTrue(catalog.contains(skill.name))
            assertTrue(catalog.contains(skill.description))
            assertFalse("body must not be in the prompt", catalog.contains(skill.body.lines().first { it.isNotBlank() }))
        }
    }

    @Test
    fun loadReturnsTheFullBodyAndAcceptsLooseNames() {
        val loaded = AgentSkillCatalog.load("Python-App")
        assertTrue(loaded.isSuccess)
        assertTrue(loaded.getOrThrow().contains("## 3. Check"))
    }

    @Test
    fun unknownSkillListsTheValidChoices() {
        val failure = AgentSkillCatalog.load("nope").exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("python_app"))
    }

    @Test
    fun loadSkillToolSchemaListsEveryCatalogSkill() {
        val schema = BuiltinTools.LOAD_SKILL
        assertEquals("load_skill", schema.name)
        assertEquals(AgentSkillCatalog.names.toSet(), schema.parameters.getValue("name").allowedValues)
        assertFalse(schema.dangerous)
        assertTrue(BuiltinTools.ALL.any { it.name == "load_skill" })
    }

    @Test
    fun skillMdFilesInTheRepositoryMatchTheCatalogExactly() {
        AgentSkillCatalog.skills.forEach { skill ->
            val file = skillFiles().firstOrNull { it.parentFile.name == skill.name }
            assertNotNull("skills/*/${skill.name}/SKILL.md is missing", file)
            val expected = "---\nname: ${skill.name}\ndescription: ${skill.description}\n---\n\n${skill.body}"
            assertEquals("SKILL.md for ${skill.name} drifted from AgentSkillCatalog", expected, file!!.readText())
        }
    }

    /**
     * Every skills/<category>/<id>/SKILL.md must be backed by something: either its id appears as
     * a string literal in the skill implementation sources (the many "profile" skills are declared
     * there, e.g. DefensiveEngineeringSkill), or it is an agent-loop skill in [AgentSkillCatalog].
     * A folder with neither is documentation without an implementation and fails this test.
     */
    @Test
    fun everySkillFolderIsBackedByAnImplementationOrTheAgentCatalog() {
        val sources = File(repoRoot(), "app/src/main/java/com/airi/assistant/ai/skills")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val unbacked = skillFiles()
            .map { it.parentFile.name }
            .filter { id -> !sources.contains("\"$id\"") && id !in AgentSkillCatalog.names }
        assertTrue("SKILL.md folders without an implementation: $unbacked", unbacked.isEmpty())
    }
}
