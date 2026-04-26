package com.airi.assistant.memory.embedding

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.LlamaNative
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.MessageEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real semantic-memory pipeline.
 *
 *   loadEmbeddingModel(path)
 *     → JNI airi_load_embedding_model — opens a SECOND llama_context with
 *       embeddings=true and pooling_type=MEAN, separate from the chat
 *       context so the user's chat KV is never touched.
 *
 *   embedAndStore(message)
 *     → JNI airi_compute_embedding(text) → float[dim] → row in
 *       message_embedding table.
 *
 *   topKSimilar(query, k)
 *     → embed(query) → cosine-sim against every stored vector for the
 *       active session → return the top-k ChatMessage rows in descending
 *       similarity order.
 *
 * All native calls are serialised through a single mutex because
 * llama_decode is not reentrant per context. The ENTIRE chat path
 * uses Dispatchers.IO.limitedParallelism(1) for the same reason; the
 * embedding context is independent so we use a different mutex, but the
 * principle is identical.
 *
 * Honest limitation: requires the user to have downloaded a small
 * embedding GGUF (e.g. bge-small-en-v1.5.Q4_K_M.gguf, ~30 MB). When no
 * embedding model is loaded, [topKSimilar] returns an empty list and
 * logs an explicit AIRI_PROOF EMBEDDING_NOT_LOADED — the chat path then
 * falls back to chronological recall via MemoryManager.getRecentMessages.
 * No silent fallback to fake similarity scores.
 */
class EmbeddingService(context: Context) {

    private val db  = AiriDatabase.getDatabase(context)
    private val dao = db.embeddingDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val nativeLock = Mutex()

    @Volatile private var loadedPath: String? = null
    @Volatile private var dim: Int = 0

    fun isReady(): Boolean = loadedPath != null && dim > 0

    fun currentDim(): Int = dim

    /** Path of the currently-loaded embedding model, or null. */
    fun loadedModelPath(): String? = loadedPath

    /**
     * Load (or replace) the embedding model. Returns the dimensionality on
     * success, or null on failure (with an AIRI_PROOF entry explaining
     * why). Safe to call repeatedly — replacing the model invalidates all
     * previously stored vectors of a different dim, but the search path
     * already filters by dim so stale rows are simply ignored until they
     * are pruned.
     */
    suspend fun loadEmbeddingModel(modelPath: String): Int? = withContext(nativeDispatcher) {
        nativeLock.withLock {
            val f = File(modelPath)
            if (!f.exists()) {
                Log.i("AIRI_PROOF", "EMBEDDING_MODEL_LOAD_FAILED reason=file_not_found path=$modelPath")
                return@withLock null
            }
            if (!LlamaNative.isAvailable()) {
                Log.i("AIRI_PROOF", "EMBEDDING_MODEL_LOAD_FAILED reason=native_lib_missing")
                return@withLock null
            }
            val rc = runCatching { LlamaNative.loadEmbeddingModel(modelPath) }
                .getOrElse {
                    Log.i("AIRI_PROOF", "EMBEDDING_MODEL_LOAD_FAILED reason=jni_throw msg=${it.message}")
                    return@withLock null
                }
            // Native returns "OK dim=NNN" on success, "ERR_*" on failure.
            if (!rc.startsWith("OK")) {
                Log.i("AIRI_PROOF", "EMBEDDING_MODEL_LOAD_FAILED reason=$rc")
                return@withLock null
            }
            val parsedDim = rc.substringAfter("dim=", "0").toIntOrNull() ?: 0
            if (parsedDim <= 0) {
                Log.i("AIRI_PROOF", "EMBEDDING_MODEL_LOAD_FAILED reason=bad_dim raw=$rc")
                return@withLock null
            }
            loadedPath = modelPath
            dim = parsedDim
            Log.i("AIRI_PROOF", "EMBEDDING_MODEL_LOADED dim=$parsedDim path=${f.name}")
            parsedDim
        }
    }

    /** Free the native embedding context. Safe to call when nothing is loaded. */
    suspend fun unload() = withContext(nativeDispatcher) {
        nativeLock.withLock {
            if (loadedPath != null) {
                runCatching { LlamaNative.unloadEmbeddingModel() }
                Log.i("AIRI_PROOF", "EMBEDDING_MODEL_UNLOADED path=${loadedPath?.substringAfterLast('/')}")
            }
            loadedPath = null
            dim = 0
        }
    }

    /**
     * Compute and persist the embedding for one freshly-stored message.
     * No-op (with proof) if the embedding model is not loaded so the
     * caller can fire-and-forget on every chat insert without branching.
     */
    suspend fun embedAndStore(message: ChatMessage): Boolean {
        if (!isReady()) {
            Log.i("AIRI_PROOF", "EMBEDDING_SKIPPED reason=not_loaded msg_id=${message.id}")
            return false
        }
        val text = message.content.trim()
        if (text.isEmpty()) return false
        val vec = computeRaw(text) ?: return false
        val bytes = floatArrayToBytes(vec)
        runCatching {
            dao.upsert(
                MessageEmbedding(
                    messageId = message.id,
                    sessionId = message.sessionId,
                    role      = message.role,
                    dim       = vec.size,
                    vector    = bytes
                )
            )
        }.onFailure {
            Log.w("AIRI_EMBED", "embedAndStore: persist failed: ${it.message}")
            return false
        }
        Log.i(
            "AIRI_PROOF",
            "EMBEDDING_CREATED msg_id=${message.id} session=${message.sessionId} dim=${vec.size} bytes=${bytes.size}"
        )
        return true
    }

    /**
     * Top-k cosine-similar previous messages from the same session.
     * Brute-force linear scan over the per-session row set (≤ 200 rows
     * by AIRI's per-session prune cap). Results are sorted by descending
     * similarity. Returns an empty list if the embedding model is not
     * loaded — caller should fall back to chronological recall.
     */
    suspend fun topKSimilar(
        sessionId: String,
        query: String,
        k: Int = 5,
        minSimilarity: Float = 0.25f
    ): List<RankedMessage> {
        if (!isReady()) {
            Log.i("AIRI_PROOF", "VECTOR_SEARCH_SKIPPED reason=not_loaded session=$sessionId")
            return emptyList()
        }
        if (query.isBlank() || k <= 0) return emptyList()
        val qVec = computeRaw(query) ?: return emptyList()
        val rows = dao.getAllForSession(sessionId, qVec.size)
        if (rows.isEmpty()) {
            Log.i("AIRI_PROOF", "VECTOR_SEARCH_NO_INDEX session=$sessionId dim=${qVec.size}")
            return emptyList()
        }
        // Score every row, then partial-sort.
        data class Scored(val messageId: Long, val score: Float)
        val scored = ArrayList<Scored>(rows.size)
        for (row in rows) {
            val v = bytesToFloatArray(row.vector, row.dim)
            // L2-normalised vectors → dot product == cosine similarity.
            var dot = 0f
            for (i in 0 until row.dim) dot += qVec[i] * v[i]
            if (dot >= minSimilarity) scored.add(Scored(row.messageId, dot))
        }
        if (scored.isEmpty()) {
            Log.i("AIRI_PROOF", "VECTOR_SEARCH_EMPTY session=$sessionId candidates=${rows.size} threshold=$minSimilarity")
            return emptyList()
        }
        scored.sortByDescending { it.score }
        val topIds = scored.take(k).map { it.messageId }
        val msgs = dao.loadMessagesByIds(topIds).associateBy { it.id }
        val out = topIds.mapNotNull { id ->
            val msg = msgs[id] ?: return@mapNotNull null
            val score = scored.first { it.messageId == id }.score
            RankedMessage(msg, score)
        }
        Log.i(
            "AIRI_PROOF",
            "VECTOR_SEARCH_HIT session=$sessionId candidates=${rows.size} returned=${out.size} top_score=${out.first().score}"
        )
        return out
    }

    /** Build a context block from the top-k messages, ready to splice into a prompt. */
    fun formatContext(hits: List<RankedMessage>): String {
        if (hits.isEmpty()) return ""
        val lines = hits.joinToString("\n") {
            "- (${"%.2f".format(it.score)}) ${it.message.role}: ${it.message.content.take(280)}"
        }
        return buildString {
            append("Relevant prior context (semantic memory):\n")
            append(lines)
            append("\n")
        }.also {
            Log.i("AIRI_PROOF", "CONTEXT_AUGMENTED hits=${hits.size} bytes=${it.length}")
        }
    }

    /**
     * Token-budget aware variant. Returns a triple of:
     *   (block, hitsUsed, estimatedTokens).
     *
     * The caller passes a HARD token cap (typically 20% of n_ctx). We greedily
     * include hits — best-similarity first — and stop the moment adding
     * another hit would exceed [maxTokens]. Per-hit content is also trimmed
     * to a per-hit char cap so a single very long message can't poison the
     * budget.
     *
     * NEVER returns a block that exceeds [maxTokens]. If even the smallest
     * possible 1-hit block would overflow, returns ("", 0, 0) so the chat
     * path falls back to plain (chronological) recall — no silent overflow.
     *
     * Token estimation deliberately matches PromptCompressor.estimateTokens:
     * `chars / 4` rounded up. We pad by +8 for the header line.
     *
     * Emits AIRI_PROOF CONTEXT_INJECTED hits=X tokens=Y on success,
     * CONTEXT_INJECTION_SKIPPED reason=… on the empty/over-budget paths.
     */
    fun formatContextWithBudget(
        hits: List<RankedMessage>,
        maxTokens: Int,
        maxCharsPerHit: Int = 220
    ): Triple<String, Int, Int> {
        if (hits.isEmpty() || maxTokens <= 0) {
            Log.i("AIRI_PROOF",
                "CONTEXT_INJECTION_SKIPPED reason=${if (hits.isEmpty()) "no_hits" else "no_budget"} " +
                "hits=${hits.size} maxTokens=$maxTokens")
            return Triple("", 0, 0)
        }
        val header = "Relevant prior context (semantic memory):\n"
        val headerTokens = (header.length + 3) / 4
        if (headerTokens >= maxTokens) {
            Log.i("AIRI_PROOF",
                "CONTEXT_INJECTION_SKIPPED reason=header_exceeds_budget " +
                "headerTokens=$headerTokens maxTokens=$maxTokens")
            return Triple("", 0, 0)
        }
        val sb = StringBuilder(header)
        var totalTokens = headerTokens
        var used = 0
        for (h in hits) {
            val trimmedContent = h.message.content.take(maxCharsPerHit)
            val line = "- (${"%.2f".format(h.score)}) ${h.message.role}: $trimmedContent\n"
            val lineTokens = (line.length + 3) / 4
            if (totalTokens + lineTokens > maxTokens) break
            sb.append(line)
            totalTokens += lineTokens
            used++
        }
        if (used == 0) {
            Log.i("AIRI_PROOF",
                "CONTEXT_INJECTION_SKIPPED reason=first_hit_over_budget " +
                "maxTokens=$maxTokens header=$headerTokens")
            return Triple("", 0, 0)
        }
        Log.i("AIRI_PROOF",
            "CONTEXT_INJECTED hits=$used tokens=$totalTokens budget=$maxTokens bytes=${sb.length}")
        return Triple(sb.toString(), used, totalTokens)
    }

    // ── Native bridge ────────────────────────────────────────────────────────
    private suspend fun computeRaw(text: String): FloatArray? = withContext(nativeDispatcher) {
        nativeLock.withLock {
            if (!isReady()) return@withLock null
            val raw = runCatching { LlamaNative.computeEmbedding(text) }
                .getOrElse {
                    Log.i("AIRI_PROOF", "EMBEDDING_FAILED reason=jni_throw msg=${it.message}")
                    return@withLock null
                }
            if (raw == null || raw.isEmpty()) {
                Log.i("AIRI_PROOF", "EMBEDDING_FAILED reason=null_or_empty")
                return@withLock null
            }
            raw
        }
    }

    private fun floatArrayToBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    private fun bytesToFloatArray(b: ByteArray, dim: Int): FloatArray {
        val out = FloatArray(dim)
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until dim) out[i] = buf.float
        return out
    }

    data class RankedMessage(val message: ChatMessage, val score: Float)

    companion object {
        @Volatile private var INSTANCE: EmbeddingService? = null
        fun getInstance(context: Context): EmbeddingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EmbeddingService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
