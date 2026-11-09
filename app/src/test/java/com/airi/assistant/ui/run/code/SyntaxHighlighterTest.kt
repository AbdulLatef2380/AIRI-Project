package com.airi.assistant.ui.run.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {
    private fun spans(source: String, language: CodeLanguage) =
        SyntaxHighlighter.highlight(LineDiff.splitLines(source), language)

    private fun CodeSpan.text(line: String) = line.substring(start, end)

    @Test fun languageIsChosenFromTheExtension() {
        assertEquals(CodeLanguage.PYTHON, SyntaxHighlighter.languageFor("calculator.py"))
        assertEquals(CodeLanguage.KOTLIN, SyntaxHighlighter.languageFor("A.KT"))
        assertEquals(CodeLanguage.SCRIPT, SyntaxHighlighter.languageFor("app.tsx"))
        assertEquals(CodeLanguage.MARKUP, SyntaxHighlighter.languageFor("index.html"))
        assertEquals(CodeLanguage.CONFIG, SyntaxHighlighter.languageFor("x.yml"))
        assertEquals(CodeLanguage.PLAIN, SyntaxHighlighter.languageFor("notes.txt"))
        assertEquals(CodeLanguage.PLAIN, SyntaxHighlighter.languageFor("noextension"))
    }

    @Test fun plainTextHasNoSpans() {
        assertTrue(spans("hello world 42", CodeLanguage.PLAIN).single().isEmpty())
    }

    @Test fun pythonKeywordStringNumberAndComment() {
        val line = "if x == 10: print(\"hi\")  # note"
        val found = spans(line, CodeLanguage.PYTHON).single()
        assertEquals(
            listOf(TokenKind.KEYWORD to "if", TokenKind.NUMBER to "10", TokenKind.STRING to "\"hi\"", TokenKind.COMMENT to "# note"),
            found.map { it.kind to it.text(line) },
        )
    }

    @Test fun commentMarkerInsideAStringIsNotAComment() {
        val line = "url = \"http://example.com\""
        val found = spans(line, CodeLanguage.KOTLIN).single()
        assertTrue(found.none { it.kind == TokenKind.COMMENT })
        assertEquals("\"http://example.com\"", found.single { it.kind == TokenKind.STRING }.text(line))
    }

    @Test fun escapedQuoteDoesNotEndTheString() {
        val line = "s = \"a\\\"b\" + 1"
        val found = spans(line, CodeLanguage.JAVA).single()
        assertEquals("\"a\\\"b\"", found.first { it.kind == TokenKind.STRING }.text(line))
        assertTrue(found.any { it.kind == TokenKind.NUMBER })
    }

    @Test fun pythonTripleQuotedStringSpansLines() {
        val result = spans("x = \"\"\"start\nmiddle\nend\"\"\" + 1", CodeLanguage.PYTHON)
        assertEquals(TokenKind.STRING, result[0].last().kind)
        assertEquals(listOf(CodeSpan(0, 6, TokenKind.STRING)), result[1])
        assertEquals(TokenKind.STRING, result[2].first().kind)
        assertTrue(result[2].any { it.kind == TokenKind.NUMBER })
    }

    @Test fun blockCommentSpansLinesAndThenCodeResumes() {
        val result = spans("/* one\ntwo */ val a = 1", CodeLanguage.KOTLIN)
        assertEquals(listOf(CodeSpan(0, 6, TokenKind.COMMENT)), result[0])
        assertEquals(TokenKind.COMMENT, result[1].first().kind)
        assertTrue(result[1].any { it.kind == TokenKind.KEYWORD })
        assertTrue(result[1].any { it.kind == TokenKind.NUMBER })
    }

    @Test fun digitsInsideIdentifiersAreNotNumbers() {
        val line = "x2 = abc3 + 4"
        val found = spans(line, CodeLanguage.PYTHON).single()
        assertEquals(listOf("4"), found.filter { it.kind == TokenKind.NUMBER }.map { it.text(line) })
    }

    @Test fun sqlKeywordsIgnoreCase() {
        val line = "SELECT name FROM users"
        val found = spans(line, CodeLanguage.SQL).single()
        assertEquals(listOf("SELECT", "FROM"), found.filter { it.kind == TokenKind.KEYWORD }.map { it.text(line) })
    }

    @Test fun markupTagsAndAttributeStrings() {
        val line = "<div class=\"a\">hi</div>"
        val found = spans(line, CodeLanguage.MARKUP).single()
        assertEquals(listOf("<div", "</div"), found.filter { it.kind == TokenKind.KEYWORD }.map { it.text(line) })
        assertEquals("\"a\"", found.single { it.kind == TokenKind.STRING }.text(line))
    }

    @Test fun unterminatedStringEndsAtTheEndOfTheLine() {
        val line = "x = \"open"
        val found = spans(line, CodeLanguage.PYTHON).single()
        assertEquals(CodeSpan(4, line.length, TokenKind.STRING), found.single())
    }

    @Test fun spansAreAscendingAndNeverOverlap() {
        val source = "def f(a, b):\n    \"\"\"doc\"\"\"\n    return a + 12  # sum\n"
        spans(source, CodeLanguage.PYTHON).forEach { line ->
            line.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        }
    }
}
