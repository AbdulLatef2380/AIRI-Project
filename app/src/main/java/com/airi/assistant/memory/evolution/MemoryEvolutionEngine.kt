package com.airi.assistant.memory.evolution

import android.content.Context
import android.util.Log
import com.airi.core.memory.text.MemoryTextNormalizer
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MemoryEvolutionEngine — advanced memory scoring beyond cosine similarity.
 *
 * ── CAPABILITIES ──────────────────────────────────────────────────────────────
 *
 * 1. SALIENCE SCORING
 *    Combines: semantic similarity (cosine) + recency (temporal decay) +
 *    execution outcome weighting (messages from successful execution paths
 *    score higher) + interaction frequency (frequently-recalled memories
 *    rank higher = reinforcement).
 *
 * 2. TEMPORAL DECAY
 *    Score × exp(−λ × ageHours) with a configurable half-life.
 *    Default: 24-hour half-life (a memory from yesterday is 50% as relevant
 *    as an identical memory from today).
 *
 * 3. EXECUTION OUTCOME WEIGHTING
 *    When a memory was part of a successful agent execution, it receives a
 *    +20% salience boost. When it was part of a failure, −10%. Stored via
 *    SharedPreferences (lightweight, no Room schema change needed).
 *
 * 4. CONFLICT RESOLUTION
 *    Detects when two high-salience memories carry contradictory information
 *    (heuristic: same role + near-identical content + different timestamps).
 *    Returns the more recent one and logs a MEMORY_CONFLICT event.
 *
 * 5. PREFERENCE PERSISTENCE RANKING
 *    Tracks per-session recall frequency. Memories recalled ≥3 times get a
 *    +15% boost (the system learned this memory is relevant to this user).
 *
 * ── INTEGRATION ───────────────────────────────────────────────────────────────
 * [EmbeddingService.topKSimilar] calls [applySalienceScoring] to upgrade the
 * raw cosine-similarity ranking to a blended salience score before returning
 * to the caller.
 */
class MemoryEvolutionEngine(context: Context) {

    companion object {
        private const val TAG                   = "MemoryEvolutionEngine"
        private const val PREFS_NAME            = "airi_memory_evolution"
        private const val DEFAULT_HALF_LIFE_H   = 24f     // hours
        private const val OUTCOME_BOOST_SUCCESS = 1.20f
        private const val OUTCOME_PENALTY_FAIL  = 0.90f
        private const val RECALL_BOOST          = 1.15f
        private const val RECALL_BOOST_THRESHOLD = 3       // recall count to trigger boost
        private const val CONFLICT_SIMILARITY   = 0.92f   // cosine similarity threshold for conflict detection
        private val LN2                         = Math.log(2.0).toFloat()
        /** Max SharedPreferences key count before pruning fires. */
        private const val PREFS_KEY_CAP         = 1_000
        /** When pruning, remove keys until count is below this watermark. */
        private const val PREFS_KEY_WATERMARK   = 700
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Apply salience scoring to a list of cosine-ranked messages.
     *
     * Input: pairs of (ChatMessage, rawCosineScore) sorted by descending cosine.
     * Output: same pairs re-ranked by blended salience score.
     *
     * Call site: [EmbeddingService.topKSimilar] uses the result to re-rank
     * before returning to [RagRetriever] / [ChatViewModel].
     *
     * @param rawResults     Pairs of (ChatMessage, cosineScore 0–1)
     * @param halfLifeHours  Temporal decay half-life. Default 24h.
     * @return               Re-ranked list with updated salience scores.
     */
    suspend fun applySalienceScoring(
        rawResults:    List<Pair<ChatMessage, Float>>,
        halfLifeHours: Float = DEFAULT_HALF_LIFE_H
    ): List<Pair<ChatMessage, Float>> = withContext(Dispatchers.Default) {
        pruneOldEntriesIfNeeded()
        val nowMs  = System.currentTimeMillis()
        val lambda = LN2 / halfLifeHours.coerceAtLeast(0.1f)

        val scored = rawResults.map { (msg, cosine) ->
            val ageHours    = ((nowMs - msg.timestamp) / 3_600_000f).coerceAtLeast(0f)
            val decayFactor = Math.exp((-lambda * ageHours).toDouble()).toFloat()

            // Execution outcome weighting
            val outcomeFactor = when (prefs.getString("outcome_${msg.id}", null)) {
                "success" -> OUTCOME_BOOST_SUCCESS
                "failure" -> OUTCOME_PENALTY_FAIL
                else      -> 1.0f
            }

            // Preference persistence (recall frequency)
            val recallCount   = prefs.getInt("recall_${msg.id}", 0)
            val recallFactor  = if (recallCount >= RECALL_BOOST_THRESHOLD) RECALL_BOOST else 1.0f

            // Blended salience: 55% semantic, 30% recency, 15% outcome/recall
            val salience = (0.55f * cosine + 0.30f * decayFactor) * outcomeFactor * recallFactor
            Pair(msg, salience.coerceIn(0f, 1.5f))
        }

        // Detect and resolve conflicts
        val resolved = resolveConflicts(scored)

        // Sort by descending salience, update recall counts
        val reranked = resolved.sortedByDescending { it.second }
        reranked.forEach { (msg, _) ->
            val count = prefs.getInt("recall_${msg.id}", 0)
            prefs.edit().putInt("recall_${msg.id}", count + 1).apply()
        }

        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "SALIENCE_SCORED total=${rawResults.size} resolved=${resolved.size} " +
            "topScore=${"%.3f".format(reranked.firstOrNull()?.second ?: 0f)}")

        reranked
    }

    /**
     * Record that a memory (message) was part of a successful or failed
     * execution path. Called by orchestration layer after plan completion.
     */
    fun recordOutcome(messageIds: List<Long>, success: Boolean) {
        val value = if (success) "success" else "failure"
        val editor = prefs.edit()
        messageIds.forEach { id -> editor.putString("outcome_$id", value) }
        editor.apply()
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "OUTCOME_RECORDED count=${messageIds.size} result=$value")
    }

    /**
     * Clear recall counts and outcome weights for a session (privacy / fresh start).
     * Does NOT clear cross-session outcome weights (those are intentional learning).
     */
    fun clearSessionRecall(messageIds: List<Long>) {
        val editor = prefs.edit()
        messageIds.forEach { id -> editor.remove("recall_$id") }
        editor.apply()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Prune accumulated SharedPreferences keys to prevent unbounded growth.
     *
     * Root cause: every unique message ID that passes through [applySalienceScoring]
     * or [recordOutcome] writes a `recall_{id}` or `outcome_{id}` key. With heavy use
     * over many sessions, this accumulates thousands of keys which inflates the prefs
     * XML file size and slows reads/writes.
     *
     * Strategy: when key count exceeds [PREFS_KEY_CAP], remove the oldest half of
     * `recall_*` keys first (least recently re-ranked messages are safest to evict),
     * then oldest `outcome_*` keys. We stop when count ≤ [PREFS_KEY_WATERMARK].
     *
     * Called from [applySalienceScoring] — already running on Dispatchers.Default,
     * so the file I/O from prefs.all is off the main thread.
     */
    @Suppress("UNCHECKED_CAST")
    private fun pruneOldEntriesIfNeeded() {
        val allKeys = prefs.all.keys
        if (allKeys.size <= PREFS_KEY_CAP) return

        val recallKeys  = allKeys.filter { it.startsWith("recall_") }
        val outcomeKeys = allKeys.filter { it.startsWith("outcome_") }

        val toRemove = mutableListOf<String>()

        // Evict recall keys first — they are the most frequent writers.
        // Stable sort: extract numeric id, remove lowest ids (oldest messages) first.
        val sortedRecall = recallKeys.sortedBy { it.removePrefix("recall_").toLongOrNull() ?: Long.MAX_VALUE }
        for (key in sortedRecall) {
            if ((allKeys.size - toRemove.size) <= PREFS_KEY_WATERMARK) break
            toRemove += key
        }

        // If recall eviction alone isn't enough, evict outcome keys next.
        if ((allKeys.size - toRemove.size) > PREFS_KEY_WATERMARK) {
            val sortedOutcome = outcomeKeys.sortedBy { it.removePrefix("outcome_").toLongOrNull() ?: Long.MAX_VALUE }
            for (key in sortedOutcome) {
                if ((allKeys.size - toRemove.size) <= PREFS_KEY_WATERMARK) break
                toRemove += key
            }
        }

        if (toRemove.isNotEmpty()) {
            val editor = prefs.edit()
            toRemove.forEach { editor.remove(it) }
            editor.apply()
            Log.i(TAG, "PREFS_PRUNED removed=${toRemove.size} remaining=${allKeys.size - toRemove.size}")
        }
    }

    /**
     * Detect conflicting memories: same role, very high cosine similarity
     * (≥ CONFLICT_SIMILARITY), but different timestamps. Keeps the more
     * recent one and emits a MEMORY_CONFLICT log.
     *
     * Heuristic: if two messages would score within 5% of each other AND have
     * cosine_sim ≥ threshold, the older one is likely stale. We remove the
     * older one from the result set.
     */
    private fun resolveConflicts(
        scored: List<Pair<ChatMessage, Float>>
    ): List<Pair<ChatMessage, Float>> {
        if (scored.size < 2) return scored

        val result  = scored.toMutableList()
        val removed = mutableSetOf<Long>()

        for (i in result.indices) {
            if (result[i].first.id in removed) continue
            for (j in i + 1 until result.size) {
                if (result[j].first.id in removed) continue
                val a = result[i].first
                val b = result[j].first
                if (a.role != b.role) continue

                // Content similarity heuristic: shared token overlap > 80%
                val overlap = tokenOverlap(a.content, b.content)
                if (overlap < CONFLICT_SIMILARITY) continue

                // Conflict: keep newer, discard older
                val discard = if (a.timestamp >= b.timestamp) b else a
                removed    += discard.id
                Log.w(TAG, "MEMORY_CONFLICT detected: keeping msg=${if (discard == a) b.id else a.id} " +
                    "discarding msg=${discard.id} overlap=${"%.2f".format(overlap)}")
            }
        }

        return result.filter { it.first.id !in removed }
    }

    private fun tokenOverlap(a: String, b: String): Float {
        val tokensA = MemoryTextNormalizer.tokens(a)
        val tokensB = MemoryTextNormalizer.tokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f
        val intersection = tokensA.intersect(tokensB).size.toFloat()
        val union        = tokensA.union(tokensB).size.toFloat()
        return if (union == 0f) 0f else intersection / union
    }
}
