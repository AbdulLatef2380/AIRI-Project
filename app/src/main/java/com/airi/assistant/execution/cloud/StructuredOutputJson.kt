package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.CloudProvider
import org.json.JSONObject

/** Provider-specific envelopes for a provider-neutral final JSON schema. */
internal object StructuredOutputJson {
    fun validationError(request: ExecutionRequest): String? {
        val spec = request.structuredOutput
        if (request.requiresStructuredOutput && spec == null) {
            return "Structured output was required but no JSON schema was supplied"
        }
        if (spec != null && (!spec.isUsable() || runCatching { JSONObject(spec.schemaJson) }.isFailure)) {
            return "Structured output schema is missing a safe name or is invalid/too large"
        }
        return null
    }

    /** Only first-party adapters currently guarantee native schema enforcement. */
    fun hasNativeGuarantee(provider: CloudProvider): Boolean = provider in setOf(
        CloudProvider.OPENAI,
        CloudProvider.GEMINI,
        CloudProvider.ANTHROPIC,
    )

    fun openAiChat(spec: ExecutionRequest.StructuredOutputSpec): String = buildString {
        append("\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{")
        append("\"name\":").append(ToolDefinitionJson.q(spec.name))
        append(",\"strict\":").append(spec.strict)
        append(",\"schema\":").append(spec.schemaJson)
        append("}}")
    }

    fun openAiResponses(spec: ExecutionRequest.StructuredOutputSpec): String = buildString {
        append("\"text\":{\"format\":{\"type\":\"json_schema\",")
        append("\"name\":").append(ToolDefinitionJson.q(spec.name))
        append(",\"strict\":").append(spec.strict)
        append(",\"schema\":").append(spec.schemaJson)
        append("}}")
    }

    fun gemini(spec: ExecutionRequest.StructuredOutputSpec): String = buildString {
        append("\"responseMimeType\":\"application/json\",\"responseSchema\":")
        append(spec.schemaJson)
    }

    fun anthropic(spec: ExecutionRequest.StructuredOutputSpec): String = buildString {
        append("\"output_config\":{\"format\":{\"type\":\"json_schema\",\"schema\":")
        append(spec.schemaJson)
        append("}}")
    }
}
