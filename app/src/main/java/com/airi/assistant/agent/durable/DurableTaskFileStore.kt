package com.airi.assistant.agent.durable

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File boundary for the versioned task document. A failed parse blocks writes
 * for this store instance so an empty in-memory cache can never replace data it
 * could not read. A caller may also block writes after rejecting an invalid
 * ownership record from an otherwise parseable document.
 */
class DurableTaskFileStore(
    private val file: File,
    private val codec: DurableTaskJsonCodec = DurableTaskJsonCodec()
) {
    private var writeBlockReason: String? = null

    @Synchronized
    fun read(): DurableTaskFileReadResult {
        if (!file.exists()) {
            writeBlockReason = null
            return DurableTaskFileReadResult.Missing
        }

        return try {
            val tasks = codec.decode(file.readText(Charsets.UTF_8))
            writeBlockReason = null
            DurableTaskFileReadResult.Loaded(tasks)
        } catch (error: Exception) {
            writeBlockReason = "document-read-failed"
            DurableTaskFileReadResult.Invalid(error)
        }
    }

    /** Refuse mutations while the existing file contains data we cannot trust. */
    @Synchronized
    fun blockWrites(reason: String) {
        writeBlockReason = reason.ifBlank { "unsafe-document" }
    }

    @Synchronized
    @Throws(IOException::class)
    fun write(tasks: List<DurableTask>) {
        writeBlockReason?.let { reason ->
            throw IOException("Task store writes are blocked: $reason")
        }

        val destination = file.absoluteFile
        val parent = destination.parentFile
            ?: throw IOException("Task store has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create task-store directory")
        }

        val temporary = File(parent, "${destination.name}.tmp")
        try {
            val bytes = codec.encode(tasks).toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException("Failed to atomically replace task store", error)
        }
    }
}

sealed class DurableTaskFileReadResult {
    data object Missing : DurableTaskFileReadResult()
    data class Loaded(val tasks: List<DurableTask>) : DurableTaskFileReadResult()
    data class Invalid(val cause: Exception) : DurableTaskFileReadResult()
}
