package com.airi.assistant.execution

import com.airi.assistant.ai.QueryType

/**
 * Unified request object passed through the execution layer.
 *
 * @param conversationHistory  Prior turns for stateless REST providers (Gemini, OpenAI,
 *                             Anthropic). Each entry is (role, content) where role is
 *                             "user" or "assistant". The current [prompt] is always the
 *                             final user turn — adapters must not duplicate it.
 *                             Local llama.cpp ignores this field; it uses KV-cache
 *                             session reuse instead.
 */
data class ExecutionRequest(
    val prompt:                   String,
    val systemPrompt:             String     = "",
    val maxTokens:                Int        = 512,
    val temperature:              Float      = 0.8f,
    val queryType:                QueryType  = QueryType.UNKNOWN,
    val requiresStreaming:        Boolean    = true,
    val requiresVision:           Boolean    = false,
    val requiresToolCalling:      Boolean    = false,
    val requiresLongContext:      Boolean    = false,
    val requiresOffline:          Boolean    = false,
    val requiresStructuredOutput: Boolean    = false,
    val estimatedPromptTokens:    Int        = 0,
    val sessionTag:               String     = "",
    /** Stable provider identity captured when the execution was admitted. */
    val requestedProviderId:      String     = "",
    /** Stable model identity captured when the execution was admitted. */
    val requestedModelId:         String     = "",
    /** Identity resolved for the selected execution target, never user-facing input. */
    val resolvedProviderId:       String     = "",
    /** Model identifier actually selected for the resolved target. */
    val resolvedModelId:          String     = "",
    val conversationHistory:      List<ConversationTurn> = emptyList(),
    /** Inline image parts for providers that support vision (base64, no file paths). */
    val imageParts:               List<ImagePart> = emptyList(),
    /** Correlation identity; generated at the request boundary and preserved downstream. */
    val identity:                 ExecutionIdentity? = null,
    /** Explicit privacy/routing boundary for this request. */
    val allowCloud:               Boolean = true,
) {
    data class ConversationTurn(val role: String, val content: String)
    data class ImagePart(val mimeType: String, val base64Data: String)

    val estimatedTotalTokens: Int get() = estimatedPromptTokens + maxTokens

    /** Ensures every request entering a backend has a traceable identity. */
    fun withResolvedIdentity(): ExecutionRequest =
        if (identity != null) this else copy(
            identity = ExecutionIdentity(
                sessionId = ChatExecutionIdentityContract.normalizeSessionId(sessionTag),
            )
        )

    val requirementSummary: String get() = buildString {
        append("streaming=$requiresStreaming")
        if (requiresVision)           append(" vision=true")
        if (requiresToolCalling)      append(" tools=true")
        if (requiresLongContext)      append(" longCtx=true")
        if (requiresOffline)          append(" offline=true")
        if (requiresStructuredOutput) append(" structured=true")
        if (requestedProviderId.isNotBlank()) append(" provider=$requestedProviderId")
        if (requestedModelId.isNotBlank()) append(" model=$requestedModelId")
        if (resolvedProviderId.isNotBlank()) append(" resolvedProvider=$resolvedProviderId")
        if (resolvedModelId.isNotBlank()) append(" resolvedModel=$resolvedModelId")
        if (conversationHistory.isNotEmpty()) append(" history=${conversationHistory.size}")
    }
}
