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
    }

    @Test
    fun unknownToolsRemainDenied() {
        assertFalse(ToolPermissionPolicy.permissionFor("unregistered_tool") != null)
    }
}
