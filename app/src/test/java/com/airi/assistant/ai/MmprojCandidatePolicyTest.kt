package com.airi.assistant.ai

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmprojCandidatePolicyTest {
    @Test
    fun rejectsNonProjectorFilesAndNonGgufFiles() {
        val dir = Files.createTempDirectory("mmproj-policy").toFile()
        val candidates = listOf(
            File(dir, "chat-model.gguf"),
            File(dir, "mmproj-f16.bin"),
            File(dir, "readme.txt"),
        )
        candidates.forEach { it.writeText("fixture") }
        assertNull(MmprojCandidatePolicy.select(candidates))
    }

    @Test
    fun prefersF16ThenStableNameOrdering() {
        val dir = Files.createTempDirectory("mmproj-policy").toFile()
        val selected = MmprojCandidatePolicy.select(
            listOf(
                File(dir, "mmproj-q4.gguf").also { it.writeText("fixture") },
                File(dir, "mmproj-f16.gguf").also { it.writeText("fixture") },
                File(dir, "mmproj-f32.gguf").also { it.writeText("fixture") },
            ),
        )
        assertEquals("mmproj-f16.gguf", selected?.name)
    }

    @Test
    fun acceptsNestedProjectorDirectory() {
        val dir = Files.createTempDirectory("mmproj-policy").toFile()
            .resolve("projector").also { it.mkdirs() }
        val candidate = File(dir, "mm-proj-q8.gguf").also { it.writeText("fixture") }
        assertTrue(MmprojCandidatePolicy.isCandidate(candidate))
    }
}
