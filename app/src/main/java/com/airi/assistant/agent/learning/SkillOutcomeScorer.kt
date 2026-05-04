package com.airi.assistant.agent.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// SkillOutcomeScorer — Self-Improvement Loop (Layer 7)
//
// After every skill/tool execution the scorer:
//   1. Records the outcome (success/failure/latency/user rating).
//   2. Computes a running EMA score per skill/tool.
//   3. Promotes high-scoring skills (score > PROMOTE_THRESHOLD).
//   4. Flags consistently-failing tools for policy demotion.
//   5. Persists state in SharedPreferences so learning survives restarts.
//
// Score formula:
//   newScore = α × successSignal + (1-α) × oldScore
//   where successSignal = 1.0 if success, -1.0 × penalty if failed
//   and α = EMA_ALPHA (0.25 by default — blends recent with history)
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG              = "SkillOutcomeScorer"
private const val PREFS_NAME       = "airi_skill_scores"
private const val EMA_ALPHA        = 0.25f
private const val INITIAL_SCORE    = 0.5f
private const val PROMOTE_THRESHOLD = 0.75f
private const val DEMOTE_THRESHOLD  = 0.25f
private const val MAX_HISTORY_PER_SKILL = 50

class SkillOutcomeScorer(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory score map: skillName → EMA score [0,1]
    private val scores = ConcurrentHashMap<String, Float>()

    // In-memory history: skillName → list of recent OutcomeRecord
    private val history = ConcurrentHashMap<String, MutableList<OutcomeRecord>>()

    // Tool policy adjustments derived from scores
    private val _toolPolicy = ConcurrentHashMap<String, ToolPolicy>()
    val toolPolicy: Map<String, ToolPolicy> get() = _toolPolicy

    init {
        loadFromPrefs()
    }

    // ── Data types ────────────────────────────────────────────────────────────

    data class OutcomeRecord(
        val skillName:   String,
        val success:     Boolean,
        val latencyMs:   Long,
        val errorReason: String?    = null,
        val userRating:  Float?     = null,  // 0..1 explicit feedback
        val timestampMs: Long       = System.currentTimeMillis()
    )

    enum class ToolPolicy { PREFERRED, NORMAL, AVOID, BLOCKED }

    data class SkillReport(
        val skillName:   String,
        val score:       Float,
        val policy:      ToolPolicy,
        val totalRuns:   Int,
        val successRate: Float,
        val avgLatencyMs: Long,
        val lastError:   String?,
        val suggestion:  String?
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record an outcome for [skillName].
     * Automatically updates the EMA score and derives a new [ToolPolicy].
     *
     * @return The updated [ToolPolicy] for this skill.
     */
    fun record(
        skillName:   String,
        success:     Boolean,
        latencyMs:   Long      = 0L,
        errorReason: String?   = null,
        userRating:  Float?    = null
    ): ToolPolicy {
        val record = OutcomeRecord(skillName, success, latencyMs, errorReason, userRating)

        // Append to in-memory history
        val hist = history.getOrPut(skillName) { mutableListOf() }
        hist.add(record)
        if (hist.size > MAX_HISTORY_PER_SKILL) hist.removeAt(0)

        // Compute success signal (user rating overrides binary success if present)
        val signal = userRating ?: if (success) 1.0f else computeFailurePenalty(latencyMs, errorReason)

        // EMA update
        val old   = scores.getOrDefault(skillName, INITIAL_SCORE)
        val alpha = EMA_ALPHA
        val newScore = (alpha * signal + (1f - alpha) * old).coerceIn(0f, 1f)
        scores[skillName] = newScore

        // Derive policy
        val policy = derivePolicy(newScore)
        _toolPolicy[skillName] = policy

        Log.i(TAG, "AIRI_PROOF SKILL_SCORED skill=$skillName " +
            "success=$success signal=${"%.2f".format(signal)} " +
            "score=${"%.3f".format(newScore)} policy=$policy")

        persistScore(skillName, newScore)
        return policy
    }

    /** Get the current EMA score for a skill (default 0.5 = neutral). */
    fun getScore(skillName: String): Float = scores.getOrDefault(skillName, INITIAL_SCORE)

    /** Get the current policy for a skill. */
    fun getPolicy(skillName: String): ToolPolicy =
        _toolPolicy.getOrDefault(skillName, ToolPolicy.NORMAL)

    /** All skills ordered by score descending. */
    fun rankedSkills(): List<SkillReport> =
        scores.entries.sortedByDescending { it.value }.map { (name, score) ->
            buildReport(name, score)
        }

    /** Produce a full report for one skill. */
    fun report(skillName: String): SkillReport? {
        val score = scores[skillName] ?: return null
        return buildReport(skillName, score)
    }

    /**
     * Generate a natural-language improvement suggestion for a failing skill.
     * Returned string is fed back to the planner for LLM-driven skill patching.
     */
    fun improvementSuggestion(skillName: String): String? {
        val hist = history[skillName] ?: return null
        val recent = hist.takeLast(10)
        val failCount = recent.count { !it.success }
        if (failCount < 3) return null

        val commonErrors = recent
            .filter { !it.success && it.errorReason != null }
            .groupBy { it.errorReason }
            .maxByOrNull { it.value.size }
            ?.key

        return buildString {
            append("Skill '$skillName' failed $failCount/${recent.size} recent runs. ")
            if (commonErrors != null) append("Common error: '$commonErrors'. ")
            append("Suggest: review tool parameters, check permissions, or replace with an alternative.")
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun derivePolicy(score: Float): ToolPolicy = when {
        score >= PROMOTE_THRESHOLD -> ToolPolicy.PREFERRED
        score <= DEMOTE_THRESHOLD  -> ToolPolicy.AVOID
        score <= 0.10f             -> ToolPolicy.BLOCKED
        else                       -> ToolPolicy.NORMAL
    }

    private fun computeFailurePenalty(latencyMs: Long, errorReason: String?): Float {
        var penalty = -0.5f
        if (latencyMs > 10_000L) penalty -= 0.1f   // timeout penalty
        if (errorReason?.contains("permission", ignoreCase = true) == true) penalty -= 0.2f
        if (errorReason?.contains("network", ignoreCase = true) == true) penalty -= 0.1f
        return penalty.coerceAtLeast(-1f)
    }

    private fun buildReport(skillName: String, score: Float): SkillReport {
        val hist       = history[skillName] ?: emptyList()
        val total      = hist.size
        val successes  = hist.count { it.success }
        val successRate = if (total == 0) 0f else successes.toFloat() / total.toFloat()
        val avgLatency = if (total == 0) 0L else hist.map { it.latencyMs }.average().toLong()
        val lastError  = hist.lastOrNull { !it.success }?.errorReason
        return SkillReport(
            skillName   = skillName,
            score       = score,
            policy      = _toolPolicy.getOrDefault(skillName, ToolPolicy.NORMAL),
            totalRuns   = total,
            successRate = successRate,
            avgLatencyMs = avgLatency,
            lastError   = lastError,
            suggestion  = improvementSuggestion(skillName)
        )
    }

    // ── SharedPreferences persistence ─────────────────────────────────────────

    private fun persistScore(skillName: String, score: Float) {
        prefs.edit().putFloat(skillName, score).apply()
    }

    private fun loadFromPrefs() {
        val all = prefs.all
        all.forEach { (k, v) ->
            if (v is Float) {
                scores[k] = v
                _toolPolicy[k] = derivePolicy(v)
            }
        }
        Log.d(TAG, "SkillOutcomeScorer loaded ${scores.size} skill scores from prefs")
    }

    companion object {
        @Volatile private var instance: SkillOutcomeScorer? = null

        fun getInstance(context: Context): SkillOutcomeScorer =
            instance ?: synchronized(this) {
                instance ?: SkillOutcomeScorer(context).also { instance = it }
            }
    }
}
