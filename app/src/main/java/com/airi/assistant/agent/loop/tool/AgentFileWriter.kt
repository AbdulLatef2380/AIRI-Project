package com.airi.assistant.agent.loop.tool

import java.io.File

/**
 * Report 1, item 5 (stage 33): bounded text-file writer behind the `write_file` tool.
 *
 * Everything the model controls is validated here, before any I/O: a flat file name
 * (no directories, no hidden or dotted-parent names), an extension allow-list that has
 * no executable formats, a content size cap, a file-count cap, and no overwrite unless
 * the caller asked for it. The file is written to a temp name and renamed, so a failed
 * write never leaves a half-written file under the final name.
 */
object AgentFileWriter {
    const val MAX_CONTENT_CHARS = 200_000
    const val MAX_FILES = 200
    const val DIRECTORY_NAME = "agent_files"

    /** Tool name this writer backs; the chat file card keys off it. */
    const val TOOL_NAME = "write_file"

    /** First words of the success message; [savedMessage] and the file card agree on it. */
    const val SAVED_PREFIX = "Saved "

    /** The tool result the model sees after a successful write. */
    fun savedMessage(fileName: String, chars: Int): String =
        "$SAVED_PREFIX$fileName ($chars characters) in AIRI files."

    val ALLOWED_EXTENSIONS: Set<String> = setOf(
        "txt", "md", "json", "csv", "tsv", "html", "css", "js", "ts", "jsx", "tsx",
        "py", "kt", "java", "xml", "yaml", "yml", "toml", "ini", "sql", "log",
    )

    private val SAFE_NAME = Regex("""^[\p{L}\p{N}][\p{L}\p{N} ._()\-]{0,90}\.[A-Za-z0-9]{1,8}$""")

    sealed class Result {
        data class Written(val file: File, val chars: Int) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /** Returns the cleaned name, or null when [raw] is not an acceptable flat file name. */
    fun sanitizeName(raw: String): String? {
        val name = raw.trim()
        if (name.length !in 3..96) return null
        if (name.contains("..") || name.contains('/') || name.contains('\\')) return null
        if (!SAFE_NAME.matches(name)) return null
        val extension = name.substringAfterLast('.').lowercase()
        return name.takeIf { extension in ALLOWED_EXTENSIONS }
    }

    fun write(directory: File, rawName: String, content: String, overwrite: Boolean): Result {
        val name = sanitizeName(rawName)
            ?: return Result.Rejected("File name must be a plain name with one of the allowed extensions: ${ALLOWED_EXTENSIONS.sorted().joinToString()}")
        if (content.isBlank()) return Result.Rejected("File content is empty")
        if (content.length > MAX_CONTENT_CHARS) return Result.Rejected("File content exceeds $MAX_CONTENT_CHARS characters")
        if (content.contains('\u0000')) return Result.Rejected("File content contains NUL characters")
        if (!directory.exists() && !directory.mkdirs()) return Result.Rejected("Could not create the files folder")

        val root = directory.canonicalFile
        val target = File(root, name).canonicalFile
        if (target.parentFile != root) return Result.Rejected("Path must remain inside the files folder")
        if (target.exists() && !overwrite) return Result.Rejected("A file named $name already exists; set overwrite to replace it")
        if (!target.exists() && (root.listFiles()?.count { it.isFile } ?: 0) >= MAX_FILES) {
            return Result.Rejected("The files folder is full ($MAX_FILES files)")
        }

        val temp = File(root, ".$name.tmp")
        return try {
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) return Result.Rejected("Could not finalize the file")
            }
            Result.Written(target, content.length)
        } catch (e: java.io.IOException) {
            Result.Rejected("Could not write the file: ${e.message}")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
