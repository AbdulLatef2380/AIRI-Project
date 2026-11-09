package com.airi.assistant.agent.loop.tool

import java.io.File
import java.util.Locale

/**
 * Phase 1, item 7: what the chat shows after `write_file` succeeds, so the person can
 * save the file to Downloads or hand it to another app (for example Pydroid) instead of
 * only reading "Saved X in AIRI files.".
 *
 * This type holds no Android classes. It never carries a filesystem path: the exporter
 * re-resolves [fileName] inside the agent files folder every time it is used.
 */
data class AgentFileCard(
    val fileName: String,
    val sizeBytes: Long,
    val kind: Kind,
) {
    enum class Kind { CODE, TEXT, DATA }

    val extension: String get() = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Type used for sharing and "open with": broadly accepted text types. */
    val shareMimeType: String get() = AgentFileCards.shareMimeFor(extension)

    /** Type recorded when copying to Downloads: never one that makes Android rename the file. */
    val downloadMimeType: String get() = AgentFileCards.downloadMimeFor(extension)

    val displaySize: String get() = AgentFileCards.formatSize(sizeBytes)
}

object AgentFileCards {
    private val CODE_EXTENSIONS = setOf(
        "py", "kt", "java", "js", "ts", "jsx", "tsx", "html", "css", "sql",
    )
    private val TEXT_EXTENSIONS = setOf("txt", "md", "log")

    /** Standard types Android's MediaStore keeps the extension for. Everything else is opaque. */
    private val DOWNLOAD_MIME = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "json" to "application/json",
        "csv" to "text/csv",
        "html" to "text/html",
        "css" to "text/css",
        "xml" to "text/xml",
    )

    fun kindFor(extension: String): AgentFileCard.Kind = when (extension.lowercase(Locale.ROOT)) {
        in CODE_EXTENSIONS -> AgentFileCard.Kind.CODE
        in TEXT_EXTENSIONS -> AgentFileCard.Kind.TEXT
        else -> AgentFileCard.Kind.DATA
    }

    fun shareMimeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html" -> "text/html"
        "xml" -> "text/xml"
        else -> "text/plain"
    }

    fun downloadMimeFor(extension: String): String =
        DOWNLOAD_MIME[extension.lowercase(Locale.ROOT)] ?: "application/octet-stream"

    fun formatSize(bytes: Long): String = when {
        bytes < 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /**
     * Builds a card from a finished tool step, or returns null. All three must hold: the
     * tool is `write_file`, its result is the writer's success message for the same file
     * name, and that file really is in [directory]. A failed, paused or gate-annotated
     * failure therefore never produces a card.
     */
    fun fromToolResult(
        toolName: String,
        args: Map<String, String>,
        result: String,
        directory: File,
    ): AgentFileCard? {
        if (toolName != AgentFileWriter.TOOL_NAME) return null
        val name = AgentFileWriter.sanitizeName(args["filename"].orEmpty()) ?: return null
        if (!result.startsWith("${AgentFileWriter.SAVED_PREFIX}$name ")) return null
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
        if (file.parentFile != root || !file.isFile) return null
        return AgentFileCard(
            fileName = name,
            sizeBytes = file.length(),
            kind = kindFor(file.extension),
        )
    }

    /**
     * Card for a file that [fileName] names in [directory], or null when the name is not a plain
     * file name or the file is gone. Used by the agent log, which keeps the step's arguments but
     * not the tool's result text, so [fromToolResult] cannot be used there.
     */
    fun fromExistingFile(fileName: String, directory: File): AgentFileCard? {
        val name = AgentFileWriter.sanitizeName(fileName) ?: return null
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return null
        if (file.parentFile != root || !file.isFile) return null
        return AgentFileCard(fileName = name, sizeBytes = file.length(), kind = kindFor(file.extension))
    }

    /** Adds [card], replacing an earlier card for the same file (an overwrite) in place. */
    fun merge(existing: List<AgentFileCard>, card: AgentFileCard): List<AgentFileCard> {
        val index = existing.indexOfFirst { it.fileName == card.fileName }
        return if (index < 0) existing + card
        else existing.toMutableList().also { it[index] = card }
    }
}
