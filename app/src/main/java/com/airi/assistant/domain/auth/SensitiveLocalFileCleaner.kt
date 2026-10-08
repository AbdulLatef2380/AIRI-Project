package com.airi.assistant.domain.auth

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Deletes user-owned files which are not represented solely by Room or cacheDir.
 * Fixed child names and no-follow traversal keep cleanup inside app-private storage.
 */
internal class SensitiveLocalFileCleaner(private val filesDir: File) {
    fun clear(): Set<String> {
        val targets = setOf("attachments", "profile")
        targets.forEach { name ->
            val child = File(filesDir, name)
            if (!deleteNoFollow(child)) throw IOException("Unable to remove private user data directory: $name")
        }
        return targets
    }

    private fun deleteNoFollow(file: File): Boolean {
        val path = file.toPath()
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true
        if (Files.isSymbolicLink(path)) return Files.deleteIfExists(path)
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Unable to enumerate private data directory")
            children.forEach { child ->
                if (!deleteNoFollow(child)) return false
            }
        }
        return !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.deleteIfExists(path)
    }
}
