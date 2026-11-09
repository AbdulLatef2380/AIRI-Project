package com.airi.assistant.agent.loop.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonSyntaxCheckerTest {

    private fun issues(source: String): List<PythonSyntaxChecker.Issue> =
        (PythonSyntaxChecker.check(source) as? PythonSyntaxChecker.Result.Failed)?.issues.orEmpty()

    private fun assertPasses(source: String) =
        assertEquals(PythonSyntaxChecker.Result.Passed, PythonSyntaxChecker.check(source))

    private fun assertFails(source: String, messagePart: String) {
        val found = issues(source)
        assertTrue("expected an issue containing '$messagePart' but got $found", found.any { it.message.contains(messagePart) })
    }

    @Test
    fun realisticCalculatorProgramPasses() {
        assertPasses(
            """
            import tkinter as tk

            THEMES = {"dark": {"bg": "#111"}, "light": {"bg": "#fff"}}

            class Calculator(tk.Tk):
                def __init__(self):
                    super().__init__()
                    self.title("آلة حاسبة")
                    self.expr = ""

                def press(self, token):
                    if token == "=":
                        try:
                            result = eval(self.expr, {"__builtins__": {}}, {})
                        except (SyntaxError, ZeroDivisionError) as exc:
                            result = f"error: ${'$'}{exc}"
                        self.expr = str(result)
                    else:
                        self.expr += token

            if __name__ == "__main__":
                Calculator().mainloop()
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun validConstructsAreNotFlagged() {
        assertPasses("x = [\n    1, 2,\n    3,\n]\nif x and print is not None:\n    pass\n")
        assertPasses("while chunk := f.read(1):\n    pass\n")
        assertPasses("a = [1, 2, 3]\nif a[0:2] == [1, 2]:\n    pass\n")
        assertPasses("class A: pass\nif x: y = 1\n")
        assertPasses("s = '''multi\nline 'quoted' text'''\nt = 1 + \\\n    2\n")
        assertPasses("def f():\r\n    return 1\r\n")
        assertPasses("def f():\n\tif True:\n\t\treturn 1\n\treturn 2\n")
    }

    @Test
    fun unterminatedStringsAreReported() {
        assertFails("x = \"abc\ny = 1\n", "unterminated string literal")
        assertFails("x = \"\"\"abc\ny = 1\n", "unterminated triple-quoted")
    }

    @Test
    fun bracketProblemsAreReported() {
        assertFails("x = (1 + 2\ny = 3\n", "never closed")
        assertFails("x = 1)\n", "unmatched")
        assertFails("x = (1, 2]\n", "does not match")
    }

    @Test
    fun indentationProblemsAreReported() {
        assertFails("def f():\n    x = 1\n      y = 2\n", "unexpected indent")
        assertFails("def f():\n        x = 1\n    y = 2\n", "unindent does not match")
        assertFails("def f():\nx = 1\n", "expected an indented block")
        assertFails("def f():\n", "expected an indented block at the end")
        assertFails("def f():\n \tx = 1\n", "tabs and spaces")
    }

    @Test
    fun missingColonIsReported() {
        assertFails("if x == 1\n    pass\n", "expected ':'")
        assertFails("def f()\n    pass\n", "expected ':'")
        assertFails("class A\n    pass\n", "expected ':'")
        assertFails("for i in range(3)\n    pass\n", "expected ':'")
    }

    @Test
    fun python2PrintStatementIsReported() {
        assertFails("print \"hello\"\n", "print")
    }

    @Test
    fun emptyFileIsReported() {
        assertFails("  \n\n", "empty")
    }

    @Test
    fun issueCountIsCapped() {
        val broken = (1..30).joinToString("\n") { "x$it = (" }
        assertTrue(issues(broken).size <= PythonSyntaxChecker.MAX_ISSUES)
    }
}
