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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * NotesConnector — lightweight on-device note storage using plain text files.
 *
 * Notes are stored as individual `.md` (Markdown) files in the app's
 * `files/notes/` directory. This keeps them privacy-safe, offline, and
 * user-exportable without a database migration burden.
 *
 * ## Supported actions
 * | action       | params                       | notes                          |
 * |--------------|------------------------------|--------------------------------|
 * | `create`     | `title`, `body`              | Creates a new note             |
 * | `read`       | `note_id`                    | Returns note content           |
 * | `update`     | `note_id`, `body`            | Overwrites note body           |
 * | `delete`     | `note_id`                    | Removes the note file          |
 * | `list`       | `limit` (default 50)         | Lists all notes by modified    |
 * | `search`     | `query`                      | Full-text search across notes  |
 */
class NotesConnector(private val context: Context) : Connector {

    override val id          = "notes"
    override val name        = "Notes"
    override val description = "Create, read, update, and search on-device Markdown notes"
    override val type        = ConnectorType.LOCAL

    private val notesDir: File by lazy {
        File(context.filesDir, "notes").also { it.mkdirs() }
    }

    private val _state = MutableStateFlow(ConnectorState(connected = true, statusLine = "Ready"))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override fun meta() = ConnectorMeta(id, name, description, type,
        tags = listOf("notes", "markdown", "write", "personal"))

    override suspend fun connect(): ConnectorState {
        notesDir.mkdirs()
        val s = ConnectorState(connected = true, statusLine = "${noteFiles().size} notes stored")
        _state.value = s
        return s
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(connected = false)
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        try {
            when (input.action) {
                "create" -> create(input.params)
                "read"   -> read(input.params)
                "update" -> update(input.params)
                "delete" -> delete(input.params)
                "list"   -> list(input.params["limit"]?.toIntOrNull() ?: 50)
                "search" -> search(input.params["query"] ?: input.text)
                else -> ConnectorOutput.Failure("unknown_action",
                    "NotesConnector does not support: ${input.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "NotesConnector error: ${e.message}")
            ConnectorOutput.Failure("notes_error", e.message ?: "Unknown error", retryable = true)
        }
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private fun create(params: Map<String, String>): ConnectorOutput {
        val title = params["title"] ?: "Untitled"
        val body  = params["body"]  ?: ""
        val id    = UUID.randomUUID().toString().take(8)
        val file  = File(notesDir, "$id.md")
        val now   = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        file.writeText("# $title\n\n_Created: $now_\n\n$body")
        Log.i(TAG, "NOTE_CREATED id=$id title=$title")
        return ConnectorOutput.Success(
            text = "Note '$title' created (id=$id)",
            data = mapOf("note_id" to id, "title" to title)
        )
    }

    private fun read(params: Map<String, String>): ConnectorOutput {
        val id   = params["note_id"] ?: return ConnectorOutput.Failure("missing_param", "note_id required")
        val file = File(notesDir, "$id.md")
        if (!file.exists()) return ConnectorOutput.Failure("not_found", "Note $id not found")
        val content = file.readText()
        return ConnectorOutput.Success(
            text = content,
            data = mapOf("note_id" to id, "content" to content,
                "size" to content.length.toString())
        )
    }

    private fun update(params: Map<String, String>): ConnectorOutput {
        val id   = params["note_id"] ?: return ConnectorOutput.Failure("missing_param", "note_id required")
        val body = params["body"]    ?: return ConnectorOutput.Failure("missing_param", "body required")
        val file = File(notesDir, "$id.md")
        if (!file.exists()) return ConnectorOutput.Failure("not_found", "Note $id not found")
        // Preserve the title header
        val existing = file.readLines()
        val titleLine = existing.firstOrNull() ?: "# Note"
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        file.writeText("$titleLine\n\n_Updated: $now_\n\n$body")
        return ConnectorOutput.Success("Note $id updated", data = mapOf("note_id" to id))
    }

    private fun delete(params: Map<String, String>): ConnectorOutput {
        val id   = params["note_id"] ?: return ConnectorOutput.Failure("missing_param", "note_id required")
        val file = File(notesDir, "$id.md")
        if (!file.exists()) return ConnectorOutput.Failure("not_found", "Note $id not found")
        file.delete()
        return ConnectorOutput.Success("Note $id deleted", data = mapOf("note_id" to id))
    }

    private fun list(limit: Int): ConnectorOutput {
        val notes = noteFiles().sortedByDescending { it.lastModified() }.take(limit)
        val fmt   = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val arr   = JSONArray()
        for (f in notes) {
            val id      = f.nameWithoutExtension
            val title   = f.readLines().firstOrNull()
                ?.removePrefix("#").trim().ifBlank { id }
            arr.put(JSONObject()
                .put("id",       id)
                .put("title",    title)
                .put("modified", fmt.format(Date(f.lastModified())))
                .put("size",     f.length()))
        }
        return ConnectorOutput.Success(
            text = "${arr.length()} notes",
            data = mapOf("notes_json" to arr.toString())
        )
    }

    private fun search(query: String): ConnectorOutput {
        if (query.isBlank()) return ConnectorOutput.Failure("missing_param", "query must not be empty")
        val lq      = query.lowercase()
        val matches = JSONArray()
        for (f in noteFiles()) {
            val content = f.readText()
            if (content.lowercase().contains(lq)) {
                val id    = f.nameWithoutExtension
                val title = f.readLines().firstOrNull()?.removePrefix("#")?.trim() ?: id
                // Find a short excerpt around the first match
                val idx   = content.lowercase().indexOf(lq)
                val start = maxOf(0, idx - 40)
                val end   = minOf(content.length, idx + 80)
                val excerpt = "…${content.substring(start, end)}…"
                matches.put(JSONObject()
                    .put("id",      id)
                    .put("title",   title)
                    .put("excerpt", excerpt))
            }
        }
        return ConnectorOutput.Success(
            text = "${matches.length()} notes match '$query'",
            data = mapOf("results_json" to matches.toString())
        )
    }

    private fun noteFiles(): List<File> =
        notesDir.listFiles { f -> f.extension == "md" }?.toList() ?: emptyList()

    companion object { private const val TAG = "AIRI_NotesConnector" }
}
