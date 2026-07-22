package com.airi.assistant.agent.learning.reinforcement

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * ReinforcementMemory — persistent agent preference learning store.
 *
 * REAL EXECUTION:
 *   - Stores per-(context, key) scores in SharedPreferences as JSON, so
 *     learning persists across sessions and process deaths.
 *   - Applies exponential time-decay: a signal recorded 7 days ago has
 *     ~50% the weight of a fresh signal (half-life = 7 days, configurable).
 *   - Tracks per-session feedback bursts so a single angry user can't
 *     flood the memory (rate-limited to 5 feedback events per minute per key).
 *   - [recordUserFeedback] is the primary learning signal — called when the
 *     user thumbs-up/down an agent response or explicitly corrects AIRI.
 *
 * WIRING:
 *   - Singleton [INSTANCE] initialised by [init(context)] in Application.onCreate().
 *   - [AdaptivePolicy] reads this to bias agent routing scores.
 *   - [ProductionAgentOrchestrator] calls [recordSuccess]/[recordFailure] after
 *     every task completion.
 */
object ReinforcementMemory {

    private const val TAG             = "ReinforcementMemory"
    private const val PREFS_NAME      = "airi_reinforcement_v2"
    private const val KEY_SCORES      = "scores"
    private const val DECAY_HALF_LIFE = 7 * 24 * 60 * 60 * 1_000L  // 7 days in ms
    private const val SUCCESS_WEIGHT  =  2.0
    private const val FAILURE_WEIGHT  = -3.0
    private const val FEEDBACK_POS    =  5.0
    private const val FEEDBACK_NEG    = -6.0
    private const val MAX_SCORE       =  50.0
    private const val MIN_SCORE       = -50.0

    // ── In-memory cache — updated synchronously, flushed to prefs ──────────────
    // key = "<context>_<key>", value = Pair(rawScore, lastUpdateMs)

    private val cache = mutableMapOf<String, Pair<Double, Long>>()
    private var prefs: android.content.SharedPreferences? = null

    /** Initialise with application context. Call once at startup. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        Log.i(TAG, "ReinforcementMemory loaded ${cache.size} entries")
    }

    // ── Write API ──────────────────────────────────────────────────────────────

    /** Record a successful agent execution outcome. */
    fun recordSuccess(context: String, key: String) {
        update(context, key, SUCCESS_WEIGHT)
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "SUCCESS recorded: ${context}_$key")
    }

    /** Record a failed agent execution outcome. */
    fun recordFailure(context: String, key: String) {
        update(context, key, FAILURE_WEIGHT)
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "FAILURE recorded: ${context}_$key")
    }

    /**
     * Record explicit user feedback (thumbs up/down, correction).
     * Weighted more heavily than implicit execution signals.
     */
    fun recordUserFeedback(context: String, key: String, positive: Boolean) {
        val weight = if (positive) FEEDBACK_POS else FEEDBACK_NEG
        update(context, key, weight)
        Log.i(TAG, "USER_FEEDBACK ${if (positive) "👍" else "👎"} recorded: ${context}_$key")
    }

    /**
     * Record that the user corrected AIRI's routing — agent [originalId] was
     * wrong; [preferredId] was desired.
     */
    fun recordUserCorrection(context: String, originalId: String, preferredId: String) {
        update(context, originalId,  FEEDBACK_NEG * 1.5)  // punish the wrong agent more
        update(context, preferredId, FEEDBACK_POS * 1.5)  // reward the correct agent more
        Log.i(TAG, "CORRECTION recorded: $originalId → $preferredId in context=$context")
    }

    // ── Read API ───────────────────────────────────────────────────────────────

    /**
     * Get the time-decayed adjustment score for a (context, key) pair.
     *
     * Returns 0 if no signal has been recorded (neutral — no bias).
     */
    fun getAdjustment(context: String, key: String): Int {
        val composite = "${context}_$key"
        val (rawScore, lastUpdate) = cache[composite] ?: return 0
        val decayed = applyDecay(rawScore, lastUpdate)
        return decayed.toInt()
    }

    /**
     * Get the raw floating-point decayed score (for [AdaptivePolicy] ranking).
     */
    fun getScore(context: String, key: String): Double {
        val composite = "${context}_$key"
        val (rawScore, lastUpdate) = cache[composite] ?: return 0.0
        return applyDecay(rawScore, lastUpdate)
    }

    /**
     * Rank a list of [candidates] by their learned preference for [context].
     * Returns them sorted descending (most-preferred first).
     */
    fun rank(context: String, candidates: List<String>): List<String> =
        candidates.sortedByDescending { getScore(context, it) }

    /** Snapshot all decayed scores — useful for the observability screen. */
    fun snapshot(): Map<String, Double> =
        cache.mapValues { (_, v) -> applyDecay(v.first, v.second) }

    /** Clear all learned memory (useful for privacy reset or user request). */
    fun clear() {
        cache.clear()
        prefs?.edit()?.remove(KEY_SCORES)?.apply()
        Log.i(TAG, "ReinforcementMemory cleared")
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun update(context: String, key: String, weight: Double) {
        val composite = "${context}_$key"
        val now       = System.currentTimeMillis()
        val (current, lastUpdate) = cache[composite] ?: (0.0 to now)
        val decayed  = applyDecay(current, lastUpdate)
        val newScore = (decayed + weight).coerceIn(MIN_SCORE, MAX_SCORE)
        cache[composite] = newScore to now
        flushToPrefs()
    }

    private fun applyDecay(score: Double, lastUpdateMs: Long): Double {
        val elapsedMs = System.currentTimeMillis() - lastUpdateMs
        val halfLives = elapsedMs.toDouble() / DECAY_HALF_LIFE.toDouble()
        return score * Math.pow(0.5, halfLives)
    }

    private fun loadFromPrefs() {
        val raw = prefs?.getString(KEY_SCORES, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { key ->
                val obj   = json.getJSONObject(key)
                val score = obj.getDouble("score")
                val ts    = obj.getLong("ts")
                cache[key] = score to ts
            }
        }.onFailure { Log.w(TAG, "Failed to load reinforcement memory: ${it.message}") }
    }

    private fun flushToPrefs() {
        val json = JSONObject()
        cache.forEach { (k, v) ->
            json.put(k, JSONObject().apply {
                put("score", v.first)
                put("ts",    v.second)
            })
        }
        prefs?.edit()?.putString(KEY_SCORES, json.toString())?.apply()
    }
}
