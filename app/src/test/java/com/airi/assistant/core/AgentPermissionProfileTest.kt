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
}
