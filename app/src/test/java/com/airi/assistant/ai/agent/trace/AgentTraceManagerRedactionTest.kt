package com.airi.assistant.ai.agent.trace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceManagerRedactionTest {

    @Test
    fun storedTrace_redactsInputStepPayloadErrorAndResult() {
        val manager = AgentTraceManager.instance
        manager.clearTraces()
        val traceId = manager.startTrace("Authorization: Bearer secret-agent-token-1234567890")
        manager.addStep(
            traceId,
            AgentStep(
                stepIndex = 0,
                type = AgentStepType.TOOL,
                name = "file_search",
                inputParams = mapOf("cookie" to "session=private-cookie"),
                outputSummary = "password=unsafe-value",
                error = "api_key=fixture-google-key-value-1234567890",
            )
        )
        manager.finalizeTrace(traceId, "/data/user/0/com.airi.assistant/private.txt", success = false)

        val trace = requireNotNull(manager.getTrace(traceId))
        val flattened = listOf(
            trace.originalInput,
            trace.steps.single().inputParams.values.single(),
            trace.steps.single().outputSummary,
            trace.steps.single().error.orEmpty(),
            trace.finalResult,
        ).joinToString("\n")

        assertTrue(flattened.contains("[SECRET_REDACTED]") || flattened.contains("[KEY_REDACTED]"))
        assertTrue(flattened.contains("[PATH_REDACTED]"))
        assertFalse(flattened.contains("secret-agent-token-1234567890"))
        assertFalse(flattened.contains("private-cookie"))
        assertFalse(flattened.contains("unsafe-value"))
        assertFalse(flattened.contains("fixture-google-key-value-1234567890"))
        assertFalse(flattened.contains("/data/user/0/com.airi.assistant/private.txt"))
    }
}
