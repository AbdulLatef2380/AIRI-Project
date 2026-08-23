package com.airi.assistant.knowledge

import android.content.Context
import android.util.Log
import com.airi.assistant.workspace.ProjectFileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local, project-scoped knowledge index backed by explicit Project File actions.
 *
 * This manager deliberately uses deterministic lexical retrieval until a vetted
 * embedding indexer is selected. It therefore never labels a match as semantic
 * or claims a confidence the current runtime cannot calculate.
 */
class ProjectKnowledgeManager(
    private val context: Context,
    private val projectFileManager: ProjectFileManager
) {
    enum class IndexStatus { INDEXED, REJECTED, FAILED }

    data class KnowledgeChunk(
        val id: String = UUID.randomUUID().toString(),
        val projectId: String,
        val fileId: String,
        val sourceName: String,
        val sourceHash: String,
        val ordinal: Int,
        val content: String,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    data class IndexResult(
        val status: IndexStatus,
        val fileId: String,
        val chunkCount: Int = 0,
        val reason: String = ""
    )

    data class KnowledgeHit(
        val citationId: String,
        val projectId: String,
        val fileId: String,
        val sourceName: String,
        val sourceHash: String,
        val chunkOrdinal: Int,
        val content: String,
        val score: Float,
        val retrievalMethod: String = "LEXICAL_LOCAL"
    )

    private val gson = Gson()
    private val chunksById = ConcurrentHashMap<String, KnowledgeChunk>()
    private val indexFile = File(context.filesDir, "knowledge/project-file-index.json")
    private val _chunks = MutableStateFlow<List<KnowledgeChunk>>(emptyList())
    val chunks: StateFlow<List<KnowledgeChunk>> = _chunks.asStateFlow()

    init {
        restore()
    }

    /**
     * Builds or replaces the index for one user-selected project file. The
     * file must first enter ProjectFileManager's explicit index state.
     */
    suspend fun indexProjectFile(fileId: String): IndexResult = withContext(Dispatchers.IO) {
        val projectFile = projectFileManager.findById(fileId)
            ?: return@withContext IndexResult(IndexStatus.REJECTED, fileId, reason = "Project file was not found")
        if (!projectFile.isReady || projectFile.projectId.isBlank()) {
            return@withContext IndexResult(IndexStatus.REJECTED, fileId, reason = "Project file is not ready")
        }
        if (projectFile.extractionState != ProjectFileManager.ExtractionState.EXTRACTED) {
            projectFileManager.markIndexed(fileId, success = false, error = "Only extracted text files can be indexed")
            return@withContext IndexResult(IndexStatus.REJECTED, fileId, reason = "Text extraction is unavailable")
        }

        projectFileManager.requestIndex(fileId)
        val text = projectFileManager.readTextForIndex(fileId)
        if (text.isNullOrBlank()) {
            projectFileManager.markIndexed(fileId, success = false, error = "No readable text was available for indexing")
            return@withContext IndexResult(IndexStatus.FAILED, fileId, reason = "No readable text was available")
        }

        return@withContext runCatching {
            val newChunks = chunkText(text).mapIndexed { ordinal, content ->
                KnowledgeChunk(
                    projectId = projectFile.projectId,
                    fileId = projectFile.id,
                    sourceName = projectFile.name,
                    sourceHash = projectFile.sha256,
                    ordinal = ordinal,
                    content = content
                )
            }
            if (newChunks.isEmpty()) throw IllegalArgumentException("No indexable text segments were produced")

            chunksById.entries.removeIf { (_, chunk) -> chunk.fileId == projectFile.id }
            newChunks.forEach { chunk -> chunksById[chunk.id] = chunk }
            persist()
            projectFileManager.markIndexed(fileId, success = true)
            Log.i(TAG, "PROJECT_KNOWLEDGE_INDEXED file=$fileId chunks=${newChunks.size}")
            IndexResult(IndexStatus.INDEXED, fileId, chunkCount = newChunks.size)
        }.getOrElse { error ->
            val reason = (error.message ?: "Knowledge indexing failed").take(MAX_ERROR_CHARS)
            projectFileManager.markIndexed(fileId, success = false, error = reason)
            Log.w(TAG, "PROJECT_KNOWLEDGE_INDEX_FAILED file=$fileId type=${error.javaClass.simpleName}")
            IndexResult(IndexStatus.FAILED, fileId, reason = reason)
        }
    }

    /** Returns local lexical hits only within the requested project. */
    fun search(projectId: String, query: String, limit: Int = DEFAULT_LIMIT): List<KnowledgeHit> {
        val terms = normalizedTerms(query)
        if (projectId.isBlank() || terms.isEmpty()) return emptyList()
        pruneUnavailableSources()
        return chunksById.values.asSequence()
            .filter { chunk -> chunk.projectId == projectId }
            .mapNotNull { chunk ->
                val score = lexicalScore(chunk.content, terms)
                if (score <= 0f) null else KnowledgeHit(
                    citationId = "file-${chunk.fileId}-${chunk.ordinal}",
                    projectId = chunk.projectId,
                    fileId = chunk.fileId,
                    sourceName = chunk.sourceName,
                    sourceHash = chunk.sourceHash,
                    chunkOrdinal = chunk.ordinal,
                    content = chunk.content,
                    score = score
                )
            }
            .sortedWith(compareByDescending<KnowledgeHit> { it.score }.thenBy { it.sourceName }.thenBy { it.chunkOrdinal })
            .take(limit.coerceIn(1, MAX_RESULTS))
            .toList()
    }

    fun deleteIndexForFile(fileId: String): Boolean {
        val removed = chunksById.entries.removeIf { (_, chunk) -> chunk.fileId == fileId }
        if (removed) persist()
        return removed
    }

    /** Clears all project knowledge chunks and the local index file during data deletion. */
    fun deleteAll() {
        chunksById.clear()
        _chunks.value = emptyList()
        runCatching { indexFile.delete() }
    }

    private fun chunkText(text: String): List<String> =
        ProjectKnowledgeTextPolicy.chunkText(text)

    private fun lexicalScore(content: String, terms: List<String>): Float {
        val normalized = content.lowercase()
        val matches = terms.sumOf { term -> occurrences(normalized, term) }
        if (matches == 0) return 0f
        val coverage = matches.toFloat() / terms.size.toFloat()
        val phraseBonus = if (terms.joinToString(" ") in normalized) 0.35f else 0f
        return (coverage + phraseBonus).coerceAtMost(MAX_SCORE)
    }

    private fun occurrences(content: String, term: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = content.indexOf(term, start)
            if (index < 0) return count
            count++
            start = index + term.length
        }
    }

    private fun normalizedTerms(query: String): List<String> = query
        .lowercase()
        .split(TERM_SEPARATOR)
        .map(String::trim)
        .filter { it.length >= MIN_TERM_CHARS }
        .distinct()
        .take(MAX_QUERY_TERMS)

    private fun restore() {
        runCatching {
            if (!indexFile.exists()) return
            val type = object : TypeToken<List<KnowledgeChunk>>() {}.type
            val restored: List<KnowledgeChunk> = gson.fromJson(indexFile.readText(Charsets.UTF_8), type) ?: emptyList()
            restored.forEach { chunk -> chunksById[chunk.id] = chunk }
            pruneUnavailableSources(persistAfterPrune = false)
            publish()
        }.onFailure { error ->
            Log.w(TAG, "PROJECT_KNOWLEDGE_RESTORE_FAILED type=${error.javaClass.simpleName}")
        }
    }

    private fun pruneUnavailableSources(persistAfterPrune: Boolean = true) {
        val removed = chunksById.entries.removeIf { (_, chunk) ->
            val source = projectFileManager.findById(chunk.fileId)
            source == null ||
                source.lifecycle == ProjectFileManager.LifecycleState.DELETED ||
                source.indexState != ProjectFileManager.IndexState.INDEXED ||
                source.sha256 != chunk.sourceHash
        }
        if (removed && persistAfterPrune) persist() else publish()
    }

    private fun persist() {
        indexFile.parentFile?.mkdirs()
        val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
        runCatching {
            temp.writeText(gson.toJson(chunksById.values.toList()), Charsets.UTF_8)
            if (!temp.renameTo(indexFile)) {
                temp.copyTo(indexFile, overwrite = true)
                temp.delete()
            }
            publish()
        }.onFailure { error ->
            Log.w(TAG, "PROJECT_KNOWLEDGE_PERSIST_FAILED type=${error.javaClass.simpleName}")
        }
    }

    private fun publish() {
        _chunks.value = chunksById.values.sortedWith(compareBy<KnowledgeChunk> { it.sourceName }.thenBy { it.ordinal })
    }

    private companion object {
        const val TAG = "ProjectKnowledgeManager"
        const val MAX_RESULTS = 12
        const val DEFAULT_LIMIT = 5
        const val MAX_QUERY_TERMS = 12
        const val MIN_TERM_CHARS = 2
        const val MAX_ERROR_CHARS = 220
        const val MAX_SCORE = 8f
        val TERM_SEPARATOR = Regex("[^\\p{L}\\p{N}_-]+")
    }
}
