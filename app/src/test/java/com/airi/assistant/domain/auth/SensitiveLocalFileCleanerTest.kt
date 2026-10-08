package com.airi.assistant.domain.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SensitiveLocalFileCleanerTest {
    @Test
    fun clearsAttachmentAndProfilePayloadsButLeavesUnrelatedAppData() {
        val root = Files.createTempDirectory("airi-private-files-test").toFile()
        try {
            File(root, "attachments/message.txt").apply { parentFile!!.mkdirs(); writeText("private attachment") }
            File(root, "profile/account/avatar.jpg").apply { parentFile!!.mkdirs(); writeText("private profile photo") }
            val retainedModel = File(root, "models/local.gguf").apply { parentFile!!.mkdirs(); writeText("model") }

            SensitiveLocalFileCleaner(root).clear()

            assertFalse(File(root, "attachments").exists())
            assertFalse(File(root, "profile").exists())
            assertTrue(retainedModel.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesSymlinkWithoutDeletingItsExternalTarget() {
        val root = Files.createTempDirectory("airi-private-link-test").toFile()
        val outside = Files.createTempDirectory("airi-outside-private-test").toFile()
        try {
            val targetFile = File(outside, "keep.txt").apply { writeText("outside") }
            val attachments = File(root, "attachments").apply { mkdirs() }
            val link = File(attachments, "external")
            Files.createSymbolicLink(link.toPath(), outside.toPath())

            SensitiveLocalFileCleaner(root).clear()

            assertFalse(link.exists() || Files.isSymbolicLink(link.toPath()))
            assertTrue(targetFile.isFile)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
