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
 * Accurate token accounting system with per-provider daily stats and provider/model historical totals.
 *
 * Tracks prompt tokens and completion tokens separately, per provider,
 * per calendar day. Resets automatically at midnight UTC.
 *
 * ## Data source (Cloud)
 * Counts come from the `usage` field returned in cloud API responses
 * (the final SSE chunk for streaming endpoints). These are exact values
 * reported by the provider — not estimates.
 *
 * ## Data source (Local)
 * Token counts for on-device llama.cpp inference are estimated from the
 * generated text length (chars ÷ [LOCAL_CHARS_PER_TOKEN]). The actual
 * native nativeTokenCount from LlamaManager is passed via [recordLocal].
 * Cost is always 0 for local inference.
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
 * The live stats are exposed as [StateFlow]s for UI consumption.
 */
class TokenAccountant(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val mutex = Mutex()

    private val _stats = MutableStateFlow(loadAll())
    val stats: StateFlow<Map<CloudProvider, ProviderStats>> = _stats.asStateFlow()

    private val _historicalStats = MutableStateFlow(loadHistoricalStats())
    /** All-time totals keyed by a stable `provider:model` identity. */
    val historicalStats: StateFlow<Map<String, HistoricalStats>> = _historicalStats.asStateFlow()

    private val _localStats = MutableStateFlow(loadLocalStats())
    val localStats: StateFlow<LocalStats> = _localStats.asStateFlow()

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
            CloudProvider.BRAVE      -> Pair(0.0,  0.0)     // search API, not token-priced
        }
    }

    data class HistoricalStats(
        val providerId: String,
        val modelId: String,
        val promptTokens: Long = 0L,
        val completionTokens: Long = 0L,
        val requestCount: Long = 0L,
        val totalLatencyMs: Long = 0L,
    ) {
        val totalTokens: Long get() = promptTokens + completionTokens
        val avgLatencyMs: Long get() = if (requestCount > 0) totalLatencyMs / requestCount else 0L
    }

    // ── Local (on-device) stats ───────────────────────────────────────────────

    data class LocalStats(
        val tokensGenerated: Long = 0L,
        val requestCount:    Int  = 0,
        val totalLatencyMs:  Long = 0L
    ) {
        val avgLatencyMs: Long get() = if (requestCount > 0) totalLatencyMs / requestCount else 0L
        val avgTps:       Float get() = if (totalLatencyMs > 0) tokensGenerated * 1000f / totalLatencyMs else 0f
        val estimatedCostUsd: Double get() = 0.0   // on-device inference has no API cost
    }

    // ── Mutations: Cloud ──────────────────────────────────────────────────────

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
        latencyMs:        Long,
        modelId:          String = "unknown"
    ) = mutex.withLock {
        val current = currentDayStats(provider)
        val updated = current.copy(
            promptTokens     = current.promptTokens     + promptTokens,
            completionTokens = current.completionTokens + completionTokens,
            requestCount     = current.requestCount     + 1,
            totalLatencyMs   = current.totalLatencyMs   + latencyMs
        )
        persist(provider, updated)
        val historical = currentHistoricalStats(provider, modelId).let {
            it.copy(
                promptTokens = it.promptTokens + promptTokens,
                completionTokens = it.completionTokens + completionTokens,
                requestCount = it.requestCount + 1L,
                totalLatencyMs = it.totalLatencyMs + latencyMs,
            )
        }
        persistHistorical(historical)
        _stats.value = loadAll()
        _historicalStats.value = loadHistoricalStats()
        Log.d(TAG, "${provider.name}/$modelId +${promptTokens}p +${completionTokens}c total=${updated.totalTokens}")
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

    // ── Mutations: Local (on-device llama.cpp) ────────────────────────────────

    /**
     * Record a completed local inference turn.
     *
     * @param tokensGenerated Native token count from [LlamaManager.generateStream]
     *                        (nativeTokenCount at generation end). If 0 or unknown,
     *                        pass the output character count and the system will
     *                        apply [LOCAL_CHARS_PER_TOKEN] estimation.
     * @param latencyMs       Wall-clock duration from first token request to
     *                        [onComplete] callback.
     * @param isExactCount    True when [tokensGenerated] is the native nativeTokenCount;
     *                        false when it's a character-length estimate.
     */
    suspend fun recordLocal(
        tokensGenerated: Int,
        latencyMs:       Long,
        isExactCount:    Boolean = false
    ) = mutex.withLock {
        val actualTokens = if (isExactCount) tokensGenerated.toLong()
                           else (tokensGenerated / LOCAL_CHARS_PER_TOKEN).toLong().coerceAtLeast(1L)
        val current = loadLocalStats()
        val updated = current.copy(
            tokensGenerated = current.tokensGenerated + actualTokens,
            requestCount    = current.requestCount    + 1,
            totalLatencyMs  = current.totalLatencyMs  + latencyMs
        )
        persistLocal(updated)
        _localStats.value = updated
        Log.d(TAG, "LOCAL +${actualTokens}tok latency=${latencyMs}ms" +
            " (${if (isExactCount) "exact" else "estimated"})")
    }

    /** Reset all providers' stats for the current day (cloud + local). */
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
        val localBase = "local_$dayKey"
        editor.remove("${localBase}_tokens")
              .remove("${localBase}_requests")
              .remove("${localBase}_latency")
        editor.apply()
        _stats.value      = loadAll()
        _localStats.value = LocalStats()
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun todayStats(provider: CloudProvider): ProviderStats = currentDayStats(provider)

    fun totalTokensToday(): Long =
        _stats.value.values.sumOf { it.totalTokens } + _localStats.value.tokensGenerated

    fun totalCloudTokensToday(): Long = _stats.value.values.sumOf { it.totalTokens }
    fun totalLocalTokensToday(): Long = _localStats.value.tokensGenerated
    fun totalCloudTokensAllTime(): Long = _historicalStats.value.values.sumOf { it.totalTokens }

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

    private fun historicalKey(provider: CloudProvider, modelId: String): String =
        "${provider.name.lowercase()}:${modelId.trim().ifBlank { "unknown" }}"

    private fun currentHistoricalStats(provider: CloudProvider, modelId: String): HistoricalStats {
        val key = historicalKey(provider, modelId)
        val base = "history_$key"
        return HistoricalStats(
            providerId = provider.name.lowercase(),
            modelId = modelId.trim().ifBlank { "unknown" },
            promptTokens = prefs.getLong("${base}_prompt", 0L),
            completionTokens = prefs.getLong("${base}_completion", 0L),
            requestCount = prefs.getLong("${base}_requests", 0L),
            totalLatencyMs = prefs.getLong("${base}_latency", 0L),
        )
    }

    private fun persistHistorical(stats: HistoricalStats) {
        val base = "history_${stats.providerId}:${stats.modelId}"
        prefs.edit()
            .putLong("${base}_prompt", stats.promptTokens)
            .putLong("${base}_completion", stats.completionTokens)
            .putLong("${base}_requests", stats.requestCount)
            .putLong("${base}_latency", stats.totalLatencyMs)
            .apply()
    }

    private fun loadHistoricalStats(): Map<String, HistoricalStats> =
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith("history_") && it.endsWith("_prompt") }
            .map { it.removePrefix("history_").removeSuffix("_prompt") }
            .mapNotNull { identity ->
                val separator = identity.indexOf(':')
                if (separator <= 0 || separator == identity.lastIndex) return@mapNotNull null
                val provider = runCatching { CloudProvider.valueOf(identity.substring(0, separator).uppercase()) }
                    .getOrNull() ?: return@mapNotNull null
                val model = identity.substring(separator + 1)
                historicalKey(provider, model) to currentHistoricalStats(provider, model)
            }
            .toMap()

    private fun loadAll(): Map<CloudProvider, ProviderStats> =
        CloudProvider.entries.associateWith { currentDayStats(it) }

    private fun loadLocalStats(): LocalStats {
        val base = "local_${todayKey()}"
        return LocalStats(
            tokensGenerated = prefs.getLong("${base}_tokens",   0L),
            requestCount    = prefs.getInt ("${base}_requests", 0),
            totalLatencyMs  = prefs.getLong("${base}_latency",  0L)
        )
    }

    private fun persistLocal(stats: LocalStats) {
        val base = "local_${todayKey()}"
        prefs.edit()
            .putLong("${base}_tokens",   stats.tokensGenerated)
            .putInt ("${base}_requests", stats.requestCount)
            .putLong("${base}_latency",  stats.totalLatencyMs)
            .apply()
    }

    private fun todayKey(): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    companion object {
        private const val TAG               = "AIRI_TokenAccountant"
        private const val PREFS_FILE        = "airi_token_accounting"
        /**
         * Chars-per-token ratio used when the native token count is unavailable.
         * English text ≈ 4 chars/token; Arabic/CJK ≈ 1.5 chars/token.
         * 3.5 is a conservative cross-language midpoint.
         */
        const val LOCAL_CHARS_PER_TOKEN = 3.5f
    }
}
