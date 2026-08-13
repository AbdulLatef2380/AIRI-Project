package com.airi.assistant.media

import android.content.Context
import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import com.airi.assistant.workspace.ArtifactManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaLibrary — unified central media repository for AIRI.
 *
 * ── CAPABILITIES ──────────────────────────────────────────────────────────────
 *
 *   - Images captured via camera or gallery, resized thumbnails
 *   - Documents imported by the user (PDF, TXT, DOCX stubs)
 *   - Generated files from ArtifactManager (code, markdown, JSON, HTML…)
 *   - Full-text search across names, tags, and MIME types
 *   - Rich filtering: by [MediaType], session ID, date range, tags
 *   - Metadata: MIME type, size, creation/update timestamps, session, tags
 *
 * ── STORAGE ───────────────────────────────────────────────────────────────────
 *
 *   <filesDir>/media_library/<type>/<filename>
 *   In-memory index ([_allItems]) backed by [ConcurrentHashMap] for O(1) lookups.
 *   The index is rebuilt from disk on [scanFromDisk] — no separate DB table
 *   required, keeping the schema small and migration-free.
 *
 * ── INTEGRATION ───────────────────────────────────────────────────────────────
 *
 *   - [ArtifactManager.importToLibrary] calls [addFromArtifact] after every
 *     artifact creation so generated files appear in the Media Library automatically.
 *   - [AgentActivityBus] receives a SANDBOX event on every import.
 *   - [MemoryManager] can tag library items by session for cross-session retrieval.
 *
 * ── PRIVACY ───────────────────────────────────────────────────────────────────
 *
 *   Local storage only. No automatic cloud upload; the user must explicitly
 *   enable cloud backup in PrivacyDataSettingsScreen.
 */
class MediaLibrary(private val context: Context) {

    private val TAG = "MediaLibrary"

    // ── Types ─────────────────────────────────────────────────────────────────

    enum class MediaType(val folder: String, val emoji: String) {
        IMAGE     ("images",     "🖼"),
        DOCUMENT  ("documents",  "📄"),
        GENERATED ("generated",  "🤖"),
        AUDIO     ("audio",      "🎵"),
        VIDEO     ("video",      "🎬"),
        OTHER     ("other",      "📦")
    }

    data class MediaItem(
        val id:          String = UUID.randomUUID().toString().take(8),
        val name:        String,
        val type:        MediaType,
        val mimeType:    String,
        val filePath:    String,
        val sizeBytes:   Long    = 0L,
        val sessionId:   String  = "",
        val tags:        List<String> = emptyList(),
        val description: String  = "",
        val sourceUri:   String  = "",
        val createdAtMs: Long    = System.currentTimeMillis(),
        val updatedAtMs: Long    = System.currentTimeMillis()
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val index            = ConcurrentHashMap<String, MediaItem>()
    private val _allItems        = MutableStateFlow<List<MediaItem>>(emptyList())
    val allItems: StateFlow<List<MediaItem>> = _allItems.asStateFlow()

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Import an external file (e.g., from camera or gallery) into the library.
     * The file is COPIED into managed storage so the original can be deleted safely.
     *
     * @return The created [MediaItem], or null if the source file does not exist.
     */
    suspend fun importFile(
        sourceFile:  File,
        type:        MediaType,
        mimeType:    String,
        sessionId:   String = "",
        tags:        List<String> = emptyList(),
        description: String = ""
    ): MediaItem? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) {
            Log.w(TAG, "importFile: source not found: ${sourceFile.absolutePath}")
            return@withContext null
        }
        val dest = destinationFile(type, sourceFile.name)
        runCatching { sourceFile.copyTo(dest, overwrite = true) }.onFailure { e ->
            Log.e(TAG, "importFile: copy failed: ${e.message}")
            return@withContext null
        }
        val item = MediaItem(
            name        = sourceFile.name,
            type        = type,
            mimeType    = mimeType,
            filePath    = dest.absolutePath,
            sizeBytes   = dest.length(),
            sessionId   = sessionId,
            tags        = tags,
            description = description,
            sourceUri   = sourceFile.absolutePath
        )
        commit(item)
        AgentActivityBus.emit("${type.emoji} Media imported: ${item.name} (${kb(dest.length())}KB)", ActivityCategory.MEMORY)
        Log.i(TAG, "AIRI_RUNTIME MEDIA_IMPORTED id=${item.id} type=${type.name} size=${dest.length()}")
        item
    }

    /**
     * Import raw bytes directly (e.g., an image from a network response or
     * a camera capture delivered as ByteArray).
     */
    suspend fun importBytes(
        bytes:       ByteArray,
        fileName:    String,
        type:        MediaType,
        mimeType:    String,
        sessionId:   String = "",
        tags:        List<String> = emptyList(),
        description: String = ""
    ): MediaItem = withContext(Dispatchers.IO) {
        val dest = destinationFile(type, fileName)
        dest.writeBytes(bytes)
        val item = MediaItem(
            name        = fileName,
            type        = type,
            mimeType    = mimeType,
            filePath    = dest.absolutePath,
            sizeBytes   = bytes.size.toLong(),
            sessionId   = sessionId,
            tags        = tags,
            description = description
        )
        commit(item)
        Log.i(TAG, "AIRI_RUNTIME MEDIA_BYTES_IMPORTED id=${item.id} bytes=${bytes.size}")
        item
    }

    /**
     * Import a generated artifact directly from [ArtifactManager].
     * Content is read from the artifact's existing file — no copy needed
     * because both live under <filesDir>.
     */
    suspend fun addFromArtifact(artifact: ArtifactManager.Artifact): MediaItem =
        withContext(Dispatchers.IO) {
            val existing = index.values.firstOrNull { it.filePath == artifact.filePath }
            if (existing != null) return@withContext existing.also {
                Log.d(TAG, "addFromArtifact: already indexed id=${existing.id}")
            }
            val item = MediaItem(
                name        = "${artifact.name}.${artifact.type.ext}",
                type        = MediaType.GENERATED,
                mimeType    = mimeTypeForArtifact(artifact.type),
                filePath    = artifact.filePath,
                sizeBytes   = artifact.sizeBytes,
                sessionId   = artifact.sessionId,
                description = artifact.description,
                createdAtMs = artifact.createdAtMs,
                updatedAtMs = artifact.updatedAtMs
            )
            commit(item)
            Log.i(TAG, "AIRI_RUNTIME MEDIA_ARTIFACT_INDEXED id=${item.id} artifact=${artifact.name}")
            item
        }

    /**
     * Update tags or description for an existing item. Returns the updated
     * item, or null if the id is not found.
     */
    fun updateMetadata(
        id:          String,
        tags:        List<String>? = null,
        description: String? = null
    ): MediaItem? {
        val existing = index[id] ?: return null
        val updated  = existing.copy(
            tags        = tags ?: existing.tags,
            description = description ?: existing.description,
            updatedAtMs = System.currentTimeMillis()
        )
        index[id] = updated
        publishAll()
        return updated
    }

    // ── Delete API ────────────────────────────────────────────────────────────

    /**
     * Delete an item from the index and its backing file from disk.
     * No-op if [id] is not found.
     */
    fun delete(id: String) {
        val item = index.remove(id) ?: return
        // Only delete files we own (GENERATED items share a path with ArtifactManager — leave those alone)
        if (item.type != MediaType.GENERATED) {
            runCatching { File(item.filePath).delete() }
        }
        publishAll()
        Log.i(TAG, "AIRI_RUNTIME MEDIA_DELETED id=$id type=${item.type}")
    }

    fun deleteAllForSession(sessionId: String) {
        val keys = index.values.filter { it.sessionId == sessionId }.map { it.id }
        keys.forEach { delete(it) }
    }

    // ── Query / Search API ────────────────────────────────────────────────────

    fun getItem(id: String): MediaItem? = index[id]

    /** All items for a specific session, newest-first. */
    fun forSession(sessionId: String): List<MediaItem> =
        index.values.filter { it.sessionId == sessionId }
            .sortedByDescending { it.createdAtMs }

    /** All items of a specific type. */
    fun byType(type: MediaType): List<MediaItem> =
        index.values.filter { it.type == type }
            .sortedByDescending { it.createdAtMs }

    /**
     * Full-text search across name, description, and tags.
     * Case-insensitive substring match.
     */
    fun search(query: String, type: MediaType? = null): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return if (type != null) byType(type) else allItemsSorted()
        return index.values
            .filter { item ->
                (type == null || item.type == type) &&
                    (item.name.contains(q, ignoreCase = true) ||
                     item.description.contains(q, ignoreCase = true) ||
                     item.tags.any { it.contains(q, ignoreCase = true) })
            }
            .sortedByDescending { it.createdAtMs }
    }

    /**
     * Filter by date range (inclusive).
     * Pass 0L for either bound to skip that constraint.
     */
    fun byDateRange(
        fromMs: Long = 0L,
        toMs:   Long = 0L,
        type:   MediaType? = null
    ): List<MediaItem> = index.values
        .filter { item ->
            (type == null || item.type == type) &&
            (fromMs == 0L || item.createdAtMs >= fromMs) &&
            (toMs == 0L || item.createdAtMs <= toMs)
        }
        .sortedByDescending { it.createdAtMs }

    /**
     * Filter by tag (exact match, case-insensitive).
     */
    fun byTag(tag: String): List<MediaItem> =
        index.values.filter { item ->
            item.tags.any { it.equals(tag, ignoreCase = true) }
        }.sortedByDescending { it.createdAtMs }

    /**
     * Summary counts by type — useful for the Media Library screen header.
     */
    fun summaryCounts(): Map<MediaType, Int> =
        MediaType.entries.associateWith { t -> index.values.count { it.type == t } }

    // ── Disk scan ─────────────────────────────────────────────────────────────

    /**
     * Rebuild the in-memory index from files on disk. Call once at app start
     * (after [ArtifactManager] has loaded) to restore the library from a
     * previous session. Generated items are NOT scanned here — they are
     * re-indexed via [addFromArtifact] called by ArtifactManager.
     */
    suspend fun scanFromDisk() = withContext(Dispatchers.IO) {
        var count = 0
        for (type in MediaType.entries) {
            if (type == MediaType.GENERATED) continue
            val dir = mediaDir(type)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !index.values.any { it.filePath == file.absolutePath }) {
                    val item = MediaItem(
                        name      = file.name,
                        type      = type,
                        mimeType  = guessMimeType(file.name),
                        filePath  = file.absolutePath,
                        sizeBytes = file.length()
                    )
                    index[item.id] = item
                    count++
                }
            }
        }
        publishAll()
        Log.i(TAG, "AIRI_RUNTIME MEDIA_SCAN_COMPLETE discovered=$count total=${index.size}")
    }

    // ── Read content ──────────────────────────────────────────────────────────

    suspend fun readBytes(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val item = index[id] ?: return@withContext null
        runCatching { File(item.filePath).readBytes() }.getOrNull()
    }

    suspend fun readText(id: String): String? = withContext(Dispatchers.IO) {
        val item = index[id] ?: return@withContext null
        runCatching { File(item.filePath).readText(Charsets.UTF_8) }.getOrNull()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun commit(item: MediaItem) {
        index[item.id] = item
        publishAll()
    }

    private fun publishAll() {
        _allItems.value = allItemsSorted()
    }

    private fun allItemsSorted(): List<MediaItem> =
        index.values.sortedByDescending { it.createdAtMs }

    private fun destinationFile(type: MediaType, name: String): File {
        val dir = mediaDir(type).also { it.mkdirs() }
        return File(dir, name)
    }

    private fun mediaDir(type: MediaType): File =
        File(context.filesDir, "media_library/${type.folder}")

    private fun kb(bytes: Long): Long = (bytes / 1024L).coerceAtLeast(1L)

    private fun mimeTypeForArtifact(artifactType: ArtifactManager.ArtifactType): String =
        when (artifactType) {
            ArtifactManager.ArtifactType.CODE_KOTLIN,
            ArtifactManager.ArtifactType.CODE_PYTHON,
            ArtifactManager.ArtifactType.CODE_JS,
            ArtifactManager.ArtifactType.SHELL_SCRIPT  -> "text/plain"
            ArtifactManager.ArtifactType.CODE_HTML,
            ArtifactManager.ArtifactType.WEBSITE        -> "text/html"
            ArtifactManager.ArtifactType.MARKDOWN,
            ArtifactManager.ArtifactType.REPORT,
            ArtifactManager.ArtifactType.PRESENTATION   -> "text/markdown"
            ArtifactManager.ArtifactType.JSON,
            ArtifactManager.ArtifactType.AUTOMATION     -> "application/json"
            ArtifactManager.ArtifactType.TEXT           -> "text/plain"
            ArtifactManager.ArtifactType.DIAGRAM        -> "text/plain"
            ArtifactManager.ArtifactType.UNKNOWN        -> "application/octet-stream"
        }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "pdf"         -> "application/pdf"
            "txt"         -> "text/plain"
            "md"          -> "text/markdown"
            "html"        -> "text/html"
            "json"        -> "application/json"
            "mp3"         -> "audio/mpeg"
            "mp4"         -> "video/mp4"
            else          -> "application/octet-stream"
        }
    }
}
