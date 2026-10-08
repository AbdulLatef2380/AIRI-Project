package com.airi.assistant.attachments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.airi.assistant.domain.storage.PrivateFileSegmentPolicy
import com.airi.core.attachments.AttachmentPolicy
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Creates short-lived, app-private text attachments using unique atomic filenames. */
object ComposerTextFileStore {
    private const val MAX_COMPOSER_CACHE_BYTES = 32L * 1024L * 1024L
    private val ownedFiles = ConcurrentHashMap<String, File>()

    @Synchronized
    fun create(context: Context, text: String, prefix: String = "prompt"): Uri? {
        if (!PrivateFileSegmentPolicy.isSafeSegment(prefix)) return null
        val incomingBytes = utf8BytesWithinLimit(text, AttachmentPolicy.MAX_TEXT_ATTACHMENT_BYTES) ?: return null
        val directory = File(context.cacheDir, "chat_attachments")
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, ".$prefix-$id.part")
        val destination = File(directory, "$prefix-$id.txt")
        return try {
            if (!directory.exists() && !directory.mkdirs()) return null
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return null
            if (directory.canonicalFile.parentFile != context.cacheDir.canonicalFile) return null
            val existingFiles = directory.listFiles() ?: return null
            if (existingFiles.any { Files.isSymbolicLink(it.toPath()) }) return null
            val existingBytes = existingFiles.filter(File::isFile).sumOf(File::length)
            if (existingBytes > MAX_COMPOSER_CACHE_BYTES - incomingBytes) return null
            temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
            if (!temporary.renameTo(destination)) throw IllegalStateException("Text attachment commit failed")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination).also { uri ->
                ownedFiles[uri.toString()] = destination
            }
        } catch (_: Exception) {
            temporary.delete()
            destination.delete()
            null
        }
    }

    /** Deletes only a composer file created by this process and still inside its cache root. */
    fun discardIfOwned(context: Context, uri: Uri) {
        val file = ownedFiles.remove(uri.toString()) ?: return
        runCatching {
            val root = File(context.cacheDir, "chat_attachments")
            if (!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.canonicalFile) {
                file.delete()
            }
        }
    }

    private fun utf8BytesWithinLimit(text: String, maximumBytes: Long): Long? {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val current = text[index]
            bytes += when {
                Character.isHighSurrogate(current) && index + 1 < text.length &&
                    Character.isLowSurrogate(text[index + 1]) -> {
                    index++
                    4L
                }
                Character.isSurrogate(current) -> 1L // UTF-8 encoder replacement for malformed surrogate.
                current.code <= 0x7F -> 1L
                current.code <= 0x7FF -> 2L
                else -> 3L
            }
            if (bytes > maximumBytes) return null
            index++
        }
        return bytes
    }
}
