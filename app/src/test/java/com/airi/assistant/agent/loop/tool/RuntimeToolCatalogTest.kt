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
    fun requestedUnavailableConnectorIsExposedWithReadinessReason() {
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

        val schema = result.schemas.single()
        assertTrue(schema.description.contains("READINESS=not_ready"))
        assertTrue(schema.description.contains("not connected and healthy"))
        assertTrue(result.filtered.isEmpty())
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
}
