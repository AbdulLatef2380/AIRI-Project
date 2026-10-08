package com.airi.assistant.attachments

import java.io.File
import java.nio.file.Files

/**
 * Maintenance for files used only while a composer attachment is pending.
 * Accepted message attachments live under filesDir/attachments and are never
 * treated as disposable cache by this class.
 */
internal object TransientAttachmentCache {
    const val COMPOSER_CACHE_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
    const val PART_FILE_MAX_AGE_MS: Long = 60L * 60L * 1000L

    /** Prunes old, direct-child cache files; intended to run once at process start. */
    fun pruneExpired(directory: File, nowMs: Long, maxAgeMs: Long = COMPOSER_CACHE_MAX_AGE_MS): Int {
        require(maxAgeMs >= 0L)
        val rootPath = directory.toPath()
        if (Files.isSymbolicLink(rootPath)) {
            return if (directory.delete()) 1 else 0
        }
        val entries = directory.listFiles() ?: return 0
        var removed = 0
        entries.forEach { entry ->
            val path = entry.toPath()
            if (Files.isSymbolicLink(path)) {
                if (entry.delete()) removed++
            } else if (entry.isFile && isExpired(entry.lastModified(), nowMs, maxAgeMs)) {
                if (entry.delete()) removed++
            }
        }
        return removed
    }

    /** Removes only old interrupted writes, never committed attachment files. */
    fun pruneStalePartFiles(directory: File, nowMs: Long, maxAgeMs: Long = PART_FILE_MAX_AGE_MS): Int {
        require(maxAgeMs >= 0L)
        val rootPath = directory.toPath()
        if (Files.isSymbolicLink(rootPath)) return if (directory.delete()) 1 else 0
        val entries = directory.listFiles() ?: return 0
        var removed = 0
        entries.forEach { entry ->
            val path = entry.toPath()
            val isPartial = entry.name.startsWith(".") && entry.name.endsWith(".part")
            if (isPartial && (Files.isSymbolicLink(path) || isExpired(entry.lastModified(), nowMs, maxAgeMs))) {
                if (entry.delete()) removed++
            }
        }
        return removed
    }

    private fun isExpired(lastModifiedMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        lastModifiedMs > 0L && nowMs >= lastModifiedMs && nowMs - lastModifiedMs >= maxAgeMs
}
