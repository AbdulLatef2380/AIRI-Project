package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Part 4, B6: the agent log builds a file card from the step's filename argument alone. */
class AgentFileCardExistingFileTest {

    private fun dir(): File = Files.createTempDirectory("agent-files").toFile().apply { deleteOnExit() }

    @Test
    fun existingFileProducesACard() {
        val d = dir()
        File(d, "script.py").writeText("print(1)")
        val card = AgentFileCards.fromExistingFile("script.py", d)
        assertNotNull(card)
        assertEquals("script.py", card!!.fileName)
        assertEquals(AgentFileCard.Kind.CODE, card.kind)
        assertEquals(8L, card.sizeBytes)
    }

    @Test
    fun missingFileProducesNoCard() {
        assertNull(AgentFileCards.fromExistingFile("gone.py", dir()))
    }

    @Test
    fun pathTraversalNamesProduceNoCard() {
        val d = dir()
        val outside = File(d.parentFile, "outside.py")
        outside.writeText("x = 1")
        outside.deleteOnExit()
        assertNull(AgentFileCards.fromExistingFile("../outside.py", d))
        assertNull(AgentFileCards.fromExistingFile("sub/dir.py", d))
    }

    @Test
    fun disallowedExtensionProducesNoCard() {
        val d = dir()
        File(d, "run.exe").writeText("MZ")
        assertNull(AgentFileCards.fromExistingFile("run.exe", d))
    }

    @Test
    fun aDirectoryNamedLikeAFileProducesNoCard() {
        val d = dir()
        File(d, "folder.py").mkdir()
        assertNull(AgentFileCards.fromExistingFile("folder.py", d))
    }
}
