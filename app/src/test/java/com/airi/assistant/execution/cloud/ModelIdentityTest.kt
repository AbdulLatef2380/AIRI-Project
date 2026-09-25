package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIdentityTest {
    @Test
    fun explicitModelIsResolvedOnlyForItsOwningProvider() {
        val request = ExecutionRequest(
            prompt = "hello",
            requestedProviderId = "openai",
            requestedModelId = "gpt-test-model"
        )

        assertEquals("gpt-test-model", CloudAdapterFactory.resolveRequestedModel(CloudProvider.OPENAI, request))
        assertEquals(null, CloudAdapterFactory.resolveRequestedModel(CloudProvider.GEMINI, request))
    }

    @Test
    fun requestCarriesProviderAndModelIdentityWithoutConflatingResolvedFields() {
        val request = ExecutionRequest(
            prompt = "hello",
            requestedProviderId = "anthropic",
            requestedModelId = "claude-test",
            resolvedProviderId = "anthropic",
            resolvedModelId = "claude-test"
        )

        assertEquals("anthropic", request.requestedProviderId)
        assertEquals("claude-test", request.requestedModelId)
        assertEquals("anthropic", request.resolvedProviderId)
        assertEquals("claude-test", request.resolvedModelId)
    }

    @Test
    fun liveOpenRouterCatalogParserReadsOnlyDataModelIds() {
        val ids = OpenRouterModelRegistry.parseModelIds(
            """{"object":"list","data":[{"id":"model/a"},{"id":"model/b"}]}"""
        )

        assertEquals(setOf("model/a", "model/b"), ids)
        assertTrue("model/a" in ids)
    }
}
