package com.airi.assistant.agent.loop.verification

/**
 * Structural Python check that needs no interpreter, so it runs on-device.
 *
 * It is NOT a full parser. It catches the mistakes a model most often makes when it writes a
 * program in one shot: unterminated strings, unbalanced or mismatched brackets, broken
 * indentation, a block header without its colon, and a Python 2 `print` statement. A pass means
 * "no structural error found", never "the program runs"; callers must word it that way.
 *
 * Known limit: an f-string that reuses its own quote inside `{}` (valid only from Python 3.12)
 * is read as an ordinary string and can be reported as an error.
 *
 * Pure Kotlin with no Android dependency, so it is unit-testable on the JVM.
 */
object PythonSyntaxChecker {

    data class Issue(val line: Int, val message: String) {
        override fun toString(): String = "line $line: $message"
    }

    sealed class Result {
        object Passed : Result()
        data class Failed(val issues: List<Issue>) : Result()
    }

    const val MAX_ISSUES = 8

    private class Logical(val line: Int, val indent: String, val code: String)

    private val BLOCK_KEYWORDS = setOf(
        "if", "elif", "else", "for", "while", "def", "class", "try", "except", "finally", "with",
    )
    private val KEYWORD_PREFIX = Regex("^([A-Za-z_]+)(?![A-Za-z0-9_])")
    private val PRINT_STATEMENT = Regex("^print\\s+(?!(?:is|in|and|or|if|else|not|for)\\b)[\"A-Za-z0-9_]")

    fun check(source: String): Result {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return Result.Failed(listOf(Issue(1, "the file is empty")))
        val issues = mutableListOf<Issue>()
        val logical = Scanner(text, issues).run()
        analyse(logical, issues)
        return if (issues.isEmpty()) {
            Result.Passed
        } else {
            Result.Failed(issues.sortedBy { it.line }.take(MAX_ISSUES))
        }
    }

    /** Splits source into logical lines (comments removed, strings collapsed to `""`). */
    private class Scanner(private val s: String, private val issues: MutableList<Issue>) {
        private val n = s.length
        private var i = 0
        private var line = 1
        private val cur = StringBuilder()
        private val brackets = ArrayList<Pair<Char, Int>>()
        private val out = ArrayList<Logical>()
        private val pendingIndent = StringBuilder()
        private var inLogical = false
        private var curStart = 0
        private var curIndent = ""

        fun run(): List<Logical> {
            while (i < n) {
                val c = s[i]
                if (!inLogical) {
                    if (c == '\n') {
                        line++
                        pendingIndent.setLength(0)
                        i++
                        continue
                    }
                    if (c == ' ' || c == '\t' || c == '\u000c') {
                        pendingIndent.append(c)
                        i++
                        continue
                    }
                    if (c == '#') {
                        skipComment()
                        continue
                    }
                    inLogical = true
                    curStart = line
                    curIndent = pendingIndent.toString()
                    pendingIndent.setLength(0)
                }
                when {
                    c == '\n' -> {
                        line++
                        i++
                        if (brackets.isEmpty()) finish() else cur.append(' ')
                    }
                    c == '\\' && i + 1 < n && s[i + 1] == '\n' -> {
                        line++
                        i += 2
                        cur.append(' ')
                    }
                    c == '#' -> skipComment()
                    c == '\'' || c == '"' -> readString()
                    c == '(' || c == '[' || c == '{' -> {
                        brackets.add(c to line)
                        cur.append(c)
                        i++
                    }
                    c == ')' || c == ']' || c == '}' -> {
                        closeBracket(c)
                        cur.append(c)
                        i++
                    }
                    else -> {
                        cur.append(c)
                        i++
                    }
                }
            }
            if (inLogical) {
                brackets.forEach { (open, openLine) ->
                    issues += Issue(openLine, "'$open' was never closed")
                }
                finish()
            }
            return out
        }

        private fun finish() {
            val code = cur.toString().trim()
            if (code.isNotEmpty()) out += Logical(curStart, curIndent, code)
            cur.setLength(0)
            inLogical = false
        }

        private fun skipComment() {
            while (i < n && s[i] != '\n') i++
        }

        private fun closeBracket(c: Char) {
            val expected = when (c) {
                ')' -> '('
                ']' -> '['
                else -> '{'
            }
            if (brackets.isEmpty()) {
                issues += Issue(line, "unmatched '$c'")
                return
            }
            val (open, openLine) = brackets.removeAt(brackets.lastIndex)
            if (open != expected) {
                issues += Issue(line, "closing '$c' does not match '$open' opened on line $openLine")
            }
        }

        private fun readString() {
            val q = s[i]
            val startLine = line
            val triple = i + 2 < n && s[i + 1] == q && s[i + 2] == q
            if (triple) {
                val closer = "$q$q$q"
                i += 3
                while (i < n) {
                    val ch = s[i]
                    if (ch == '\\') {
                        if (i + 1 < n && s[i + 1] == '\n') line++
                        i += 2
                        continue
                    }
                    if (ch == q && s.startsWith(closer, i)) {
                        i += 3
                        cur.append("\"\"")
                        return
                    }
                    if (ch == '\n') line++
                    i++
                }
                issues += Issue(startLine, "unterminated triple-quoted string opened on line $startLine")
                return
            }
            i++
            while (i < n) {
                val ch = s[i]
                if (ch == '\\') {
                    if (i + 1 < n && s[i + 1] == '\n') line++
                    i += 2
                    continue
                }
                if (ch == q) {
                    i++
                    cur.append("\"\"")
                    return
                }
                if (ch == '\n') {
                    issues += Issue(startLine, "unterminated string literal")
                    cur.append("\"\"")
                    return
                }
                i++
            }
            issues += Issue(startLine, "unterminated string literal")
        }
    }

    private fun analyse(lines: List<Logical>, issues: MutableList<Issue>) {
        val stack = ArrayList<Int>()
        stack.add(0)
        var prev: Logical? = null
        var prevOpensBlock = false
        for (l in lines) {
            if (l.indent.contains(' ') && l.indent.contains('\t')) {
                issues += Issue(l.line, "inconsistent use of tabs and spaces in indentation")
            }
            val w = indentWidth(l.indent)
            if (prevOpensBlock) {
                if (w <= stack.last()) {
                    issues += Issue(l.line, "expected an indented block after the statement on line ${prev?.line}")
                } else {
                    stack.add(w)
                }
            } else if (w > stack.last()) {
                issues += Issue(l.line, "unexpected indent")
                stack.add(w)
            } else if (w < stack.last()) {
                while (stack.size > 1 && w < stack.last()) stack.removeAt(stack.lastIndex)
                if (w != stack.last()) {
                    issues += Issue(l.line, "unindent does not match any outer indentation level")
                }
            }
            checkBlockColon(l, issues)
            if (PRINT_STATEMENT.containsMatchIn(l.code)) {
                issues += Issue(l.line, "Python 2 style print statement; use print(...)")
            }
            prevOpensBlock = l.code.endsWith(":")
            prev = l
        }
        if (prevOpensBlock && prev != null) {
            issues += Issue(prev.line, "expected an indented block at the end of the file")
        }
    }

    private fun indentWidth(indent: String): Int {
        var width = 0
        for (ch in indent) {
            width = when (ch) {
                '\t' -> (width / 8 + 1) * 8
                '\u000c' -> 0
                else -> width + 1
            }
        }
        return width
    }

    private fun checkBlockColon(l: Logical, issues: MutableList<Issue>) {
        val keyword = KEYWORD_PREFIX.find(l.code)?.groupValues?.get(1) ?: return
        if (keyword !in BLOCK_KEYWORDS) return
        if (!hasTopLevelColon(l.code)) {
            issues += Issue(l.line, "expected ':' at the end of the '$keyword' statement")
        }
    }

    /** A ':' outside any bracket that is not part of the walrus operator ':='. */
    private fun hasTopLevelColon(code: String): Boolean {
        var depth = 0
        for ((index, ch) in code.withIndex()) {
            when (ch) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                ':' -> if (depth == 0 && code.getOrNull(index + 1) != '=') return true
            }
        }
        return false
    }
}
