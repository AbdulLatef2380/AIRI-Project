package com.airi.assistant.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TransientAttachmentCacheTest {
    @Test
    fun prunesExpiredComposerFilesButKeepsRecentDraftFiles() {
        val temporaryRoot = Files.createTempDirectory("airi-composer-cache-test").toFile()
        try {
            val cache = File(temporaryRoot, "chat_attachments").apply { mkdirs() }
            val now = 10L * 24L * 60L * 60L * 1000L
            val expired = File(cache, "long_message_old.txt").apply { writeText("old") }
            val recent = File(cache, "prompt_recent.txt").apply { writeText("draft") }
            expired.setLastModified(now - TransientAttachmentCache.COMPOSER_CACHE_MAX_AGE_MS - 1L)
            recent.setLastModified(now - 1_000L)

            val removed = TransientAttachmentCache.pruneExpired(cache, now)

            assertEquals(1, removed)
            assertFalse(expired.exists())
            assertTrue(recent.exists())
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    @Test
    fun prunesOnlyStalePartialWritesAndPreservesCommittedAttachments() {
        val temporaryRoot = Files.createTempDirectory("airi-attachment-parts-test").toFile()
        try {
            val attachments = File(temporaryRoot, "attachments").apply { mkdirs() }
            val now = 20L * 60L * 60L * 1000L
            val stalePart = File(attachments, ".interrupted.part").apply { writeText("partial") }
            val freshPart = File(attachments, ".active.part").apply { writeText("in progress") }
            val committed = File(attachments, "message_photo.jpg").apply { writeText("committed") }
            stalePart.setLastModified(now - TransientAttachmentCache.PART_FILE_MAX_AGE_MS - 1L)
            freshPart.setLastModified(now - 1_000L)

            val removed = TransientAttachmentCache.pruneStalePartFiles(attachments, now)

            assertEquals(1, removed)
            assertFalse(stalePart.exists())
            assertTrue(freshPart.exists())
            assertTrue(committed.exists())
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }
}
