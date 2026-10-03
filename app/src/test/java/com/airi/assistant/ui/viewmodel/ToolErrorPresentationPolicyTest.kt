package com.airi.assistant.ui.viewmodel

import com.airi.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolErrorPresentationPolicyTest {
    @Test
    fun authRequiredUsesGoogleMessageForGmailTools() {
        val result = ToolErrorPresentationPolicy.resolve(
            toolName = "gmail_search",
            toolResult = "Error [auth_required]: provider details must not reach the UI",
        )

        requireNotNull(result)
        assertEquals("auth_required", result.code)
        assertEquals(R.string.tool_error_auth_required, result.messageResId)
        assertEquals(listOf("Google"), result.formatArgs)
    }

    @Test
    fun networkAndMemoryCodesUseActionableMessages() {
        val network = ToolErrorPresentationPolicy.resolveCode("network_unavailable", "web_search")
        val memory = ToolErrorPresentationPolicy.resolveCode("memory_unavailable", "memory_recall")

        assertEquals(R.string.tool_error_network_unavailable, network.messageResId)
        assertEquals(R.string.tool_error_memory_unavailable, memory.messageResId)
    }

    @Test
    fun unknownCodeFallsBackWithoutExposingTechnicalDetails() {
        val result = ToolErrorPresentationPolicy.resolveCode(
            "provider_internal_stack_trace",
            "connector_tool",
        )

        assertEquals(R.string.tool_error_generic, result.messageResId)
        assertTrue(result.code == "provider_internal_stack_trace")
    }
}
