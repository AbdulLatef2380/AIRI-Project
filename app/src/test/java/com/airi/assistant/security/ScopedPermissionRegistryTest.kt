package com.airi.assistant.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedPermissionRegistryTest {
    @Test
    fun terminalUiHasOnlyTheTerminalExecutionPermission() {
        val registry = ScopedPermissionRegistry()
        registry.installDefaults()

        assertTrue(registry.check("terminal-ui", ScopedPermissionRegistry.AgentPermission.EXECUTE_TERMINAL))
        assertEquals(
            setOf(ScopedPermissionRegistry.AgentPermission.EXECUTE_TERMINAL),
            registry.grantsFor("terminal-ui"),
        )
        assertFalse(registry.check("terminal-ui", ScopedPermissionRegistry.AgentPermission.READ_FILES))
        assertFalse(registry.check("terminal-ui", ScopedPermissionRegistry.AgentPermission.WRITE_FILES))
        assertFalse(registry.check("terminal-ui", ScopedPermissionRegistry.AgentPermission.CALL_CONNECTOR_API))
    }
}
