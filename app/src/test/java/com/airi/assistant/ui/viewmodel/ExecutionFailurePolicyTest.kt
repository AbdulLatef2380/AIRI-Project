package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionFailurePolicyTest {
    @Test
    fun providerRejectionIsReportedAtTheProviderStage() {
        val result = ExecutionFailurePolicy.classify(
            "The cloud provider rejected this request. Review the model settings and try again.",
            responseStarted = false
        )

        assertEquals(ExecutionFailureKind.PROVIDER_REJECTED, result.kind)
        assertEquals(ExecutionFailureStage.PROVIDER, result.stage)
    }

    @Test
    fun providerDiagnosticsKeepTheirSpecificCause() {
        val cases = listOf(
            "Invalid API key for Gemini. Check Settings → API Keys." to ExecutionFailureKind.PROVIDER_CREDENTIALS,
            "The selected model is not available at OpenRouter. Choose another model." to ExecutionFailureKind.PROVIDER_MODEL_UNAVAILABLE,
            "Gemini quota exhausted. Check your billing dashboard." to ExecutionFailureKind.PROVIDER_QUOTA_EXHAUSTED,
            "Prompt too long for Gemini. Try a shorter message." to ExecutionFailureKind.PROVIDER_CONTEXT_LIMIT,
            "Gemini is rate-limiting requests. Please wait a moment." to ExecutionFailureKind.PROVIDER_RATE_LIMIT,
            "Custom endpoint timed out. Check your internet connection." to ExecutionFailureKind.PROVIDER_TIMEOUT,
            "Connection to Ollama was lost mid-stream." to ExecutionFailureKind.PROVIDER_UNAVAILABLE
        )

        cases.forEach { (message, expectedKind) ->
            val result = ExecutionFailurePolicy.classify(message, responseStarted = false)
            assertEquals(message, expectedKind, result.kind)
            assertEquals(message, ExecutionFailureStage.PROVIDER, result.stage)
        }
    }

    @Test
    fun contextAndRateLimitFailuresKeepTheirSpecificCause() {
        assertEquals(
            ExecutionFailureKind.PROVIDER_CONTEXT_LIMIT,
            ExecutionFailurePolicy.classify("Prompt exceeds model context window", false).kind
        )
        assertEquals(
            ExecutionFailureKind.PROVIDER_RATE_LIMIT,
            ExecutionFailurePolicy.classify("Too many requests (HTTP 429)", false).kind
        )
    }

    @Test
    fun genericFailureStageTracksWhetherTheModelStartedResponding() {
        val beforeResponse = ExecutionFailurePolicy.classify("unexpected local failure", false)
        val duringResponse = ExecutionFailurePolicy.classify("unexpected local failure", true)

        assertEquals(ExecutionFailureStage.AGENT_LOOP, beforeResponse.stage)
        assertEquals(ExecutionFailureStage.RESPONSE, duringResponse.stage)
    }
}
