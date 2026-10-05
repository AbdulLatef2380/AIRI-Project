package com.airi.assistant.security

import com.airi.assistant.security.ScopedPermissionRegistry.AgentPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionFirewallMappingTest {

    private val firewall = ExecutionFirewall(ScopedPermissionRegistry())

    @Test
    fun mapsEveryAgentLoopBuiltinToItsCapability() {
        val expected = mapOf(
            "read_screen" to AgentPermission.ACCESSIBILITY_ACTIONS,
            "open_app" to AgentPermission.TRIGGER_INTENT,
            "tap" to AgentPermission.ACCESSIBILITY_ACTIONS,
            "type_text" to AgentPermission.ACCESSIBILITY_ACTIONS,
            "scroll_down" to AgentPermission.ACCESSIBILITY_ACTIONS,
            "go_back" to AgentPermission.ACCESSIBILITY_ACTIONS,
            "web_search" to AgentPermission.SEARCH_WEB,
            "fetch_url" to AgentPermission.SEARCH_WEB,
            "memory_recall" to AgentPermission.READ_MEMORY,
            "calendar_read" to AgentPermission.READ_CALENDAR,
            "calendar_create" to AgentPermission.WRITE_CALENDAR,
            "set_alarm" to AgentPermission.SET_ALARM,
            "create_note" to AgentPermission.WRITE_NOTES,
            "ask_confirmation" to AgentPermission.REQUEST_CONFIRMATION
        )

        expected.forEach { (tool, permission) ->
            assertEquals(setOf(permission), firewall.resolveRequiredPermissions(tool))
        }
    }

    @Test
    fun acceptsDeclaredSkillPermissionsButRejectsUnadvertisedNames() {
        assertEquals(
            setOf(AgentPermission.CALL_TELEGRAM_API),
            firewall.resolveRequiredPermissions(
                "skill_telegram_messenger",
                setOf(AgentPermission.CALL_TELEGRAM_API)
            )
        )
        assertNull(firewall.resolveRequiredPermissions("skill_not_registered"))
    }
}
