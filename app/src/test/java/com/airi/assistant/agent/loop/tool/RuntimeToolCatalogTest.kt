package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToolCatalogTest {
    private val connectorSchema = ToolSchema(
        name = "connector_google_gmail_read",
        description = "Read Gmail",
        category = ToolSchema.Category.EXTERNAL,
    )

    @Test
    fun unavailableConnectorIsHiddenWhenRequestDoesNotNeedConnector() {
        val result = RuntimeToolCatalog.assemble(
            builtins = listOf(BuiltinTools.CURRENT_TIME),
            skills = emptyList(),
            connectors = listOf(RuntimeToolContract.connector(connectorSchema, available = false)),
            intent = CapabilityIntentDetector.Intent(),
        )

        assertTrue(result.schemas.any { it.name == "current_time" })
        assertFalse(result.schemas.any { it.name == connectorSchema.name })
        assertEquals(RuntimeToolCatalog.Reason.CONNECTOR_NOT_REQUESTED, result.filtered.single().reason)
    }

    @Test
    fun readyConnectorIsStillHiddenUnlessThisRequestSelectsIt() {
        val result = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = emptyList(),
            connectors = listOf(RuntimeToolContract.connector(connectorSchema, available = true)),
            intent = CapabilityIntentDetector.Intent(),
        )

        assertTrue(result.schemas.isEmpty())
        assertEquals(RuntimeToolCatalog.Reason.CONNECTOR_NOT_REQUESTED, result.filtered.single().reason)
    }

    @Test
    fun requestedReadyConnectorIsPrioritizedAheadOfUnrelatedTools() {
        val intent = CapabilityIntentDetector.Intent(
            capabilities = setOf(CapabilityIntentDetector.Capability.CONNECTOR_READ),
            connectorIds = setOf("google"),
        )
        val result = RuntimeToolCatalog.assemble(
            builtins = listOf(BuiltinTools.CURRENT_TIME),
            skills = listOf(ToolSchema("skill_generic", "Generic skill")),
            connectors = listOf(
                RuntimeToolContract.connector(connectorSchema, available = true),
                RuntimeToolContract.connector(
                    connectorSchema.copy(name = "connector_github_list_repos"),
                    available = true,
                ),
            ),
            intent = intent,
        )

        assertEquals(connectorSchema.name, result.schemas.first().name)
        assertTrue(result.schemas.any { it.name == "current_time" })
        assertTrue(result.schemas.any { it.name == "skill_generic" })
        assertEquals("connector_github_list_repos", result.filtered.single().toolName)
    }

    @Test
    fun requestedUnavailableConnectorIsNotExposedAsExecutable() {
        val intent = CapabilityIntentDetector.Intent(
            capabilities = setOf(CapabilityIntentDetector.Capability.CONNECTOR_READ),
            connectorIds = setOf("google"),
        )
        val result = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = emptyList(),
            connectors = listOf(RuntimeToolContract.connector(connectorSchema, available = false)),
            intent = intent,
        )

        assertTrue(result.schemas.isEmpty())
        assertEquals(RuntimeToolCatalog.Reason.NOT_READY, result.filtered.single().reason)
        assertEquals(connectorSchema.name, result.filtered.single().toolName)
    }

    @Test
    fun duplicateToolNamesAreExposedOnce() {
        val same = ToolSchema("same_tool", "builtin")
        val result = RuntimeToolCatalog.assemble(
            builtins = listOf(same),
            skills = listOf(same),
            connectors = emptyList(),
            intent = CapabilityIntentDetector.Intent(),
        )
        assertEquals(1, result.schemas.count { it.name == "same_tool" })
    }

    @Test
    fun inventorySeparatesRegistrationIntentSelectionExposureAndDispatchAdmission() {
        val intent = CapabilityIntentDetector.Intent(
            capabilities = setOf(CapabilityIntentDetector.Capability.CONNECTOR_READ),
            connectorIds = setOf("google"),
        )
        val result = RuntimeToolCatalog.assemble(
            builtins = listOf(BuiltinTools.CURRENT_TIME),
            skills = emptyList(),
            connectors = listOf(RuntimeToolContract.connector(connectorSchema, available = false)),
            intent = intent,
        )
        val inventory = RuntimeCapabilityInventory.from(
            catalog = result,
            dispatcherAdmittedNames = setOf("current_time"),
        )

        val builtin = inventory.entries.single { it.id == "current_time" }
        assertTrue(builtin.registered)
        assertTrue(builtin.inRequestCatalog)
        assertTrue(builtin.exposedToAgent)
        assertTrue(builtin.admittedToDispatcher)

        val connector = inventory.entries.single { it.id == connectorSchema.name }
        assertTrue(connector.registered)
        assertFalse(connector.inRequestCatalog)
        assertFalse(connector.exposedToAgent)
        assertFalse(connector.admittedToDispatcher)
        assertTrue(inventory.promptBlock("Tell me about Gmail").contains("not proof that a provider"))
    }

    @Test
    fun policyBlockedTerminalIsNotExposedAndInventoryNamesTheBlock() {
        val terminal = ToolSchema("terminal_execute", "Run a shell command")
        val result = RuntimeToolCatalog.assemble(
            builtins = listOf(terminal),
            skills = emptyList(),
            connectors = emptyList(),
            intent = CapabilityIntentDetector.Intent(),
        )
        val inventory = RuntimeCapabilityInventory.from(result, emptySet())
        val terminalEntry = inventory.entries.single()

        assertTrue(result.schemas.isEmpty())
        assertEquals(RuntimeCapabilityInventory.Availability.BLOCKED, terminalEntry.availability)
        assertFalse(terminalEntry.exposedToAgent)
        assertTrue(inventory.promptBlock("Can you use the terminal?").contains("disabled until an isolated process boundary"))
    }

    @Test
    fun sideEffectsWithoutTypedTaskRouteAreNotAdvertisedAsExecutable() {
        val result = RuntimeToolCatalog.assemble(
            builtins = BuiltinTools.ALL,
            skills = emptyList(),
            connectors = emptyList(),
            intent = CapabilityIntentDetector.Intent(),
        )
        val exposed = result.schemas.mapTo(mutableSetOf()) { it.name }
        val blocked = setOf("calendar_create", "create_note", "set_alarm", "open_app", "tap", "type_text", "scroll_down", "go_back", "flashlight", "terminal_execute")

        assertTrue(exposed.intersect(blocked).isEmpty())
        assertTrue(blocked.all { name -> result.filtered.any { it.toolName == name } })
        assertFalse(exposed.contains("calendar_create"))
    }
}
