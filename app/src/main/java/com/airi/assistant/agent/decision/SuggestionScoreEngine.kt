package com.airi.assistant.agent.decision

import android.util.Log
import com.airi.assistant.memory.AiriDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * SuggestionScoreEngine — computes display scores for suggestion candidates.
 *
 * ── ANR FIX ──────────────────────────────────────────────────────────────────
 * The previous implementation called `runBlocking` inside [calculate], which
 * blocks the calling thread until the Room query completes. If the caller is
 * the main thread or an accessibility callback thread, this causes an ANR.
 *
 * Fix: scores are computed from an in-memory [scoreCache] populated
 * asynchronously. [calculate] is non-blocking and returns a cached score (or a
 * conservative default if the cache is cold). A background refresh runs after
 * each [initialize] and after each cache miss so the cache stays warm.
 */
object SuggestionScoreEngine {

    private const val TAG          = "SuggestionScoreEngine"
    private const val DEFAULT_COLD = 60  // returned when cache is cold — assume displayable
    private const val DEFAULT_NONE = 50  // returned when no DB is wired

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var db: AiriDatabase? = null

    /** key = "$app|$intent" → score 0–100 */
    private val scoreCache = ConcurrentHashMap<String, Int>()

    fun initialize(database: AiriDatabase) {
        db = database
        warmCache()
    }

    /**
     * Return the score for [app]+[intent] — NON-BLOCKING.
     *
     * If the cache contains an entry, it is returned immediately.
     * On a cache miss a background refresh is scheduled so the next call hits.
     * Callers that need an accurate score in a coroutine context should use
     * [calculateSuspend] instead.
     */
    fun calculate(app: String, intent: String): Int {
        if (db == null) return DEFAULT_NONE
        val key = "$app|$intent"
        val cached = scoreCache[key]
        if (cached != null) return cached
        // Cache miss — schedule async population and return conservative default
        scheduleFetch(key)
        return DEFAULT_COLD
    }

    /** Suspend version — accurate, used from coroutine contexts. */
    suspend fun calculateSuspend(app: String, intent: String): Int {
        val database = db ?: return DEFAULT_NONE
        val key = "$app|$intent"
        return scoreCache.getOrElse(key) {
            try {
                val stats = database.behaviorStatsDao().get(key) ?: return DEFAULT_COLD
                computeScore(stats.acceptedCount, stats.shownCount, stats.dismissedCount)
                    .also { scoreCache[key] = it }
            } catch (e: Exception) {
                Log.e(TAG, "calculateSuspend failed: ${e.message}")
                DEFAULT_COLD
            }
        }
    }

    private fun scheduleFetch(key: String) {
        val database = db ?: return
        scope.launch {
            try {
                val stats = database.behaviorStatsDao().get(key) ?: return@launch
                scoreCache[key] = computeScore(stats.acceptedCount, stats.shownCount, stats.dismissedCount)
            } catch (e: Exception) {
                Log.e(TAG, "scheduleFetch failed for key=$key: ${e.message}")
            }
        }
    }

    private fun warmCache() {
        val database = db ?: return
        scope.launch {
            try {
                // getAllBehaviorStats() is on memoryDao(), not behaviorStatsDao()
                // (behaviorStatsDao only exposes single-key get/upsert)
                val all = database.memoryDao().getAllBehaviorStats()
                for (stats in all) {
                    scoreCache[stats.key] = computeScore(
                        stats.acceptedCount, stats.shownCount, stats.dismissedCount
                    )
                }
                Log.d(TAG, "score cache warmed: ${scoreCache.size} entries")
            } catch (e: Exception) {
                Log.e(TAG, "warmCache failed: ${e.message}")
            }
        }
    }

    private fun computeScore(accepted: Int, shown: Int, dismissed: Int): Int {
        val acceptanceRate = if (shown == 0) 0.5 else accepted.toDouble() / shown
        val penalty        = if (dismissed > accepted) 10 else 0
        return ((acceptanceRate * 100).toInt() - penalty).coerceIn(0, 100)
    }
}
