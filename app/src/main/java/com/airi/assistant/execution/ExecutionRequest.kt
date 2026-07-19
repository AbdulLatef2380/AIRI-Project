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
    val conversationHistory:      List<ConversationTurn> = emptyList()
) {
    data class ConversationTurn(val role: String, val content: String)

    val estimatedTotalTokens: Int get() = estimatedPromptTokens + maxTokens

    val requirementSummary: String get() = buildString {
        append("streaming=$requiresStreaming")
        if (requiresVision)           append(" vision=true")
        if (requiresToolCalling)      append(" tools=true")
        if (requiresLongContext)      append(" longCtx=true")
        if (requiresOffline)          append(" offline=true")
        if (requiresStructuredOutput) append(" structured=true")
        if (conversationHistory.isNotEmpty()) append(" history=${conversationHistory.size}")
    }
}
