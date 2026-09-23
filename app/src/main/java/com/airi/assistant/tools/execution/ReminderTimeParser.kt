package com.airi.assistant.tools.execution

/** Pure parser used by AlarmTool; safe to unit-test without Android services. */
object ReminderTimeParser {
    fun parseTime(input: String): Pair<Int, Int>? {
        val normalized = normalizeDigits(input.lowercase().trim())
        return when {
            normalized.contains("noon") || normalized.contains("الظهر") -> 12 to 0
            normalized.contains("midnight") || normalized.contains("منتصف الليل") -> 0 to 0
            else -> {
                val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|صباحا|صباحًا|مساء|مساءً)?").find(normalized)
                    ?: return null
                var hour = match.groupValues[1].toIntOrNull() ?: return null
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val suffix = match.groupValues[3]
                if (suffix in setOf("pm", "مساء", "مساءً") && hour != 12) hour += 12
                if (suffix in setOf("am", "صباحا", "صباحًا") && hour == 12) hour = 0
                if (hour in 0..23 && minute in 0..59) hour to minute else null
            }
        }
    }

    fun parseDuration(input: String): Int? {
        val normalized = normalizeDigits(input.lowercase().trim())
        var seconds = 0
        Regex("(\\d+)\\s*(?:h|hour|hours|ساعة|ساعات)").find(normalized)?.let {
            seconds += (it.groupValues[1].toIntOrNull() ?: 0) * 3600
        }
        if (seconds == 0 && Regex("(?<!\\d)ساعة(?:\\s|$)").containsMatchIn(normalized)) {
            seconds += 3600
        }
        Regex("(\\d+)\\s*(?:m|min|minute|minutes|دقيقة|دقائق|دقيقه)").find(normalized)?.let {
            seconds += (it.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        Regex("(\\d+)\\s*(?:s|sec|second|seconds|ثانية|ثواني|ثانيه)").find(normalized)?.let {
            seconds += it.groupValues[1].toIntOrNull() ?: 0
        }
        return seconds.takeIf { it > 0 }
    }

    private fun normalizeDigits(text: String): String = buildString(text.length) {
        text.forEach { char ->
            append(
                when (char) {
                    in '٠'..'٩' -> ('0'.code + (char.code - '٠'.code)).toChar()
                    in '۰'..'۹' -> ('0'.code + (char.code - '۰'.code)).toChar()
                    else -> char
                }
            )
        }
    }
}
