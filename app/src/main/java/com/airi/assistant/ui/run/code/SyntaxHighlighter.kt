package com.airi.assistant.ui.run.code

/**
 * Part 5 §4 (read-only editor): a small lexical highlighter for the file types `write_file` accepts.
 * It only classifies spans (keyword, string, comment, number); colors belong to the UI. It is not a
 * parser: it can be wrong on exotic syntax, which only costs a wrong color, never a wrong text.
 */
enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER }

/** [start, end) inside one line. Spans of a line never overlap and are sorted. */
data class CodeSpan(val start: Int, val end: Int, val kind: TokenKind)

enum class CodeLanguage { PYTHON, KOTLIN, JAVA, SCRIPT, JSON, MARKUP, STYLE, SQL, CONFIG, PLAIN }

private class LanguageSpec(
    val keywords: Set<String>,
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val tripleQuotes: Boolean = false,
    val quotes: Set<Char> = setOf('"', '\''),
    val ignoreCase: Boolean = false,
    val markup: Boolean = false,
)

private val PYTHON_KEYWORDS = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
    "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal",
    "not", "or", "pass", "raise", "return", "True", "False", "try", "while", "with", "yield",
)
private val KOTLIN_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return",
    "break", "continue", "in", "is", "as", "try", "catch", "finally", "throw", "import", "package", "null",
    "true", "false", "this", "super", "override", "private", "public", "protected", "internal", "open",
    "abstract", "data", "sealed", "enum", "const", "lateinit", "by", "companion", "suspend",
)
private val JAVA_KEYWORDS = setOf(
    "class", "interface", "public", "private", "protected", "static", "final", "void", "int", "long", "double",
    "float", "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while", "do", "switch",
    "case", "break", "continue", "try", "catch", "finally", "throw", "throws", "import", "package", "null",
    "true", "false", "this", "super", "extends", "implements", "abstract", "enum", "instanceof",
)
private val SCRIPT_KEYWORDS = setOf(
    "function", "const", "let", "var", "class", "return", "if", "else", "for", "while", "do", "switch", "case",
    "break", "continue", "try", "catch", "finally", "throw", "new", "import", "export", "from", "default",
    "null", "undefined", "true", "false", "this", "async", "await", "typeof", "instanceof", "interface",
    "type", "extends", "implements", "of", "in",
)
private val SQL_KEYWORDS = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop",
    "alter", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "and",
    "or", "not", "null", "as", "distinct", "union", "primary", "key", "foreign", "references", "index",
    "default", "int", "integer", "text", "varchar",
)
private val LITERALS = setOf("true", "false", "null", "yes", "no", "on", "off")

private val SPECS: Map<CodeLanguage, LanguageSpec> = mapOf(
    CodeLanguage.PYTHON to LanguageSpec(PYTHON_KEYWORDS, lineComments = listOf("#"), tripleQuotes = true),
    CodeLanguage.KOTLIN to LanguageSpec(KOTLIN_KEYWORDS, listOf("//"), "/*" to "*/", tripleQuotes = true),
    CodeLanguage.JAVA to LanguageSpec(JAVA_KEYWORDS, listOf("//"), "/*" to "*/"),
    CodeLanguage.SCRIPT to LanguageSpec(SCRIPT_KEYWORDS, listOf("//"), "/*" to "*/", quotes = setOf('"', '\'', '`')),
    CodeLanguage.JSON to LanguageSpec(setOf("true", "false", "null"), quotes = setOf('"')),
    CodeLanguage.MARKUP to LanguageSpec(emptySet(), blockComment = "<!--" to "-->", quotes = setOf('"'), markup = true),
    CodeLanguage.STYLE to LanguageSpec(emptySet(), blockComment = "/*" to "*/"),
    CodeLanguage.SQL to LanguageSpec(SQL_KEYWORDS, listOf("--"), "/*" to "*/", ignoreCase = true),
    CodeLanguage.CONFIG to LanguageSpec(LITERALS, listOf("#"), ignoreCase = true),
)

object SyntaxHighlighter {
    fun languageFor(fileName: String): CodeLanguage = when (fileName.substringAfterLast('.', "").lowercase()) {
        "py" -> CodeLanguage.PYTHON
        "kt" -> CodeLanguage.KOTLIN
        "java" -> CodeLanguage.JAVA
        "js", "ts", "jsx", "tsx" -> CodeLanguage.SCRIPT
        "json" -> CodeLanguage.JSON
        "html", "xml" -> CodeLanguage.MARKUP
        "css" -> CodeLanguage.STYLE
        "sql" -> CodeLanguage.SQL
        "yaml", "yml", "toml", "ini" -> CodeLanguage.CONFIG
        else -> CodeLanguage.PLAIN
    }

    /** Spans for each line, same length and order as [lines]. */
    fun highlight(lines: List<String>, language: CodeLanguage): List<List<CodeSpan>> {
        val spec = SPECS[language] ?: return lines.map { emptyList() }
        var open: Pair<String, TokenKind>? = null // closing delimiter + what the open region is
        return lines.map { line ->
            val spans = ArrayList<CodeSpan>()
            fun add(start: Int, end: Int, kind: TokenKind) { if (end > start) spans += CodeSpan(start, end, kind) }
            var i = 0
            val pending = open
            if (pending != null) {
                val close = line.indexOf(pending.first)
                if (close < 0) {
                    add(0, line.length, pending.second)
                    return@map spans
                }
                i = close + pending.first.length
                add(0, i, pending.second)
                open = null
            }
            while (i < line.length) {
                val c = line[i]
                val lineComment = spec.lineComments.firstOrNull { line.startsWith(it, i) }
                if (lineComment != null) { add(i, line.length, TokenKind.COMMENT); break }
                val block = spec.blockComment
                if (block != null && line.startsWith(block.first, i)) {
                    val close = line.indexOf(block.second, i + block.first.length)
                    if (close < 0) { add(i, line.length, TokenKind.COMMENT); open = block.second to TokenKind.COMMENT; break }
                    add(i, close + block.second.length, TokenKind.COMMENT)
                    i = close + block.second.length
                    continue
                }
                if (spec.tripleQuotes && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                    val quote = line.substring(i, i + 3)
                    val close = line.indexOf(quote, i + 3)
                    if (close < 0) { add(i, line.length, TokenKind.STRING); open = quote to TokenKind.STRING; break }
                    add(i, close + 3, TokenKind.STRING)
                    i = close + 3
                    continue
                }
                if (c in spec.quotes) {
                    var j = i + 1
                    while (j < line.length && line[j] != c) {
                        if (line[j] == '\\' && j + 1 < line.length) j++
                        j++
                    }
                    val end = minOf(j + 1, line.length)
                    add(i, end, TokenKind.STRING)
                    i = end
                    continue
                }
                if (spec.markup && c == '<' && i + 1 < line.length && (line[i + 1].isLetter() || line[i + 1] == '/' || line[i + 1] == '?' || line[i + 1] == '!')) {
                    var j = i + 1
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] in "/-:_?!")) j++
                    add(i, j, TokenKind.KEYWORD)
                    i = j
                    continue
                }
                if (c.isDigit() && (i == 0 || !(line[i - 1].isLetterOrDigit() || line[i - 1] == '_'))) {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                    add(i, j, TokenKind.NUMBER)
                    i = j
                    continue
                }
                if (c.isLetter() || c == '_') {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val word = line.substring(i, j)
                    if ((if (spec.ignoreCase) word.lowercase() else word) in spec.keywords) add(i, j, TokenKind.KEYWORD)
                    i = j
                    continue
                }
                i++
            }
            spans
        }
    }
}
