package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class StructuredOutputJsonTest {
    private val spec = ExecutionRequest.StructuredOutputSpec(
        name = "airi_result",
        schemaJson = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}",
    )

    @Test
    fun providerEnvelopesUseTheirNativeStructuredOutputContracts() {
        JSONObject("{${StructuredOutputJson.openAiChat(spec)}}")
        JSONObject("{${StructuredOutputJson.openAiResponses(spec)}}")
        JSONObject("{${StructuredOutputJson.gemini(spec)}}")
        JSONObject("{${StructuredOutputJson.anthropic(spec)}}")
        assertTrue(StructuredOutputJson.openAiChat(spec).contains("\"response_format\":{\"type\":\"json_schema\""))
        assertTrue(StructuredOutputJson.openAiResponses(spec).contains("\"text\":{\"format\":{\"type\":\"json_schema\""))
        assertTrue(StructuredOutputJson.gemini(spec).contains("\"responseMimeType\":\"application/json\""))
        assertTrue(StructuredOutputJson.gemini(spec).contains("\"responseSchema\":{"))
        assertTrue(StructuredOutputJson.anthropic(spec).contains("\"output_config\":{\"format\":{\"type\":\"json_schema\""))
    }

    @Test
    fun malformedOrMissingSchemaFailsClosed() {
        assertTrue(StructuredOutputJson.validationError(ExecutionRequest(
            prompt = "x",
            requiresStructuredOutput = true,
        )).orEmpty().isNotBlank())
        assertTrue(StructuredOutputJson.validationError(ExecutionRequest(
            prompt = "x",
            structuredOutput = spec.copy(name = "unsafe name"),
        )).orEmpty().isNotBlank())
        assertFalse(StructuredOutputJson.validationError(ExecutionRequest(
            prompt = "x",
            requiresStructuredOutput = true,
            structuredOutput = spec,
        )).orEmpty().isNotBlank())
    }

    @Test
    fun onlyVerifiedFirstPartyProvidersCanGuaranteeSchema() {
        assertTrue(StructuredOutputJson.hasNativeGuarantee(CloudProvider.OPENAI))
        assertTrue(StructuredOutputJson.hasNativeGuarantee(CloudProvider.GEMINI))
        assertTrue(StructuredOutputJson.hasNativeGuarantee(CloudProvider.ANTHROPIC))
        assertFalse(StructuredOutputJson.hasNativeGuarantee(CloudProvider.OPENROUTER))
        assertFalse(StructuredOutputJson.hasNativeGuarantee(CloudProvider.CUSTOM))
        assertFalse(StructuredOutputJson.hasNativeGuarantee(CloudProvider.KIMI))
    }
}
