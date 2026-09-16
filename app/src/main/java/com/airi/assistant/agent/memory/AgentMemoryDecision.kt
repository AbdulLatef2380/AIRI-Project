package com.airi.assistant.agent.memory

/**
 * The memory gate owned by the agent layer. Chat history is always retained as
 * bounded session context, but durable knowledge is admitted only when the
 * agent can identify an explicit memory intent in the user's message.
 */
object AgentMemoryDecision {
    private val explicitIntent = Regex(
        "(?:remember|save this|store this|keep this in memory|add this to knowledge|do not forget|تذكر|احفظ|سجل هذا|أضف هذا إلى المعرفة|لا تنس)",
        RegexOption.IGNORE_CASE
    )

    fun shouldExtractFacts(role: String, content: String): Boolean =
        role.equals("user", ignoreCase = true) &&
            content.length <= 4_000 &&
            explicitIntent.containsMatchIn(content)
}
