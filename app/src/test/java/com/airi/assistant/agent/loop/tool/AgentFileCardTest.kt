package com.airi.assistant.agent.loop.tool

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileCardTest {
    private fun dir() = Files.createTempDirectory("airi_cards").toFile().also { it.deleteOnExit() }

    private fun write(dir: File, name: String, content: String = "print('hi')\n"): String {
        val written = AgentFileWriter.write(dir, name, content, overwrite = true)
        assertTrue(written is AgentFileWriter.Result.Written)
        return AgentFileWriter.savedMessage(name, content.length)
    }

    @Test fun successfulWriteFileBecomesACard() {
        val d = dir()
        val result = write(d, "calculator.py", "x = 1\n".repeat(500))
        val card = AgentFileCards.fromToolResult(
            toolName = "write_file",
            args = mapOf("filename" to "calculator.py", "content" to "ignored"),
            result = result,
            directory = d,
        )
        assertNotNull(card)
        assertEquals("calculator.py", card!!.fileName)
        assertEquals(AgentFileCard.Kind.CODE, card.kind)
        assertEquals(3000L, card.sizeBytes)
        assertEquals("2.9 KB", card.displaySize)
    }

    @Test fun gateFeedbackAppendedToTheResultStillProducesACard() {
        val d = dir()
        val result = write(d, "app.py") + "\n\nSyntax check passed."
        assertNotNull(AgentFileCards.fromToolResult("write_file", mapOf("filename" to "app.py"), result, d))
    }

    @Test fun otherToolsNeverProduceACard() {
        val d = dir()
        val result = write(d, "app.py")
        assertNull(AgentFileCards.fromToolResult("read_file", mapOf("filename" to "app.py"), result, d))
        assertNull(AgentFileCards.fromToolResult("terminal_execute", mapOf("command" to "ls"), result, d))
    }

    @Test fun failedWritesNeverProduceACard() {
        val d = dir()
        val error = "Error [INVALID_ARGUMENT]: A file named app.py already exists; set overwrite to replace it"
        write(d, "app.py")
        assertNull(AgentFileCards.fromToolResult("write_file", mapOf("filename" to "app.py"), error, d))
        assertNull(
            AgentFileCards.fromToolResult(
                "write_file",
                mapOf("filename" to "app.py"),
                "Approval is required; the requested action is paused for user review. Do not retry the operation.",
                d,
            ),
        )
    }

    @Test fun successMessageForAnotherFileDoesNotMatch() {
        val d = dir()
        write(d, "a.py")
        write(d, "b.py")
        val resultForB = AgentFileWriter.savedMessage("b.py", 10)
        assertNull(AgentFileCards.fromToolResult("write_file", mapOf("filename" to "a.py"), resultForB, d))
    }

    @Test fun missingFileOrUnsafeNameProducesNoCard() {
        val d = dir()
        val saved = AgentFileWriter.savedMessage("ghost.py", 5)
        assertNull(AgentFileCards.fromToolResult("write_file", mapOf("filename" to "ghost.py"), saved, d))
        listOf("../escape.py", "dir/inner.py", "..", "", "no_extension", "run.exe").forEach { bad ->
            assertNull(
                bad,
                AgentFileCards.fromToolResult("write_file", mapOf("filename" to bad), "Saved $bad (5 characters) in AIRI files.", d),
            )
        }
    }

    @Test fun fileOutsideTheFolderIsNotAccepted() {
        val d = dir()
        val outside = File(d.parentFile, "outside_${System.nanoTime()}.py").also { it.writeText("x"); it.deleteOnExit() }
        assertNull(
            AgentFileCards.fromToolResult(
                "write_file",
                mapOf("filename" to "../${outside.name}"),
                "Saved ../${outside.name} (1 characters) in AIRI files.",
                d,
            ),
        )
    }

    @Test fun overwriteReplacesTheSameCardInsteadOfAddingOne() {
        val first = AgentFileCard("calc.py", 100, AgentFileCard.Kind.CODE)
        val other = AgentFileCard("notes.md", 20, AgentFileCard.Kind.TEXT)
        val rewritten = AgentFileCard("calc.py", 900, AgentFileCard.Kind.CODE)
        val merged = AgentFileCards.merge(AgentFileCards.merge(listOf(first), other), rewritten)
        assertEquals(listOf(rewritten, other), merged)
    }

    @Test fun kindsAndMimeTypesFollowTheExtension() {
        assertEquals(AgentFileCard.Kind.CODE, AgentFileCards.kindFor("PY"))
        assertEquals(AgentFileCard.Kind.CODE, AgentFileCards.kindFor("kt"))
        assertEquals(AgentFileCard.Kind.TEXT, AgentFileCards.kindFor("md"))
        assertEquals(AgentFileCard.Kind.DATA, AgentFileCards.kindFor("json"))
        assertEquals(AgentFileCard.Kind.DATA, AgentFileCards.kindFor("yaml"))

        val py = AgentFileCard("calculator.py", 10, AgentFileCard.Kind.CODE)
        assertEquals("text/plain", py.shareMimeType)
        // An opaque type keeps Android from renaming calculator.py to calculator.py.txt.
        assertEquals("application/octet-stream", py.downloadMimeType)
        val json = AgentFileCard("data.json", 10, AgentFileCard.Kind.DATA)
        assertEquals("application/json", json.shareMimeType)
        assertEquals("application/json", json.downloadMimeType)
        assertEquals("text/markdown", AgentFileCard("a.md", 1, AgentFileCard.Kind.TEXT).downloadMimeType)
    }

    @Test fun sizesAreFormattedWithoutLocaleDigits() {
        assertEquals("0 B", AgentFileCards.formatSize(0))
        assertEquals("1023 B", AgentFileCards.formatSize(1023))
        assertEquals("1.0 KB", AgentFileCards.formatSize(1024))
        assertEquals("24.1 KB", AgentFileCards.formatSize(24_680))
        assertEquals("1.5 MB", AgentFileCards.formatSize(1_572_864))
        assertEquals("", AgentFileCards.formatSize(-1))
    }

    @Test fun writerSuccessMessageKeepsItsPrefixAndWording() {
        val message = AgentFileWriter.savedMessage("calculator.py", 1200)
        assertTrue(message.startsWith(AgentFileWriter.SAVED_PREFIX))
        assertEquals("Saved calculator.py (1200 characters) in AIRI files.", message)
    }
}
