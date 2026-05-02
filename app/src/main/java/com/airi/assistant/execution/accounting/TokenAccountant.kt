package com.airi.assistant.execution.accounting

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.airi.assistant.execution.CloudProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accurate, per-provider daily token accounting system.
 *
 * Tracks prompt tokens and completion tokens separately, per provider,
 * per calendar day. Resets automatically at midnight UTC.
 *
 * ## Data source
 * Counts come from the `usage` field returned in cloud API responses
 * (the final SSE chunk for streaming endpoints). These are exact values
 * reported by the provider — not estimates.
 *
 * ## Persistence
 * Backed by a plain [SharedPreferences] file. Not encrypted (usage stats
 * are not sensitive). Writes use `apply()` (async) to avoid blocking.
 *
 * ## Thread safety
 * All mutating operations are guarded by a [Mutex] so concurrent calls
 * from multiple coroutines accumulate correctly.
 *
 * ## Observable
 * The live stats are exposed as a [StateFlow] for UI consumption.
 */
class TokenAccountant(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    private val _stats = MutableStateFlow(loadAll())
    val stats: StateFlow<Map<CloudProvider, ProviderStats>> = _stats.asStateFlow()

    // ── Per-provider stats ────────────────────────────────────────────────────

    data class ProviderStats(
        val promptTokens:     Long = 0L,
        val completionTokens: Long = 0L,
        val requestCount:     Int  = 0,
        val failureCount:     Int  = 0,
        val totalLatencyMs:   Long = 0L
    ) {
        val totalTokens:   Long  get() = promptTokens + completionTokens
        val avgLatencyMs:  Long  get() = if (requestCount > 0) totalLatencyMs / requestCount else 0L

        /**
         * Rough cost estimate in USD, based on approximate public pricing
         * (per-million tokens as of Q1 2025). Not suitable for billing —
         * for display / awareness only.
         */
        fun estimatedCostUsd(provider: CloudProvider): Double {
            val (inputMPT, outputMPT) = providerCostPerMillion(provider)
            return (promptTokens * inputMPT + completionTokens * outputMPT) / 1_000_000.0
        }

        private fun providerCostPerMillion(provider: CloudProvider): Pair<Double, Double> = when (provider) {
            CloudProvider.OPENAI     -> Pair(0.15, 0.60)    // gpt-4o-mini approximate
            CloudProvider.GEMINI     -> Pair(0.07, 0.21)    // gemini-1.5-flash approximate
            CloudProvider.ANTHROPIC  -> Pair(0.25, 1.25)    // claude-haiku approximate
            CloudProvider.OPENROUTER -> Pair(0.10, 0.40)    // average across models
            CloudProvider.KIMI       -> Pair(0.12, 0.50)    // moonshot-v1-8k approximate
            CloudProvider.CUSTOM     -> Pair(0.0,  0.0)     // unknown
        }
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /**
     * Record a completed cloud generation turn.
     *
     * @param provider         The cloud provider that served the request.
     * @param promptTokens     Exact prompt token count from the API response.
     * @param completionTokens Exact completion token count from the API response.
     * @param latencyMs        Wall-clock duration of the streaming call.
     */
    suspend fun recordSuccess(
        provider:         CloudProvider,
        promptTokens:     Int,
        completionTokens: Int,
        latencyMs:        Long
    ) = mutex.withLock {
        val current = currentDayStats(provider)
        val updated = current.copy(
            promptTokens     = current.promptTokens     + promptTokens,
            completionTokens = current.completionTokens + completionTokens,
            requestCount     = current.requestCount     + 1,
            totalLatencyMs   = current.totalLatencyMs   + latencyMs
        )
        persist(provider, updated)
        _stats.value = loadAll()
        Log.d(TAG, "${provider.name} +${promptTokens}p +${completionTokens}c total=${updated.totalTokens}")
    }

    /**
     * Record a failed cloud request (no tokens consumed, but failure tracked).
     */
    suspend fun recordFailure(provider: CloudProvider) = mutex.withLock {
        val current = currentDayStats(provider)
        val updated = current.copy(failureCount = current.failureCount + 1)
        persist(provider, updated)
        _stats.value = loadAll()
    }

    /** Reset all providers' stats for the current day. */
    suspend fun resetToday() = mutex.withLock {
        val dayKey = todayKey()
        val editor = prefs.edit()
        CloudProvider.entries.forEach { provider ->
            val base = "${provider.name}_$dayKey"
            editor.remove("${base}_prompt")
                  .remove("${base}_completion")
                  .remove("${base}_requests")
                  .remove("${base}_failures")
                  .remove("${base}_latency")
        }
        editor.apply()
        _stats.value = loadAll()
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Today's stats for a single provider. Synchronous — no Mutex needed for reads. */
    fun todayStats(provider: CloudProvider): ProviderStats = currentDayStats(provider)

    /** Total tokens across ALL providers today. */
    fun totalTokensToday(): Long = _stats.value.values.sumOf { it.totalTokens }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun currentDayStats(provider: CloudProvider): ProviderStats {
        val dayKey = todayKey()
        val base   = "${provider.name}_$dayKey"
        return ProviderStats(
            promptTokens     = prefs.getLong("${base}_prompt",     0L),
            completionTokens = prefs.getLong("${base}_completion", 0L),
            requestCount     = prefs.getInt ("${base}_requests",   0),
            failureCount     = prefs.getInt ("${base}_failures",   0),
            totalLatencyMs   = prefs.getLong("${base}_latency",    0L)
        )
    }

    private fun persist(provider: CloudProvider, stats: ProviderStats) {
        val dayKey = todayKey()
        val base   = "${provider.name}_$dayKey"
        prefs.edit()
            .putLong("${base}_prompt",     stats.promptTokens)
            .putLong("${base}_completion", stats.completionTokens)
            .putInt ("${base}_requests",   stats.requestCount)
            .putInt ("${base}_failures",   stats.failureCount)
            .putLong("${base}_latency",    stats.totalLatencyMs)
            .apply()
    }

    private fun loadAll(): Map<CloudProvider, ProviderStats> =
        CloudProvider.entries.associateWith { currentDayStats(it) }

    /** UTC day key as "YYYY-MM-DD" string. Rolls over at midnight UTC. */
    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    companion object {
        private const val TAG       = "AIRI_TokenAccountant"
        private const val PREFS_FILE = "airi_token_accounting"
    }
}
