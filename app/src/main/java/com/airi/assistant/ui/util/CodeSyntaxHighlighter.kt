package com.airi.assistant.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.util.Locale

/** Display-only token colouring. The returned text is byte-for-byte equivalent at the String level. */
internal object CodeSyntaxHighlighter {
    val defaultColor = Color(0xFFE6EDF3)
    data class Palette(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val function: Color,
        val type: Color,
        val operator: Color,
    )

    private val darkPalette = Palette(
        keyword = Color(0xFFFF7B72), string = Color(0xFFA5D6FF), comment = Color(0xFF8B949E),
        number = Color(0xFFD2A8FF), function = Color(0xFFD2A8FF), type = Color(0xFFFFA657),
        operator = Color(0xFFFFA657),
    )
    private val lightPalette = Palette(
        keyword = Color(0xFFB42318), string = Color(0xFF075985), comment = Color(0xFF667085),
        number = Color(0xFF6941C6), function = Color(0xFF6941C6), type = Color(0xFFB54708),
        operator = Color(0xFFB54708),
    )

    private val tokens = Regex(
        "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`\\n]*`|" +
            "//[^\\n]*|#[^\\n]*|--[^\\n]*|/\\*[\\s\\S]*?\\*/|" +
            "\\b[A-Za-z_][A-Za-z0-9_]*\\b|\\b\\d+(?:\\.\\d+)?\\b|" +
            "[{}()\\[\\].,:;<>+\\-*/%=!&|?]+"
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
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val operators = Regex("[{}()\\[\\].,:;<>+\\-*/%=!&|?]+")
    private val declarationWords = setOf("class", "interface", "object", "type", "enum")
    private val functionWords = setOf("fun", "def", "function")

    fun palette(background: Color): Palette =
        if (background.luminance() < 0.5f) darkPalette else lightPalette

    fun highlight(code: String, language: String, palette: Palette = darkPalette): AnnotatedString {
        val languageKeywords = if (language.equals("sql", ignoreCase = true)) {
            commonKeywords + sqlKeywords
        } else {
            commonKeywords
        }
        return buildAnnotatedString {
            var cursor = 0
            var previousIdentifier = ""
            for (match in tokens.findAll(code)) {
                if (match.range.first > cursor) append(code.substring(cursor, match.range.first))
                val token = match.value
                var nextIndex = match.range.last + 1
                while (nextIndex < code.length && code[nextIndex].isWhitespace()) nextIndex++
                val nextNonSpace = code.getOrNull(nextIndex)
                val style = when {
                    token.startsWith("//") || token.startsWith("#") || token.startsWith("--") || token.startsWith("/*") ->
                        SpanStyle(color = palette.comment)
                    token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") ->
                        SpanStyle(color = palette.string)
                    token.firstOrNull()?.isDigit() == true -> SpanStyle(color = palette.number)
                    token.lowercase(Locale.ROOT) in languageKeywords -> SpanStyle(color = palette.keyword)
                    operators.matches(token) -> SpanStyle(color = palette.operator)
                    nextNonSpace == '(' || previousIdentifier in functionWords ->
                        SpanStyle(color = palette.function)
                    token.firstOrNull()?.isUpperCase() == true || previousIdentifier in declarationWords ->
                        SpanStyle(color = palette.type)
                    else -> null
                }
                if (style == null) append(token) else withStyle(style) { append(token) }
                if (identifier.matches(token)) previousIdentifier = token
                else if (!token.firstOrNull()?.isWhitespace().orFalse() && !token.startsWith(".")) previousIdentifier = ""
                cursor = match.range.last + 1
            }
            if (cursor < code.length) append(code.substring(cursor))
        }
    }

    private fun Boolean?.orFalse(): Boolean = this == true
}
