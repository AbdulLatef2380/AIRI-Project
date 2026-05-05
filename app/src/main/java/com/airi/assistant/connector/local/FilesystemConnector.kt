package com.airi.assistant.connector.local

import android.content.Context
import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FilesystemConnector — scoped on-device file I/O.
 *
 * All paths are resolved relative to one of three sandbox roots:
 *   - `internal://` → [Context.getFilesDir]   (always writable, private)
 *   - `cache://`    → [Context.getCacheDir]    (always writable, evictable)
 *   - `external://` → [Context.getExternalFilesDir](null)  (writable when mounted)
 *
 * Paths that escape the sandbox via `../` traversal are rejected with
 * `security_violation` rather than being silently clamped — the agent
 * must be told explicitly when it issues a bad path.
 *
 * ## Supported actions
 * | action        | required params                                | notes                       |
 * |---------------|------------------------------------------------|-----------------------------|
 * | `read_text`   | `path`                                         | UTF-8 decode, max 128 KB    |
 * | `write_text`  | `path`, text = content                         | Creates parent dirs         |
 * | `append_text` | `path`, text = content                         | Creates file if absent      |
 * | `list_dir`    | `path`                                         | Returns comma-delimited list|
 * | `file_exists` | `path`                                         | Returns "true" / "false"    |
 * | `delete_file` | `path`                                         | Files only; dirs rejected   |
 * | `make_dir`    | `path`                                         | Creates dir + parents       |
 */
class FilesystemConnector(
    private val appContext: Context,
) : Connector {

    override val id          = "filesystem"
    override val name        = "Filesystem"
    override val description = "Read and write files in the app's scoped storage sandbox."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Sandbox ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("file", "storage", "read", "write", "local"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val roots = listOf(internalRoot(), cacheRoot())
        val allOk = roots.all { it.exists() || it.mkdirs() }
        _state.value = ConnectorState(
            connected = allOk, healthy = allOk,
            statusLine = if (allOk) "Sandbox ready (${roots.joinToString { it.name }})"
                         else "Cannot access sandbox roots",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val path = input.params["path"].orEmpty()
        if (path.isBlank()) {
            return@withContext ConnectorOutput.Failure(code = "bad_input", message = "Missing 'path' param")
        }
        when (input.action) {
            "read_text"   -> readText(path)
            "write_text"  -> writeText(path, input.text, append = false)
            "append_text" -> writeText(path, input.text, append = true)
            "list_dir"    -> listDir(path)
            "file_exists" -> fileExists(path)
            "delete_file" -> deleteFile(path)
            "make_dir"    -> makeDir(path)
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "FilesystemConnector: unknown action '${input.action}'",
            )
        }
    }

    // ── Sandbox helpers ────────────────────────────────────────────────────────

    private fun internalRoot(): File = appContext.filesDir
    private fun cacheRoot():   File = appContext.cacheDir
    private fun externalRoot(): File? = appContext.getExternalFilesDir(null)

    /**
     * Resolve a caller-supplied path to a real [File] inside the sandbox.
     * Returns null and a [ConnectorOutput.Failure] if the path escapes.
     */
    private fun resolve(path: String): Result<File> {
        val prefix = when {
            path.startsWith("internal://") -> Pair(internalRoot(), path.removePrefix("internal://"))
            path.startsWith("cache://")    -> Pair(cacheRoot(),    path.removePrefix("cache://"))
            path.startsWith("external://") -> {
                val ext = externalRoot() ?: return Result.failure(
                    IllegalStateException("External storage not available")
                )
                Pair(ext, path.removePrefix("external://"))
            }
            else -> Pair(internalRoot(), path.trimStart('/'))
        }
        val (root, relative) = prefix
        val resolved = File(root, relative).canonicalFile
        if (!resolved.path.startsWith(root.canonicalPath)) {
            Log.w(TAG, "PATH_TRAVERSAL_BLOCKED path=$path resolved=${resolved.path} root=${root.canonicalPath}")
            return Result.failure(SecurityException("Path escapes sandbox: $path"))
        }
        return Result.success(resolved)
    }

    // ── Action implementations ─────────────────────────────────────────────────

    private fun readText(path: String): ConnectorOutput {
        val file = resolve(path).getOrElse { return securityOrIoFailure(it) }
        if (!file.exists()) return ConnectorOutput.Failure(code = "not_found", message = "File not found: $path")
        if (!file.isFile) return ConnectorOutput.Failure(code = "not_a_file", message = "Not a file: $path")
        if (file.length() > MAX_READ_BYTES) {
            return ConnectorOutput.Failure(
                code = "too_large",
                message = "File exceeds ${MAX_READ_BYTES / 1024} KB limit: ${file.length() / 1024} KB",
            )
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return ConnectorOutput.Failure(code = "read_error", message = it.message ?: "IO error", retryable = true)
        }
        Log.i("AIRI_PROOF", "FS_READ path=$path bytes=${file.length()}")
        return ConnectorOutput.Success(
            text = text,
            data = mapOf("path" to file.path, "size_bytes" to file.length().toString()),
        )
    }

    private fun writeText(path: String, content: String, append: Boolean): ConnectorOutput {
        val file = resolve(path).getOrElse { return securityOrIoFailure(it) }
        file.parentFile?.mkdirs()
        return runCatching {
            if (append) file.appendText(content, Charsets.UTF_8)
            else        file.writeText(content, Charsets.UTF_8)
            Log.i("AIRI_PROOF", "FS_WRITE path=$path append=$append bytes=${content.length}")
            ConnectorOutput.Success(
                text = "${if (append) "Appended" else "Wrote"} ${content.length} bytes to $path",
                data = mapOf("path" to file.path, "bytes" to content.length.toString()),
            )
        }.getOrElse {
            ConnectorOutput.Failure(code = "write_error", message = it.message ?: "IO error", retryable = true)
        }
    }

    private fun listDir(path: String): ConnectorOutput {
        val dir = resolve(path).getOrElse { return securityOrIoFailure(it) }
        if (!dir.exists()) return ConnectorOutput.Failure(code = "not_found", message = "Directory not found: $path")
        if (!dir.isDirectory) return ConnectorOutput.Failure(code = "not_a_dir", message = "Not a directory: $path")
        val entries = dir.listFiles()?.map { f ->
            "${f.name}${if (f.isDirectory) "/" else ""}"
        }?.sorted() ?: emptyList()
        Log.i("AIRI_PROOF", "FS_LIST path=$path count=${entries.size}")
        return ConnectorOutput.Success(
            text = entries.joinToString("\n"),
            data = mapOf("path" to dir.path, "count" to entries.size.toString()),
        )
    }

    private fun fileExists(path: String): ConnectorOutput {
        val file = resolve(path).getOrElse { return securityOrIoFailure(it) }
        val exists = file.exists()
        return ConnectorOutput.Success(
            text = exists.toString(),
            data = mapOf("exists" to exists.toString(), "is_dir" to file.isDirectory.toString()),
        )
    }

    private fun deleteFile(path: String): ConnectorOutput {
        val file = resolve(path).getOrElse { return securityOrIoFailure(it) }
        if (!file.exists()) return ConnectorOutput.Failure(code = "not_found", message = "File not found: $path")
        if (file.isDirectory) return ConnectorOutput.Failure(code = "is_directory", message = "Use delete_dir for directories")
        val deleted = file.delete()
        Log.i("AIRI_PROOF", "FS_DELETE path=$path success=$deleted")
        return if (deleted) ConnectorOutput.Success(text = "Deleted: $path")
               else ConnectorOutput.Failure(code = "delete_failed", message = "Could not delete: $path")
    }

    private fun makeDir(path: String): ConnectorOutput {
        val dir = resolve(path).getOrElse { return securityOrIoFailure(it) }
        val created = dir.mkdirs()
        Log.i("AIRI_PROOF", "FS_MKDIR path=$path created=$created")
        return ConnectorOutput.Success(
            text = if (created) "Created: $path" else "Already exists: $path",
            data = mapOf("path" to dir.path, "created" to created.toString()),
        )
    }

    private fun securityOrIoFailure(t: Throwable): ConnectorOutput =
        ConnectorOutput.Failure(
            code = if (t is SecurityException) "security_violation" else "io_error",
            message = t.message ?: t.javaClass.simpleName,
        )

    companion object {
        private const val TAG = "FilesystemConnector"
        private const val MAX_READ_BYTES = 128 * 1024L
    }
}
