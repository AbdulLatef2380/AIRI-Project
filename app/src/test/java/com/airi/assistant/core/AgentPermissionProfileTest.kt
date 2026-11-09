package com.airi.assistant.core

import com.airi.assistant.ai.QueryType
import com.airi.assistant.agent.loop.tool.ToolSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionProfileTest {
    private val connector = ToolSchema(
        name = "connector_github_list_repos",
        description = "read",
        category = ToolSchema.Category.EXTERNAL,
    )
    private val skill = ToolSchema(
        name = "skill_research",
        description = "skill",
        category = ToolSchema.Category.SEARCH,
    )
    private val memory = ToolSchema(
        name = "memory_recall",
        description = "memory",
        category = ToolSchema.Category.SEARCH,
    )
    private val calendarCreate = ToolSchema(
        name = "calendar_create",
        description = "proposal",
        dangerous = true,
        category = ToolSchema.Category.SYSTEM,
    )
    private val terminal = ToolSchema(
        name = "terminal_execute",
        description = "terminal",
        dangerous = true,
        category = ToolSchema.Category.PRODUCTIVITY,
    )

    private val writeFile = ToolSchema(
        name = "write_file",
        description = "write",
        dangerous = true,
        category = ToolSchema.Category.PRODUCTIVITY,
    )

    @Test
    fun simpleRequestReceivesNoAgentCapabilitySurface() {
        val profile = AgentPermissionProfile.resolve(QueryType.SIMPLE, "model", "provider")
        assertTrue(profile.filterTools(listOf(connector, skill, memory, calendarCreate, terminal)).isEmpty())
        assertFalse(profile.allowReadTools)
    }

    @Test
    fun simpleRequestWithResolvedCapabilityCanReadTools() {
        val profile = AgentPermissionProfile.resolve(
            QueryType.SIMPLE, "model", "provider", toolsRequested = true
        )
        val exposed = profile.filterTools(listOf(connector, memory, calendarCreate))
        assertTrue(exposed.contains(connector))
        assertTrue(exposed.contains(memory))
        assertFalse(exposed.contains(calendarCreate))
        assertTrue(profile.allowReadTools)
    }

    @Test
    fun actionProfileAllowsReadAndExplicitCalendarProposalOnly() {
        val profile = AgentPermissionProfile.resolve(QueryType.ACTION, "model", "provider")
        val exposed = profile.filterTools(listOf(connector, skill, memory, calendarCreate, terminal))
        assertTrue(exposed.contains(connector))
        assertTrue(exposed.contains(skill))
        assertTrue(exposed.contains(memory))
        assertTrue(exposed.contains(calendarCreate))
        assertTrue(exposed.contains(terminal))
        assertTrue(profile.allowTerminalExecute)
        assertTrue(profile.allowApprovedProposals)
    }

    @Test
    fun analyticalProfileDoesNotGrantAutomation() {
        val profile = AgentPermissionProfile.resolve(QueryType.ANALYTICAL, "model", "provider")
        val automation = ToolSchema("open_app", "automation", category = ToolSchema.Category.AUTOMATION)
        assertFalse(profile.filterTools(listOf(automation)).contains(automation))
        assertTrue(profile.allowConnectorRead)
    }

    @Test
    fun actionProfileStillAllowsWriteFileAndTerminalTogether() {
        val profile = AgentPermissionProfile.resolve(QueryType.ACTION, "model", "provider")
        assertTrue(profile.allowFileWrite)
        assertTrue(profile.filterTools(listOf(writeFile, terminal)).containsAll(listOf(writeFile, terminal)))
    }

    @Test
    fun codeTaskGrantsWriteFileWithoutOpeningTheTerminal() {
        val profile = AgentPermissionProfile.resolve(
            QueryType.SIMPLE, "model", "provider", toolsRequested = true, codeTask = true,
        )
        val exposed = profile.filterTools(listOf(writeFile, terminal))
        assertTrue(exposed.contains(writeFile))
        assertFalse(exposed.contains(terminal))
        assertFalse(profile.allowTerminalExecute)
    }

    @Test
    fun writeFileStaysClosedWithoutActionOrCodeTask() {
        val simple = AgentPermissionProfile.resolve(QueryType.SIMPLE, "model", "provider", toolsRequested = true)
        assertFalse(simple.filterTools(listOf(writeFile)).contains(writeFile))
        val noTools = AgentPermissionProfile.resolve(QueryType.SIMPLE, "model", "provider", codeTask = true)
        assertFalse(noTools.filterTools(listOf(writeFile)).contains(writeFile))
    }
}
