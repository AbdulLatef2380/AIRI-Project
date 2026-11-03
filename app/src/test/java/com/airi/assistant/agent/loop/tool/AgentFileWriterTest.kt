package com.airi.assistant.agent.loop.tool

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileWriterTest {
    private fun dir() = Files.createTempDirectory("airi_files").toFile().also { it.deleteOnExit() }

    @Test fun writesAPlainFileInsideTheFolder() {
        val d = dir()
        val r = AgentFileWriter.write(d, "report.md", "# Title\nbody", overwrite = false)
        assertTrue(r is AgentFileWriter.Result.Written)
        assertEquals("# Title\nbody", java.io.File(d, "report.md").readText())
        assertFalse(java.io.File(d, ".report.md.tmp").exists())
    }

    @Test fun rejectsPathsHiddenNamesAndExecutableExtensions() {
        listOf("../x.txt", "a/b.txt", "a\\b.txt", ".hidden.txt", "run.sh", "tool.exe", "noext", "a..b.txt", "/etc/passwd.txt")
            .forEach { assertNull(it, AgentFileWriter.sanitizeName(it)) }
        assertEquals("ملاحظات.txt", AgentFileWriter.sanitizeName(" ملاحظات.txt "))
    }

    @Test fun neverOverwritesUnlessAsked() {
        val d = dir()
        AgentFileWriter.write(d, "a.txt", "one", false)
        assertTrue(AgentFileWriter.write(d, "a.txt", "two", false) is AgentFileWriter.Result.Rejected)
        assertEquals("one", java.io.File(d, "a.txt").readText())
        assertTrue(AgentFileWriter.write(d, "a.txt", "two", true) is AgentFileWriter.Result.Written)
        assertEquals("two", java.io.File(d, "a.txt").readText())
    }

    @Test fun enforcesContentLimits() {
        val d = dir()
        assertTrue(AgentFileWriter.write(d, "big.txt", "x".repeat(AgentFileWriter.MAX_CONTENT_CHARS + 1), false) is AgentFileWriter.Result.Rejected)
        assertTrue(AgentFileWriter.write(d, "empty.txt", "  ", false) is AgentFileWriter.Result.Rejected)
        assertTrue(AgentFileWriter.write(d, "nul.txt", "a\u0000b", false) is AgentFileWriter.Result.Rejected)
    }
}
