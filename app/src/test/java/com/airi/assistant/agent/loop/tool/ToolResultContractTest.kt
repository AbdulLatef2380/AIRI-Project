package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultContractTest {
    @Test
    fun connectorCodesNormalizeToStableAgentCodes() {
        assertEquals(ToolErrorCodes.AUTH_REQUIRED, ToolErrorCodes.fromConnector("unauthorized"))
        assertEquals(ToolErrorCodes.INVALID_ARGUMENT, ToolErrorCodes.fromConnector("missing_param"))
        assertEquals(ToolErrorCodes.CONNECTOR_FAILED, ToolErrorCodes.fromConnector("api_error"))
        assertEquals(ToolErrorCodes.TOOL_NOT_FOUND, ToolErrorCodes.fromConnector("not_found"))
    }

    @Test
    fun errorCarriesMachineCodeRetryabilityAndProvenance() {
        val result = ToolDispatcher.ToolResult.Error(
            message = "Google authorization is required",
            code = ToolErrorCodes.AUTH_REQUIRED,
            retryable = false,
            provenance = ToolProvenance.CONNECTOR,
        )
        assertEquals(ToolErrorCodes.AUTH_REQUIRED, result.code)
        assertTrue(!result.retryable)
        assertEquals(ToolProvenance.CONNECTOR, result.provenance)
    }

    @Test
    fun standardCodesCoverCoreRuntimeFailureClasses() {
        val codes = setOf(
            ToolErrorCodes.MEMORY_UNAVAILABLE,
            ToolErrorCodes.NETWORK_UNAVAILABLE,
            ToolErrorCodes.NOT_CONNECTED,
            ToolErrorCodes.AUTH_REQUIRED,
            ToolErrorCodes.TOOL_NOT_FOUND,
        )
        assertEquals(5, codes.size)
    }
}
