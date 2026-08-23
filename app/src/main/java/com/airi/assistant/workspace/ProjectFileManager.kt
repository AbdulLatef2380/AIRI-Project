package com.airi.assistant.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.airi.assistant.media.MediaLibrary
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped file lifecycle manager.
 *
 * This is deliberately distinct from an attachment, generated artifact,
 * knowledge source, or memory entry. A project file is a user-controlled local
 * resource. It may be linked to knowledge later, but importing it does not
 * silently place its text in a model context or a knowledge index.
 */
class ProjectFileManager(
    private val context: Context,
    private val mediaLibrary: MediaLibrary,
    /** Removes external knowledge entries when a file leaves the active project set. */
    private val onFileDeleted: (ProjectFile) -> Unit = {}
) {
    enum class LifecycleState {
        IMPORTING,
        VALIDATING,
        HASHING,
        STORING,
        EXTRACTING,
        INDEXING,
        READY,
        FAILED,
        DELETED
    }

    enum class ExtractionState {
        NOT_APPLICABLE,
        PENDING,
        EXTRACTED,
        FAILED
    }

    enum class IndexState {
        NOT_REQUESTED,
        PENDING,
        INDEXED,
        FAILED
    }

    data class ProjectFile(
        val id: String = UUID.randomUUID().toString(),
        val projectId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long = 0L,
        val sha256: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
        val modifiedAtMs: Long = createdAtMs,
        val storagePath: String = "",
        val sourceUri: String = "",
        val mediaItemId: String = "",
        val lifecycle: LifecycleState = LifecycleState.IMPORTING,
        val extractionState: ExtractionState = ExtractionState.PENDING,
        val indexState: IndexState = IndexState.NOT_REQUESTED,
        val previewText: String = "",
        val tags: List<String> = emptyList(),
        val folder: String = "",
        val isFavorite: Boolean = false,
        /** Private recovery copy retained only while lifecycle is DELETED. */
        val trashPath: String = "",
        val error: String = ""
    ) {
        val isReady: Boolean get() = lifecycle == LifecycleState.READY
    }

    sealed class ImportResult {
        data class Imported(val file: ProjectFile) : ImportResult()
        data class Duplicate(val existing: ProjectFile) : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    /** Private backup reference returned only to the project-file edit runtime. */
    class TextRevisionBackup internal constructor(
        val revisionId: String,
        val projectId: String,
        val fileId: String,
        val contentHash: String,
        internal val privatePath: String
    )

    sealed class TextRevisionResult {
        data class Applied(val file: ProjectFile, val backup: TextRevisionBackup) : TextRevisionResult()
        data class Conflict(val reason: String) : TextRevisionResult()
        data class Rejected(val reason: String) : TextRevisionResult()
        data class Failed(val reason: String) : TextRevisionResult()
    }

    private val gson = Gson()
    private val store = ConcurrentHashMap<String, ProjectFile>()
    private val indexFile = File(context.filesDir, "workspace/project-files/index.json")
    private val _files = MutableStateFlow<List<ProjectFile>>(emptyList())
    val files: StateFlow<List<ProjectFile>> = _files.asStateFlow()

    init {
        restore()
    }

    fun forProject(projectId: String): List<ProjectFile> = files.value
        .filter { it.projectId == projectId && it.lifecycle != LifecycleState.DELETED }
        .sortedByDescending { it.modifiedAtMs }

    fun deletedForProject(projectId: String): List<ProjectFile> = files.value
        .filter { it.projectId == projectId && it.lifecycle == LifecycleState.DELETED }
        .sortedByDescending { it.modifiedAtMs }

    fun findById(id: String): ProjectFile? = store[id]

    fun findDuplicate(projectId: String, sha256: String): ProjectFile? = store.values.firstOrNull {
        it.projectId == projectId &&
            it.sha256 == sha256 &&
            it.lifecycle != LifecycleState.DELETED
    }

    suspend fun importFromUri(projectId: String, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        if (projectId.isBlank()) return@withContext ImportResult.Failed("A project is required before importing a file")
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameColumn >= 0) cursor.getString(nameColumn) else null
        }.orEmpty().ifBlank { "imported-file" }
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
            ?: return@withContext ImportResult.Failed("The selected file could not be opened")
        stream.use { input ->
            importStream(projectId, displayName, mimeType, uri.toString(), input)
        }
    }

    /** Used by trusted in-app producers and tests; external UI should use [importFromUri]. */
    suspend fun importFromBytes(
        projectId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        sourceUri: String = "in-app"
    ): ImportResult = withContext(Dispatchers.IO) {
        bytes.inputStream().use { input ->
            importStream(projectId, name, mimeType, sourceUri, input)
        }
    }

    fun rename(id: String, name: String): ProjectFile? = update(id) { current ->
        val cleanName = name.trim().take(MAX_FILE_NAME_CHARS)
        if (cleanName.isBlank()) current else current.copy(name = cleanName)
    }

    fun updateMetadata(
        id: String,
        tags: List<String>? = null,
        folder: String? = null,
        isFavorite: Boolean? = null
    ): ProjectFile? = update(id) { current ->
        current.copy(
            tags = tags?.map(String::trim)?.filter(String::isNotBlank)?.distinct().orEmpty().ifEmpty { current.tags },
            folder = folder?.trim()?.take(MAX_FOLDER_CHARS) ?: current.folder,
            isFavorite = isFavorite ?: current.isFavorite
        )
    }

    /** Queues a file for later knowledge indexing; it does not claim indexing success. */
    fun requestIndex(id: String): ProjectFile? = update(id) { current ->
        if (!current.isReady) current else current.copy(indexState = IndexState.PENDING)
    }

    /** Reads bounded text only for an explicit knowledge-indexing operation. */
    suspend fun readTextForIndex(id: String): String? = withContext(Dispatchers.IO) {
        val file = store[id] ?: return@withContext null
        if (!file.isReady || file.extractionState != ExtractionState.EXTRACTED || file.storagePath.isBlank()) {
            return@withContext null
        }
        runCatching {
            File(file.storagePath).bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(MAX_INDEXABLE_CHARS)
                val count = reader.read(chars)
                if (count <= 0) null else String(chars, 0, count)
            }
        }.getOrNull()
    }

    /** Reads a bounded managed text file only after a project-owned edit proposal is requested. */
    suspend fun readTextForEdit(projectId: String, id: String): String? = withContext(Dispatchers.IO) {
        val file = store[id] ?: return@withContext null
        if (!isEditableTextFile(file, projectId)) return@withContext null
        runCatching {
            File(file.storagePath).inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(MAX_EDITABLE_CHARS + 1)
                val count = reader.read(chars)
                if (count !in 0..MAX_EDITABLE_CHARS) null else String(chars, 0, count)
            }
        }.getOrNull()
    }

    /**
     * Replaces one managed textual file only when its project and expected content
     * hash still match. Candidate bytes are written to a sibling temporary file
     * and renamed in place; a failed rename leaves the active source untouched.
     */
    suspend fun applyTextRevision(
        projectId: String,
        id: String,
        expectedContentHash: String,
        candidateText: String
    ): TextRevisionResult = withContext(Dispatchers.IO) {
        val current = store[id] ?: return@withContext TextRevisionResult.Rejected("Project file is unavailable")
        if (!isEditableTextFile(current, projectId)) {
            return@withContext TextRevisionResult.Rejected("Only ready managed text files can be edited")
        }
        val candidateBytes = candidateText.toByteArray(Charsets.UTF_8)
        if (candidateBytes.size > MAX_EDITABLE_BYTES) {
            return@withContext TextRevisionResult.Rejected("Edited content exceeds the local edit limit")
        }
        val source = File(current.storagePath)
        val currentHash = sha256(source) ?: return@withContext TextRevisionResult.Failed("Managed file cannot be read")
        if (currentHash != expectedContentHash) {
            return@withContext TextRevisionResult.Conflict("The project file changed before approval")
        }
        val candidateHash = sha256(candidateBytes)
        if (candidateHash == currentHash) {
            return@withContext TextRevisionResult.Rejected("The proposed content does not change the file")
        }

        val backup = createTextRevisionBackup(current, source, currentHash)
            ?: return@withContext TextRevisionResult.Failed("Could not create a private recovery copy")
        val temp = File(source.parentFile, ".${source.name}.${UUID.randomUUID()}.edit")
        val writeSucceeded = runCatching {
            FileOutputStream(temp).use { output ->
                output.write(candidateBytes)
                output.fd.sync()
            }
            temp.renameTo(source)
        }.getOrDefault(false)
        if (!writeSucceeded) {
            temp.delete()
            return@withContext TextRevisionResult.Failed("Atomic project-file replacement failed")
        }

        val updated = current.copy(
            sizeBytes = candidateBytes.size.toLong(),
            sha256 = candidateHash,
            modifiedAtMs = System.currentTimeMillis(),
            extractionState = ExtractionState.EXTRACTED,
            previewText = candidateText.take(MAX_EXTRACTED_CHARS).trim(),
            indexState = IndexState.NOT_REQUESTED,
            error = ""
        )
        store[id] = updated
        publish()
        if (!persistIndex()) {
            val restored = restoreTextRevisionInternal(current, backup)
            return@withContext if (restored) {
                TextRevisionResult.Failed("Project-file metadata could not be persisted; the prior content was restored")
            } else {
                TextRevisionResult.Failed("Project-file metadata could not be persisted after replacement")
            }
        }
        onFileDeleted(current)
        TextRevisionResult.Applied(updated, backup)
    }

    /** Restores a private revision only when the currently active file still matches the caller's expected hash. */
    suspend fun restoreTextRevision(
        projectId: String,
        id: String,
        expectedCurrentHash: String,
        backup: TextRevisionBackup
    ): TextRevisionResult = withContext(Dispatchers.IO) {
        val current = store[id] ?: return@withContext TextRevisionResult.Rejected("Project file is unavailable")
        if (!isEditableTextFile(current, projectId) || backup.projectId != projectId || backup.fileId != id) {
            return@withContext TextRevisionResult.Rejected("Project file recovery ownership check failed")
        }
        val source = File(current.storagePath)
        val activeHash = sha256(source) ?: return@withContext TextRevisionResult.Failed("Managed file cannot be read")
        if (activeHash != expectedCurrentHash) {
            return@withContext TextRevisionResult.Conflict("The project file changed before recovery")
        }
        if (!restoreTextRevisionInternal(current, backup)) {
            return@withContext TextRevisionResult.Failed("Project-file recovery failed")
        }
        store[id]?.let { restored ->
            onFileDeleted(current)
            TextRevisionResult.Applied(restored, backup)
        } ?: TextRevisionResult.Failed("Project-file recovery metadata is unavailable")
    }

    /** Removes a private historical backup after the editor's bounded retention policy expires. */
    fun discardTextRevisionBackup(backup: TextRevisionBackup): Boolean = runCatching {
        val path = File(backup.privatePath)
        path.startsWith(textRevisionDirectory(backup.projectId)) && (!path.exists() || path.delete())
    }.getOrDefault(false)

    /** Called only by a real knowledge-indexing runtime. */
    fun markIndexed(id: String, success: Boolean, error: String = ""): ProjectFile? = update(id) { current ->
        current.copy(
            indexState = if (success) IndexState.INDEXED else IndexState.FAILED,
            error = if (success) current.error else error.take(MAX_ERROR_CHARS)
        )
    }

    /**
     * Moves a project file to private local trash before removing its active
     * media-library copy. A failed archive leaves the live resource untouched.
     */
    fun delete(id: String): Boolean {
        val file = store[id] ?: return false
        if (file.lifecycle == LifecycleState.DELETED) return true
        val source = file.storagePath.takeIf(String::isNotBlank)?.let(::File)
            ?.takeIf(File::exists) ?: return false
        val trash = trashFile(file)
        if (runCatching {
                trash.parentFile?.mkdirs()
                source.copyTo(trash, overwrite = true)
            }.isFailure) return false
        mediaLibrary.delete(file.mediaItemId)
        runCatching { source.delete() }
        store[id] = file.copy(
            lifecycle = LifecycleState.DELETED,
            modifiedAtMs = System.currentTimeMillis(),
            previewText = "",
            trashPath = trash.absolutePath
        )
        publishAndPersist()
        onFileDeleted(file)
        return true
    }

    /**
     * Clears active files, private trash, and the persisted project-file index.
     * Used by account-data deletion; safe to repeat after a partial cleanup.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        val removed = store.values.toList()
        removed.forEach { file ->
            if (file.mediaItemId.isNotBlank()) mediaLibrary.delete(file.mediaItemId)
            onFileDeleted(file)
        }
        store.clear()
        publish()
        File(context.filesDir, "workspace/project-files").deleteRecursively()
    }

    /** Restores a soft-deleted file to the same project from its private trash copy. */
    suspend fun restore(id: String): ProjectFile? = withContext(Dispatchers.IO) {
        val deleted = store[id] ?: return@withContext null
        if (deleted.lifecycle != LifecycleState.DELETED || deleted.trashPath.isBlank()) return@withContext null
        val trash = File(deleted.trashPath)
        if (!trash.exists()) return@withContext null
        val libraryItem = mediaLibrary.importFile(
            sourceFile = trash,
            type = mediaTypeFor(deleted.mimeType),
            mimeType = deleted.mimeType,
            sessionId = deleted.projectId,
            description = "Restored project file"
        ) ?: return@withContext null
        val restoring = deleted.copy(
            lifecycle = LifecycleState.EXTRACTING,
            storagePath = libraryItem.filePath,
            mediaItemId = libraryItem.id,
            trashPath = "",
            modifiedAtMs = System.currentTimeMillis()
        )
        val extraction = extractPreview(restoring)
        val restored = restoring.copy(
            lifecycle = LifecycleState.READY,
            extractionState = extraction.state,
            previewText = extraction.preview,
            error = extraction.error,
            indexState = IndexState.NOT_REQUESTED,
            modifiedAtMs = System.currentTimeMillis()
        )
        runCatching { trash.delete() }
        replace(restored)
        restored
    }

    /** Permanently removes a file already in trash and its persisted metadata. */
    fun purge(id: String): Boolean {
        val deleted = store[id] ?: return false
        if (deleted.lifecycle != LifecycleState.DELETED) return false
        if (deleted.trashPath.isNotBlank()) runCatching { File(deleted.trashPath).delete() }
        store.remove(id)
        publishAndPersist()
        return true
    }

    private suspend fun importStream(
        projectId: String,
        rawName: String,
        mimeType: String,
        sourceUri: String,
        input: java.io.InputStream
    ): ImportResult {
        val id = UUID.randomUUID().toString()
        val safeName = normalizeFileName(rawName)
        var record = ProjectFile(
            id = id,
            projectId = projectId,
            name = safeName,
            mimeType = mimeType,
            sourceUri = sourceUri,
            lifecycle = LifecycleState.IMPORTING,
            extractionState = extractionStateFor(mimeType)
        )
        store[id] = record
        publishAndPersist()

        val stagingDir = File(context.cacheDir, "project-file-imports").also { it.mkdirs() }
        val staged = File(stagingDir, "$id-$safeName")
        return runCatching {
            record = record.copy(lifecycle = LifecycleState.VALIDATING, modifiedAtMs = System.currentTimeMillis())
            replace(record)

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    if (totalBytes > MAX_IMPORT_BYTES) {
                        throw IllegalArgumentException("The file exceeds the ${MAX_IMPORT_BYTES / (1024 * 1024)} MB import limit")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            if (totalBytes == 0L) throw IllegalArgumentException("The selected file is empty")

            val hash = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            record = record.copy(
                lifecycle = LifecycleState.HASHING,
                sizeBytes = totalBytes,
                sha256 = hash,
                modifiedAtMs = System.currentTimeMillis()
            )
            replace(record)

            findDuplicate(projectId, hash)?.takeIf { it.id != id }?.let { duplicate ->
                store.remove(id)
                publishAndPersist()
                staged.delete()
                return ImportResult.Duplicate(duplicate)
            }

            record = record.copy(lifecycle = LifecycleState.STORING, modifiedAtMs = System.currentTimeMillis())
            replace(record)
            val libraryItem = mediaLibrary.importFile(
                sourceFile = staged,
                type = mediaTypeFor(mimeType),
                mimeType = mimeType,
                sessionId = projectId,
                description = "Project file"
            ) ?: throw IllegalStateException("Managed storage rejected the imported file")
            staged.delete()

            record = record.copy(
                lifecycle = LifecycleState.EXTRACTING,
                storagePath = libraryItem.filePath,
                mediaItemId = libraryItem.id,
                modifiedAtMs = System.currentTimeMillis()
            )
            replace(record)

            val extraction = extractPreview(record)
            record = record.copy(
                lifecycle = LifecycleState.INDEXING,
                extractionState = extraction.state,
                previewText = extraction.preview,
                error = extraction.error,
                modifiedAtMs = System.currentTimeMillis()
            )
            replace(record)

            // Knowledge ingestion is explicit. PENDING means this resource is
            // ready for use but has not been silently added to model knowledge.
            record = record.copy(
                lifecycle = LifecycleState.READY,
                indexState = IndexState.NOT_REQUESTED,
                modifiedAtMs = System.currentTimeMillis()
            )
            replace(record)
            Log.i(TAG, "PROJECT_FILE_READY id=$id project=$projectId bytes=$totalBytes")
            ImportResult.Imported(record)
        }.getOrElse { throwable ->
            staged.delete()
            val failed = record.copy(
                lifecycle = LifecycleState.FAILED,
                error = (throwable.message ?: "Import failed").take(MAX_ERROR_CHARS),
                modifiedAtMs = System.currentTimeMillis()
            )
            replace(failed)
            Log.w(TAG, "PROJECT_FILE_IMPORT_FAILED id=$id type=${throwable.javaClass.simpleName}")
            ImportResult.Failed(failed.error)
        }
    }

    private fun extractPreview(file: ProjectFile): Extraction {
        if (file.extractionState == ExtractionState.NOT_APPLICABLE) {
            return Extraction(ExtractionState.NOT_APPLICABLE)
        }
        return runCatching {
            FileInputStream(file.storagePath).bufferedReader(Charsets.UTF_8).use { reader ->
                val chars = CharArray(MAX_EXTRACTED_CHARS)
                val count = reader.read(chars)
                val preview = if (count > 0) String(chars, 0, count).trim() else ""
                Extraction(ExtractionState.EXTRACTED, preview)
            }
        }.getOrElse { error ->
            Extraction(ExtractionState.FAILED, error = "Text extraction unavailable")
        }
    }

    private fun extractionStateFor(mimeType: String): ExtractionState =
        ProjectFilePolicy.extractionStateFor(mimeType)

    private fun mediaTypeFor(mimeType: String): MediaLibrary.MediaType = when {
        mimeType.startsWith("image/") -> MediaLibrary.MediaType.IMAGE
        mimeType.startsWith("audio/") -> MediaLibrary.MediaType.AUDIO
        mimeType.startsWith("video/") -> MediaLibrary.MediaType.VIDEO
        mimeType.startsWith("text/") || ProjectFilePolicy.isTextualApplicationType(mimeType) -> MediaLibrary.MediaType.DOCUMENT
        else -> MediaLibrary.MediaType.OTHER
    }

    private fun normalizeFileName(raw: String): String =
        ProjectFilePolicy.normalizeFileName(raw)

    private fun trashFile(file: ProjectFile): File =
        File(context.filesDir, "workspace/project-files/trash/${file.projectId}/${file.id}-${file.name}")

    private fun textRevisionDirectory(projectId: String): File =
        File(context.filesDir, "workspace/project-files/history/$projectId")

    private fun createTextRevisionBackup(
        file: ProjectFile,
        source: File,
        contentHash: String
    ): TextRevisionBackup? = runCatching {
        val revisionId = UUID.randomUUID().toString()
        val backupFile = File(textRevisionDirectory(file.projectId), "${file.id}-$revisionId.backup")
        backupFile.parentFile?.mkdirs()
        source.copyTo(backupFile, overwrite = false)
        TextRevisionBackup(revisionId, file.projectId, file.id, contentHash, backupFile.absolutePath)
    }.getOrNull()

    private fun restoreTextRevisionInternal(current: ProjectFile, backup: TextRevisionBackup): Boolean {
        val backupFile = File(backup.privatePath)
        val source = File(current.storagePath)
        if (
            !backupFile.startsWith(textRevisionDirectory(current.projectId)) ||
            !backupFile.exists() ||
            sha256(backupFile) != backup.contentHash
        ) return false
        val temp = File(source.parentFile, ".${source.name}.${UUID.randomUUID()}.restore")
        val restored = runCatching {
            FileInputStream(backupFile).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            temp.renameTo(source)
        }.getOrDefault(false)
        if (!restored) {
            temp.delete()
            return false
        }
        val restoredText = runCatching { source.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val restoredRecord = current.copy(
            sizeBytes = source.length(),
            sha256 = backup.contentHash,
            modifiedAtMs = System.currentTimeMillis(),
            extractionState = ExtractionState.EXTRACTED,
            previewText = restoredText.take(MAX_EXTRACTED_CHARS).trim(),
            indexState = IndexState.NOT_REQUESTED,
            error = ""
        )
        store[current.id] = restoredRecord
        publish()
        return persistIndex()
    }

    private fun isEditableTextFile(file: ProjectFile, projectId: String): Boolean =
        file.projectId == projectId &&
            file.isReady &&
            file.storagePath.isNotBlank() &&
            (file.mimeType.startsWith("text/") || ProjectFilePolicy.isTextualApplicationType(file.mimeType))

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private fun update(id: String, transform: (ProjectFile) -> ProjectFile): ProjectFile? {
        val current = store[id] ?: return null
        val updated = transform(current).copy(modifiedAtMs = System.currentTimeMillis())
        replace(updated)
        return updated
    }

    private fun replace(file: ProjectFile) {
        store[file.id] = file
        publishAndPersist()
    }

    private fun restore() {
        runCatching {
            if (!indexFile.exists()) return
            val type = object : TypeToken<List<ProjectFile>>() {}.type
            val restored: List<ProjectFile> = gson.fromJson(indexFile.readText(Charsets.UTF_8), type) ?: emptyList()
            restored.filter { it.lifecycle != LifecycleState.DELETED || it.storagePath.isNotBlank() }
                .forEach { file ->
                    if (file.lifecycle == LifecycleState.DELETED || File(file.storagePath).exists()) {
                        store[file.id] = file
                    }
                }
            publish()
        }.onFailure { error ->
            Log.w(TAG, "PROJECT_FILE_RESTORE_FAILED type=${error.javaClass.simpleName}")
        }
    }

    private fun publishAndPersist() {
        publish()
        persistIndex()
    }

    private fun persistIndex(): Boolean = runCatching {
        indexFile.parentFile?.mkdirs()
        val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        temp.writeText(gson.toJson(store.values.toList()), Charsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            temp.copyTo(indexFile, overwrite = true)
            temp.delete()
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "PROJECT_FILE_PERSIST_FAILED type=${error.javaClass.simpleName}")
        false
    }

    private fun publish() {
        _files.value = store.values.sortedByDescending { it.modifiedAtMs }
    }

    private data class Extraction(
        val state: ExtractionState,
        val preview: String = "",
        val error: String = ""
    )

    private companion object {
        const val TAG = "ProjectFileManager"
        const val COPY_BUFFER_BYTES = 8 * 1024
        const val MAX_IMPORT_BYTES = 100L * 1024L * 1024L
        const val MAX_EXTRACTED_CHARS = 8 * 1024
        const val MAX_INDEXABLE_CHARS = 250 * 1024
        const val MAX_EDITABLE_CHARS = 250 * 1024
        const val MAX_EDITABLE_BYTES = 1_024 * 1_024
        const val MAX_FILE_NAME_CHARS = 160
        const val MAX_FOLDER_CHARS = 80
        const val MAX_ERROR_CHARS = 240
    }
}

/** Pure validation policy used by the real project-file import path. */
internal object ProjectFilePolicy {
    private const val MAX_FILE_NAME_CHARS = 160
    private val textualApplicationTypes = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/sql",
        "application/x-yaml"
    )

    fun normalizeFileName(raw: String): String = raw
        .trim()
        .replace(Regex("[^a-zA-Z0-9._() -]"), "_")
        .take(MAX_FILE_NAME_CHARS)
        .ifBlank { "imported-file" }

    fun extractionStateFor(mimeType: String): ProjectFileManager.ExtractionState = when {
        mimeType.startsWith("text/") -> ProjectFileManager.ExtractionState.PENDING
        mimeType in textualApplicationTypes -> ProjectFileManager.ExtractionState.PENDING
        else -> ProjectFileManager.ExtractionState.NOT_APPLICABLE
    }

    fun isTextualApplicationType(mimeType: String): Boolean = mimeType in textualApplicationTypes
}
