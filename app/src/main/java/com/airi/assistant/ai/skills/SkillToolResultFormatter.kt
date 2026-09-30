package com.airi.assistant.ai.skills

/** Formats a bounded, explicit skill-result envelope for the agent conversation. */
object SkillToolResultFormatter {
    private const val MAX_TOTAL_CHARS = 12_000
    private const val MAX_FIELD_CHARS = 4_000
    private val secretKey = Regex("(?i)(token|secret|password|authorization|api[_-]?key|webhook|credential)")
    private val bearerSecret = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
    private val secretAssignment = Regex(
        "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|authorization|webhook[_-]?key|credential)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\\"'\\s,;}{]+"
    )
    private val iftttWebhookSecret = Regex("(?i)(https?://maker\\.ifttt\\.com/trigger/[^/\\s\\\"<>]+/with/key/)[^/?#\\s\\\"<>]+")

    fun format(result: SkillResult): String = buildString {
        appendLine("SKILL_RESULT")
        appendLine("status=${if (result.success) "success" else "error"}")
        result.skillName?.takeIf(String::isNotBlank)?.let { appendLine("skill=${safe(it)}") }
        result.error?.takeIf(String::isNotBlank)?.let { appendLine("error=${safe(it)}") }
        result.data.takeIf(String::isNotBlank)?.let {
            appendLine("data:")
            appendLine(safe(it))
        }
        if (result.metadata.isNotEmpty()) {
            appendLine("metadata:")
            result.metadata.toSortedMap().forEach { (key, value) ->
                if (!secretKey.containsMatchIn(key)) appendLine("- ${safe(key)}=${safe(value)}")
            }
        }
        if (result.toolOutputs.isNotEmpty()) {
            appendLine("tool_outputs:")
            result.toolOutputs.forEach { output ->
                appendLine("- tool=${safe(output.toolName)} success=${output.success}")
                appendLine("  ${safe(output.output)}")
            }
        }
        result.executionMs.takeIf { it > 0 }?.let { appendLine("execution_ms=$it") }
    }.take(MAX_TOTAL_CHARS)

    private fun safe(value: String): String = value
        .replace("\u0000", "")
        .replace(bearerSecret) { "Bearer [REDACTED]" }
        .replace(secretAssignment) { "${it.groupValues[1]}[REDACTED]" }
        .replace(iftttWebhookSecret) { "${it.groupValues[1]}[REDACTED]" }
        .take(MAX_FIELD_CHARS)
}
