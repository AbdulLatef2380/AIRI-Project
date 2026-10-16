package com.airi.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExecutionFirewallTest {

    @Test
    fun actualAgentLoopToolsAreMappedToScopedPermissions() {
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
            ToolPermissionPolicy.permissionFor("read_screen")
        )
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.ACCESSIBILITY_ACTIONS,
            ToolPermissionPolicy.permissionFor(" tap ")
        )
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.SEARCH_WEB,
            ToolPermissionPolicy.permissionFor("fetch_url")
        )
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.READ_MEMORY,
            ToolPermissionPolicy.permissionFor("memory_recall")
        )
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.WRITE_NOTES,
            ToolPermissionPolicy.permissionFor("create_note")
        )
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.EXECUTE_TERMINAL,
            ToolPermissionPolicy.permissionFor("terminal_execute")
        )
    }

    @Test
    fun unknownToolsRemainDenied() {
        assertFalse(ToolPermissionPolicy.permissionFor("unregistered_tool") != null)
    }

    @Test
    fun dynamicConnectorAndSkillToolsReachTheirGovernedBridges() {
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.CALL_CONNECTOR_API,
            ToolPermissionPolicy.permissionFor("connector_github_get_repos")
        )
        assertEquals(
            ScopedPermissionRegistry.AgentPermission.MANAGE_SKILLS,
            ToolPermissionPolicy.permissionFor("skill_drive_search")
        )
    }

    @Test
    fun permissionGrantAndFirewallGuardDoNotDependOnAndroidLogger() {
        val registry = ScopedPermissionRegistry()
        registry.grant(
            "agent_loop",
            ScopedPermissionRegistry.AgentPermission.CALL_CONNECTOR_API,
        )

        val firewall = ExecutionFirewall(registry)
        firewall.guard("agent_loop", "connector_github_get_repos")
    }

    @Test
    fun agentLoopCanGuardDynamicConnectorAndSkillCalls() {
        val registry = ScopedPermissionRegistry()
        // Keep this JVM unit test independent from Application/LoggingService
        // bootstrap; it verifies the firewall contract, not app startup.
        registry.grant(
            "agent_loop",
            ScopedPermissionRegistry.AgentPermission.CALL_CONNECTOR_API,
            ScopedPermissionRegistry.AgentPermission.MANAGE_SKILLS,
        )
        val firewall = ExecutionFirewall(registry)

        firewall.guard("agent_loop", "connector_github_get_repos")
        firewall.guard("agent_loop", "skill_drive_search")
    }
}
