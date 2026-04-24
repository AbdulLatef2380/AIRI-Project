package com.airi.assistant.domain.customskill

object CustomSkillTemplateEngine {

    private val VARIABLE_REGEX = Regex("\\{\\{(\\w+)}}")

    fun render(template: String, input: Map<String, Any>): String {
        val values = buildValueMap(input)
        return VARIABLE_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            val value = values[key] ?: ""
            escapeJsonString(value)
        }
    }

    private fun buildValueMap(input: Map<String, Any>): Map<String, String> = mapOf(
        "user_input" to resolveFirst(input, "user_input", "input", "message", "query"),
        "timestamp" to (input["timestamp"] ?: System.currentTimeMillis()).toString(),
        "user_id" to (input["user_id"] ?: "").toString(),
        "conversation_context" to resolveFirst(input, "conversation_context", "context", "history"),
        "agent_goal" to resolveFirst(input, "agent_goal", "goal", "objective", "intent")
    )

    private fun resolveFirst(input: Map<String, Any>, vararg keys: String): String {
        for (key in keys) {
            val value = input[key]
            if (value != null && value.toString().isNotBlank()) return value.toString()
        }
        return ""
    }

    private fun escapeJsonString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
