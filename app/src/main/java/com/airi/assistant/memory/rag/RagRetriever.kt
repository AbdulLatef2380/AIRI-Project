package com.airi.assistant.memory.rag

import android.util.Log
import com.airi.assistant.memory.embedding.EmbeddingService
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.repository.MemoryManager

/**
 * RagRetriever — Retrieval-Augmented Generation wired into the main agent flow.
 *
 * REAL EXECUTION:
 *   1. Semantic search via [EmbeddingService.topKSimilar] over the Room
 *      embedding store (L2-normalised cosine similarity, same vectors the
 *      MemoryAgent uses for RECALL).
 *   2. Falls back to chronological [MemoryManager.getRecentMessages] when
 *      the embedding backend is not loaded (no model on device, cold start).
 *   3. Applies a relevance threshold ([MIN_SCORE]) to exclude low-quality
 *      hits that would add noise rather than signal.
 *   4. Formats the retrieved passages into a system-prompt fragment that the
 *      orchestrator injects BEFORE the user's query in every LLM call.
 *
 * WIRING:
 *   - [ServiceLocator.ragRetriever] holds the singleton.
 *   - [ChatViewModel.sendMessage] calls [buildContextBlock] and prepends
 *     the result to the system prompt BEFORE delegating to the backend.
 *   - The retriever is bypassed entirely when [isReady] returns false or
 *     [memoryManager.isSemanticMemoryReady] is false.
 *
 * PRIVACY:
 *   - All retrieval is local-only (Room + EmbeddingService).
 *   - Retrieved passages are included in the prompt sent to the LLM backend
 *     (which may be cloud). If privacy=MAXIMUM (local-only), only the local
 *     LlamaManager receives the augmented prompt — no cloud leakage.
 */
class RagRetriever(
    private val memoryManager: MemoryManager
) {

    companion object {
        private const val TAG       = "RagRetriever"
        private const val MIN_SCORE = 0.30f  // cosine similarity floor
        private const val DEFAULT_K = 5
        private const val FALLBACK_LIMIT = 8
        private const val MAX_CONTEXT_CHARS = 2_400
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** True when the semantic retrieval backend is ready. */
    fun isReady(): Boolean = memoryManager.isSemanticMemoryReady()

    /**
     * Retrieve the top-k most relevant prior messages for [query] in
     * [sessionId] and format them as a system-prompt context block.
     *
     * Returns an empty string when no relevant passages are found, so the
     * caller can append it unconditionally without adding empty sections.
     */
    suspend fun buildContextBlock(
        sessionId: String,
        query:     String,
        k:         Int = DEFAULT_K
    ): String {
        val passages = retrieve(sessionId, query, k)
        if (passages.isEmpty()) return ""

        val formatted = passages.joinToString("\n") { p ->
            "[${p.role.uppercase()}] ${p.content.take(240)}"
        }
        val block = """
--- Relevant prior context (retrieved from memory) ---
$formatted
--- End of retrieved context ---
        """.trimIndent()

        Log.d(TAG, "AIRI_PROOF RAG_CONTEXT_BUILT hits=${passages.size} chars=${block.length}")
        return block.take(MAX_CONTEXT_CHARS)
    }

    /**
     * Raw retrieval — returns ranked passages without formatting.
     *
     * Uses semantic search when the embedding backend is ready; falls back
     * to the most recent chronological messages otherwise.
     */
    suspend fun retrieve(
        sessionId: String,
        query:     String,
        k:         Int = DEFAULT_K
    ): List<RetrievedPassage> {
        return if (memoryManager.isSemanticMemoryReady()) {
            retrieveSemantic(sessionId, query, k)
        } else {
            retrieveChronological(sessionId)
        }
    }

    /**
     * Multi-query retrieval — runs [buildContextBlock] for multiple query
     * facets and merges the results, deduplicating by message content.
     *
     * Useful when the planner decomposes a goal into multiple search queries.
     */
    suspend fun buildMultiQueryContext(
        sessionId: String,
        queries:   List<String>,
        kPerQuery: Int = 3
    ): String {
        val seen     = mutableSetOf<String>()
        val passages = mutableListOf<RetrievedPassage>()
        for (q in queries.take(4)) {
            val hits = retrieve(sessionId, q, kPerQuery)
            for (p in hits) {
                val key = "${p.role}:${p.content.take(60)}"
                if (seen.add(key)) passages.add(p)
            }
        }
        if (passages.isEmpty()) return ""

        val formatted = passages
            .sortedByDescending { it.score }
            .take(DEFAULT_K)
            .joinToString("\n") { p ->
                "[${p.role.uppercase()}] ${p.content.take(240)}"
            }
        return """
--- Relevant prior context (multi-query RAG) ---
$formatted
--- End of retrieved context ---
        """.trimIndent().take(MAX_CONTEXT_CHARS)
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private suspend fun retrieveSemantic(
        sessionId: String,
        query:     String,
        k:         Int
    ): List<RetrievedPassage> {
        val hits = runCatching {
            memoryManager.semanticSearch(sessionId, query, k)
        }.getOrElse { e ->
            Log.w(TAG, "Semantic search failed: ${e.message}")
            emptyList()
        }

        val filtered = hits
            .filter { it.score >= MIN_SCORE }
            .map { ranked ->
                RetrievedPassage(
                    role    = ranked.message.role,
                    content = ranked.message.content,
                    score   = ranked.score,
                    source  = "semantic"
                )
            }

        Log.d(TAG, "RAG semantic hits=${hits.size} filtered=${filtered.size} " +
            "minScore=${filtered.minOfOrNull { it.score } ?: 0f}")
        return filtered
    }

    private suspend fun retrieveChronological(sessionId: String): List<RetrievedPassage> {
        val msgs = runCatching {
            memoryManager.getRecentMessages(sessionId, FALLBACK_LIMIT)
        }.getOrElse { emptyList() }

        Log.d(TAG, "RAG chronological fallback hits=${msgs.size}")
        return msgs.map { m ->
            RetrievedPassage(
                role    = m.role,
                content = m.content,
                score   = 0f,
                source  = "chronological"
            )
        }
    }
}

data class RetrievedPassage(
    val role:    String,
    val content: String,
    val score:   Float,
    val source:  String
)
