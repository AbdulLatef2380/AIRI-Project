package com.airi.assistant.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.util.Locale

/** Display-only token colouring. The returned text is byte-for-byte equivalent at the String level. */
internal object CodeSyntaxHighlighter {
    val defaultColor = Color(0xFFE6EDF3)
    private val keywordColor = Color(0xFFFF7B72)
    private val stringColor = Color(0xFFA5D6FF)
    private val commentColor = Color(0xFF8B949E)
    private val numberColor = Color(0xFFD2A8FF)

    private val tokens = Regex(
        "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`\\n]*`|" +
            "//[^\\n]*|#[^\\n]*|--[^\\n]*|/\\*[\\s\\S]*?\\*/|" +
            "\\b[A-Za-z_][A-Za-z0-9_]*\\b|\\b\\d+(?:\\.\\d+)?\\b"
    )

    private val commonKeywords = setOf(
        "as", "async", "await", "and", "break", "case", "catch", "class", "const", "continue",
        "def", "do", "elif", "else", "enum", "except", "false", "False", "finally", "for", "from",
        "fun", "if", "implements", "import", "in", "interface", "is", "lambda", "let", "new", "not",
        "null", "None", "object", "or", "override", "package", "private", "protected", "public", "raise",
        "return", "static", "super", "suspend", "switch", "this", "throw", "true", "True", "try", "val",
        "var", "void", "when", "while", "with", "yield"
    )
    private val sqlKeywords = setOf("select", "where", "from", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "insert", "into", "values", "update", "set", "delete", "create", "table", "null", "as")

    fun highlight(code: String, language: String): AnnotatedString {
        val languageKeywords = if (language.equals("sql", ignoreCase = true)) {
            commonKeywords + sqlKeywords
        } else {
            commonKeywords
        }
        return buildAnnotatedString {
            var cursor = 0
            for (match in tokens.findAll(code)) {
                if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
                val token = match.value
                val style = when {
                    token.startsWith("//") || token.startsWith("#") || token.startsWith("--") || token.startsWith("/*") ->
                        SpanStyle(color = commentColor)
                    token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") ->
                        SpanStyle(color = stringColor)
                    token.firstOrNull()?.isDigit() == true -> SpanStyle(color = numberColor)
                    token.lowercase(Locale.ROOT) in languageKeywords -> SpanStyle(color = keywordColor)
                    else -> null
                }
                if (style == null) append(token) else withStyle(style) { append(token) }
                cursor = match.range.last + 1
            }
            if (cursor < code.length) append(code.substring(cursor))
        }
    }
}
