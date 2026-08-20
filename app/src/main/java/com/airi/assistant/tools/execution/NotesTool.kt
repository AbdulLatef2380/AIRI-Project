package com.airi.assistant.tools.execution

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * NotesTool — local note creation, reading, searching, and deletion.
 *
 * Persistence: JSON file in the app's internal files directory.
 * File: airi_notes.json — atomic write-to-temp-then-rename for crash safety.
 *
 * No permissions required (internal storage only).
 * Notes are private to AIRI and are NOT synced to any cloud service
 * without explicit user consent.
 *
 * StateFlow [notes] enables reactive UI observation.
 */
class NotesTool(private val context: Context) {

    private val TAG  = "NotesTool"
    private val gson = Gson()

    private val notesFile get() = File(context.filesDir, "airi_notes.json")
    private val tempFile  get() = File(context.filesDir, "airi_notes_tmp.json")

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    /** Live note list — observe for reactive UI updates. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    init {
        // Load persisted notes on construction (blocking OK — called during
        // ServiceLocator lazy init, which is on the calling thread)
        _notes.value = loadFromDisk()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a new note.
     *
     * @param title   Note title
     * @param body    Note body text
     * @param tags    Optional list of tags for search/filtering
     * @param pinned  True to pin the note to the top
     * @return The created [Note].
     */
    suspend fun createNote(
        title:  String,
        body:   String,
        tags:   List<String> = emptyList(),
        pinned: Boolean      = false
    ): Note = withContext(Dispatchers.IO) {
        val note = Note(
            id          = UUID.randomUUID().toString(),
            title       = title.trim(),
            body        = body.trim(),
            tags        = tags.map { it.lowercase().trim() },
            pinned      = pinned,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
        val updated = (_notes.value + note).sortedWith(
            compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAtMs }
        )
        _notes.value = updated
        saveToDisk(updated)
        Log.i(TAG, "Note created: '${note.title}' id=${note.id}")
        note
    }

    /**
     * Update an existing note by ID.
     * Pass null for any field to keep the existing value.
     *
     * @return Updated [Note], or null if note not found.
     */
    suspend fun updateNote(
        id:     String,
        title:  String? = null,
        body:   String? = null,
        tags:   List<String>? = null,
        pinned: Boolean? = null
    ): Note? = withContext(Dispatchers.IO) {
        val current = _notes.value.toMutableList()
        val idx     = current.indexOfFirst { it.id == id }
        if (idx < 0) {
            Log.w(TAG, "Note not found: $id")
            return@withContext null
        }
        val old  = current[idx]
        val new  = old.copy(
            title       = title?.trim() ?: old.title,
            body        = body?.trim()  ?: old.body,
            tags        = tags?.map { it.lowercase().trim() } ?: old.tags,
            pinned      = pinned ?: old.pinned,
            updatedAtMs = System.currentTimeMillis()
        )
        current[idx] = new
        val sorted = current.sortedWith(
            compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAtMs }
        )
        _notes.value = sorted
        saveToDisk(sorted)
        Log.i(TAG, "Note updated: '${new.title}' id=$id")
        new
    }

    /**
     * Append text to an existing note's body.
     */
    suspend fun appendToNote(id: String, appendText: String): Note? {
        val note = _notes.value.find { it.id == id } ?: return null
        return updateNote(id, body = "${note.body}\n\n${appendText.trim()}")
    }

    /**
     * Delete a note by ID.
     * @return true if deleted.
     */
    suspend fun deleteNote(id: String): Boolean = withContext(Dispatchers.IO) {
        val current = _notes.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            _notes.value = current
            saveToDisk(current)
            Log.i(TAG, "Note deleted: $id")
        }
        removed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read API
    // ─────────────────────────────────────────────────────────────────────────

    /** Get all notes (pinned first, newest first). */
    fun getAll(): List<Note> = _notes.value

    /** Get a single note by ID. */
    fun getById(id: String): Note? = _notes.value.find { it.id == id }

    /**
     * Search notes by query (matches title, body, and tags).
     * Case-insensitive.
     */
    fun search(query: String): List<Note> {
        val lower = query.lowercase().trim()
        return _notes.value.filter { note ->
            note.title.lowercase().contains(lower) ||
            note.body.lowercase().contains(lower)  ||
            note.tags.any { it.contains(lower) }
        }
    }

    /** Get notes with a specific tag. */
    fun getByTag(tag: String): List<Note> {
        val lower = tag.lowercase().trim()
        return _notes.value.filter { it.tags.contains(lower) }
    }

    /** Get pinned notes only. */
    fun getPinned(): List<Note> = _notes.value.filter { it.pinned }

    /** Format note list as LLM-readable summary. */
    fun summarize(notes: List<Note> = _notes.value): String {
        if (notes.isEmpty()) return "No notes found."
        return notes.take(20).joinToString("\n\n") { note ->
            val time = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(note.updatedAtMs))
            val pinMark = if (note.pinned) " " else ""
            val tagStr  = if (note.tags.isNotEmpty()) "  [${note.tags.joinToString(", ")}]" else ""
            "── ${note.title}$pinMark$tagStr  ($time) ──\n${note.body.take(200)}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToDisk(notes: List<Note>) {
        try {
            val json = gson.toJson(notes)
            tempFile.writeText(json)
            tempFile.renameTo(notesFile)
        } catch (e: Exception) {
            Log.e(TAG, "saveToDisk failed: ${e.message}")
        }
    }

    private fun loadFromDisk(): List<Note> {
        if (!notesFile.exists()) return emptyList()
        return try {
            val json = notesFile.readText()
            val type = object : TypeToken<List<Note>>() {}.type
            gson.fromJson<List<Note>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "loadFromDisk failed: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data model
    // ─────────────────────────────────────────────────────────────────────────

    data class Note(
        val id:          String,
        val title:       String,
        val body:        String,
        val tags:        List<String> = emptyList(),
        val pinned:      Boolean      = false,
        val createdAtMs: Long,
        val updatedAtMs: Long
    ) {
        val formattedDate: String get() =
            SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
                .format(Date(updatedAtMs))
    }
}
