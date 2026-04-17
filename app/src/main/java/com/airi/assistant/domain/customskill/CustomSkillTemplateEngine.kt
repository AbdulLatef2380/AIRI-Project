package com.airi.assistant.domain.customskill

object CustomSkillTemplateEngine {
    fun render(template: String, input: Map<String, Any>): String {
        val values = mapOf(
            "user_input" to (input["user_input"] ?: input["input"] ?: input["message"] ?: "").toString(),
            "timestamp" to (input["timestamp"] ?: System.currentTimeMillis()).toString(),
            "user_id" to (input["user_id"] ?: "").toString()
        )
        return values.entries.fold(template) { current, (key, value) ->
            current.replace("{{$key}}", escapeJsonString(value))
        }
    }

    private fun escapeJsonString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}