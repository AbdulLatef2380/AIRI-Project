package com.airi.assistant.domain

/**
 * Builds a short, local-only conversation title from the first user request.
 *
 * This is deliberately heuristic: automatic titling must not create a second
 * model pipeline, spend cloud credits, or persist hidden reasoning. The raw
 * request remains the fallback source if no task-specific rule matches.
 */
object ConversationTitlePolicy {
    private const val MAX_CHARS = 60
    private const val MAX_WORDS = 9

    private val urls = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val markdown = Regex("[*_`#>~]+")
    private val whitespace = Regex("\\s+")

    fun generate(input: String): String {
        val cleaned = normalize(input)
        if (cleaned.isBlank()) return "محادثة جديدة"

        val lower = cleaned.lowercase()
        val taskTitle = when {
            lower.contains("gradle") || lower.contains("kotlin") || lower.contains("compile") ||
                lower.contains("build error") || lower.contains("خطأ") -> {
                if (cleaned.any { it in '\u0600'..'\u06FF' }) "إصلاح خطأ Kotlin أو Gradle" else "Fix a Kotlin or Gradle error"
            }
            lower.contains("pdf") || lower.contains("document") || lower.contains("ملف") ||
                lower.contains("تحليل") -> {
                if (cleaned.any { it in '\u0600'..'\u06FF' }) "تحليل الملف" else "Analyze the file"
            }
            lower.contains("schedule") || lower.contains("scheduled") || lower.contains("مجدول") ||
                lower.contains("جدول") -> {
                if (cleaned.any { it in '\u0600'..'\u06FF' }) "إعداد مهمة مجدولة" else "Set up a scheduled task"
            }
            lower.contains("website") || lower.contains("web page") || lower.contains("موقع") ||
                lower.contains("صفحة") -> {
                if (cleaned.any { it in '\u0600'..'\u06FF' }) "إنشاء صفحة ويب" else "Create a website"
            }
            else -> null
        }
        return limit(taskTitle ?: removeRequestPrefix(cleaned))
    }

    fun normalize(input: String): String {
        return input
            .replace(urls, "")
            .replace(markdown, "")
            .replace(Regex("\\[[^]]*]\\(.*?\\)"), "")
            .replace(whitespace, " ")
            .trim(' ', '.', ',', ':', ';', '-', '—', '؟', '?', '!')
    }

    private fun removeRequestPrefix(value: String): String {
        val prefixes = listOf(
            "أريد منك", "اريد منك", "ساعدني في", "ساعدني على", "هل يمكنك", "من فضلك",
            "قم ب", "قم بـ", "أحتاج إلى", "احتاج الى", "please", "can you", "could you",
            "help me", "i want you to", "i need you to", "build me"
        )
        return prefixes.firstOrNull { value.startsWith(it, ignoreCase = true) }
            ?.let { value.removePrefix(it).trimStart(' ', ':', '-', '—') }
            ?.ifBlank { value }
            ?: value
    }

    private fun limit(value: String): String {
        val words = value.split(whitespace).filter(String::isNotBlank).take(MAX_WORDS)
        val compact = words.joinToString(" ")
        if (compact.length <= MAX_CHARS) return compact
        return compact.take(MAX_CHARS).substringBeforeLast(' ').ifBlank { compact.take(MAX_CHARS) }
    }
}
