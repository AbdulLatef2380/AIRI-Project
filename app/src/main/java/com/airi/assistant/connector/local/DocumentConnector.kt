package com.airi.assistant.connector.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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

/**
 * DocumentConnector — extract text from user-shared documents via [ContentResolver].
 *
 * The user grants access to a document through Android's Storage Access
 * Framework (file picker / share sheet). The agent receives a `content://` or
 * `file://` URI as the `uri` param and this connector reads it.
 *
 * ## Supported MIME types
 * Plain text, Markdown, CSV, JSON, XML, HTML, and any other MIME type whose
 * raw bytes are valid UTF-8 are read directly. For binary formats (PDF, DOCX)
 * the connector returns a clear error rather than garbled bytes — those
 * formats require a dedicated parser library.
 *
 * ## Supported actions
 * | action        | required params | notes                                    |
 * |---------------|-----------------|------------------------------------------|
 * | `read_uri`    | `uri`           | Reads the content at the given URI       |
 * | `mime_type`   | `uri`           | Returns MIME type without reading content|
 * | `read_lines`  | `uri`, `start`, `end` | Returns specific line range       |
 */
class DocumentConnector(
    private val appContext: Context,
) : Connector {

    override val id          = "document"
    override val name        = "Document Reader"
    override val description = "Read text from shared documents, files, and URIs."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("document", "file", "text", "pdf", "uri", "read"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        _state.value = ConnectorState(
            connected = true, healthy = true,
            statusLine = "ContentResolver ready",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val rawUri = input.params["uri"].orEmpty().ifBlank { input.text }
        if (rawUri.isBlank()) {
            return@withContext ConnectorOutput.Failure(code = "bad_input", message = "Missing 'uri' param")
        }
        val uri = runCatching { Uri.parse(rawUri) }.getOrElse {
            return@withContext ConnectorOutput.Failure(code = "bad_uri", message = "Invalid URI: $rawUri")
        }
        when (input.action) {
            "read_uri"   -> readUri(uri)
            "mime_type"  -> getMimeType(uri)
            "read_lines" -> {
                val start = input.params["start"]?.toIntOrNull() ?: 1
                val end   = input.params["end"]?.toIntOrNull()   ?: Int.MAX_VALUE
                readLines(uri, start, end)
            }
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "DocumentConnector: unknown action '${input.action}'",
            )
        }
    }

    // ── Implementations ────────────────────────────────────────────────────────

    private fun readUri(uri: Uri): ConnectorOutput {
        val mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        if (mimeType in BINARY_TYPES) {
            return ConnectorOutput.Failure(
                code = "binary_format",
                message = "Binary format '$mimeType' requires a dedicated parser. Use read_uri on plain text / markdown / CSV / JSON files.",
            )
        }
        return runCatching {
            val text = readContent(uri)
            Log.i("AIRI_PROOF", "DOC_READ uri=${uri.toString().take(80)} mime=$mimeType chars=${text.length}")
            ConnectorOutput.Success(
                text = text,
                data = mapOf(
                    "uri"       to uri.toString(),
                    "mime_type" to mimeType,
                    "chars"     to text.length.toString(),
                ),
            )
        }.getOrElse { t ->
            Log.w("AIRI_PROOF", "DOC_READ_FAILED uri=${uri.toString().take(80)} cause=${t.message}")
            ConnectorOutput.Failure(
                code = "read_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    private fun getMimeType(uri: Uri): ConnectorOutput {
        val mimeType = appContext.contentResolver.getType(uri) ?: "unknown"
        return ConnectorOutput.Success(
            text = mimeType,
            data = mapOf("mime_type" to mimeType),
        )
    }

    private fun readLines(uri: Uri, startLine: Int, endLine: Int): ConnectorOutput {
        return runCatching {
            val mimeType = appContext.contentResolver.getType(uri) ?: "text/plain"
            if (mimeType in BINARY_TYPES) {
                return ConnectorOutput.Failure(
                    code = "binary_format",
                    message = "Cannot read lines from binary format: $mimeType",
                )
            }
            val allLines = readContent(uri).lines()
            val total    = allLines.size
            val from     = (startLine - 1).coerceIn(0, total)
            val to       = endLine.coerceIn(from, total)
            val selected = allLines.subList(from, to)
            ConnectorOutput.Success(
                text = selected.joinToString("\n"),
                data = mapOf(
                    "start_line"   to (from + 1).toString(),
                    "end_line"     to to.toString(),
                    "total_lines"  to total.toString(),
                    "lines_read"   to selected.size.toString(),
                ),
            )
        }.getOrElse { t ->
            ConnectorOutput.Failure(
                code = "read_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    private fun readContent(uri: Uri): String {
        val resolver: ContentResolver = appContext.contentResolver
        return resolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > MAX_BYTES) {
                String(bytes, 0, MAX_BYTES, Charsets.UTF_8) +
                    "\n\n[Truncated — document exceeds ${MAX_BYTES / 1024} KB limit]"
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } ?: throw IllegalStateException("ContentResolver returned null stream for $uri")
    }

    companion object {
        private val BINARY_TYPES = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/zip",
            "application/octet-stream",
        )
        private const val MAX_BYTES = 256 * 1024
    }
}
