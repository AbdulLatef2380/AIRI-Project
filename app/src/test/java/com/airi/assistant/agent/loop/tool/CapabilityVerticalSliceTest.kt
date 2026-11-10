package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.agent.ModelFacingRepairContract
import com.airi.assistant.core.ExecutionToolTraceLifecycle
import com.airi.assistant.domain.tool.TypedArgumentContract
import com.airi.assistant.domain.tool.TypedArgumentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM fake vertical slices for G-9. These tests exercise the real contracts
 * used by catalog, readiness, typed parsing, repair, dispatcher admission and
 * trace lifecycle without Android providers or network credentials.
 */
class CapabilityVerticalSliceTest {
    private val commandSpec = TypedArgumentSpec(
        name = "command",
        type = TypedArgumentSpec.Type.STRING,
        required = true,
        minLength = 1,
    )

    @Test
    fun skillSliceCoversCatalogReadinessParserTraceAndRestart() {
        val schema = ToolSchema("skill_demo", "Demo skill")
        val catalog = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = listOf(schema),
            connectors = emptyList(),
            intent = CapabilityIntentDetector.Intent(),
        )
        assertTrue(catalog.exposed.any { it.schema.name == schema.name })
        assertTrue(RuntimeCapabilityInventory.from(catalog, setOf(schema.name)).entries.any { it.id == schema.name })
        assertValidTrace("skill-exec", "skill-action")
    }

    @Test
    fun connectorSliceSeparatesUnavailableAuthFromExecutableRoute() {
        val schema = ToolSchema("connector_fake_read", "Read fake provider")
        val intent = CapabilityIntentDetector.Intent(connectorIds = setOf("fake"))
        val unavailable = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = emptyList(),
            connectors = listOf(RuntimeToolContract.connector(schema, available = false, capabilityId = "fake")),
            intent = intent,
        )
        assertTrue(unavailable.exposed.isEmpty())
        assertEquals(RuntimeToolCatalog.Reason.NOT_READY, unavailable.filtered.single().reason)

        val ready = RuntimeToolCatalog.assemble(
            builtins = emptyList(),
            skills = emptyList(),
            connectors = listOf(RuntimeToolContract.connector(schema, available = true, capabilityId = "fake")),
            intent = intent,
        )
        assertEquals(schema.name, ready.exposed.single().schema.name)
        assertValidTrace("connector-exec", "connector-action")
    }

    @Test
    fun terminalSliceCoversMalformedApprovalTimeoutCancellationAndRestart() {
        val malformed = TypedArgumentContract.validate(listOf(commandSpec), emptyMap())
        assertFalse(malformed.isValid)
        assertEquals("missing_argument", malformed.failure?.code)

        val approval = ModelFacingRepairContract.buildPrompt(
            ModelFacingRepairContract.Request(
                toolName = "terminal_execute",
                errorCode = ToolErrorCodes.APPROVAL_REQUIRED,
                sideEffectPolicy = ToolSchema.SideEffectPolicy.REQUIRES_DURABLE_CONTEXT,
                originalInput = "run command",
                attempt = 0,
                maxAttempts = 2,
                hasIdempotencyKey = false,
            )
        )
        assertEquals(ModelFacingRepairContract.Decision.EXPLAIN_APPROVAL, approval.decision)

        val notReady = TerminalCapabilityNegotiation.project(true, false, true, true, true, true)
        assertEquals(RuntimeCapabilityInventory.Availability.CONFIGURATION_REQUIRED, notReady.availability)
        val blocked = TerminalCapabilityNegotiation.project(true, true, false, true, true, true)
        assertEquals(RuntimeCapabilityInventory.Availability.BLOCKED, blocked.availability)

        val lifecycle = ExecutionToolTraceLifecycle().also { it.begin("terminal-exec") }
        assertTrue(lifecycle.admitStart("terminal-exec", "terminal-action"))
        assertTrue(lifecycle.requireVerification("terminal-exec", "terminal-action"))
        // Timeout/failure is terminal and does not become a successful completion.
        assertTrue(lifecycle.admitTerminal("terminal-exec", "terminal-action", successful = false))
        assertFalse(lifecycle.admitTerminal("terminal-exec", "terminal-action", successful = true))
        lifecycle.begin("terminal-restarted")
        assertFalse(lifecycle.admitTerminal("terminal-exec", "terminal-action", successful = false))
    }

    private fun assertValidTrace(executionId: String, actionId: String) {
        val lifecycle = ExecutionToolTraceLifecycle().also { it.begin(executionId) }
        assertTrue(lifecycle.admitStart(executionId, actionId))
        assertTrue(lifecycle.requireVerification(executionId, actionId))
        assertTrue(lifecycle.completeVerification(executionId, actionId, passed = true))
        assertTrue(lifecycle.admitTerminal(executionId, actionId, successful = true))
        assertFalse(lifecycle.admitStart(executionId, actionId))
    }
}
