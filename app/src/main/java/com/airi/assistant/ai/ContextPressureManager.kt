package com.airi.assistant.ai

import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ContextPressureManager — tracks the LLM context window token budget and
 * emits actionable signals when the budget is under pressure.
 *
 * ── MOTIVATION ───────────────────────────────────────────────────────────
 *
 *   llama.cpp models have a hard context limit (commonly 4096, 8192, 32768
 *   tokens). When a conversation approaches that limit:
 *     - The model starts silently truncating the oldest turns.
 *     - Inference quality degrades noticeably.
 *     - The JNI call may fail with a cryptic error.
 *
 *   ContextPressureManager intercepts this before it happens.
 *
 * ── SIGNALS ──────────────────────────────────────────────────────────────
 *
 *   NOMINAL    — < [WARN_THRESHOLD_PCT]% of budget used
 *   WARNING    — [WARN_THRESHOLD_PCT] – [CRITICAL_THRESHOLD_PCT]% used
 *   CRITICAL   — [CRITICAL_THRESHOLD_PCT] – 100% used
 *   OVERFLOW   — > 100% (model will truncate; summarization is urgent)
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────
 *
 *   ChatViewModel / UnifiedCognitiveLoop should:
 *     1. Call [addTokens] after each LLM turn with the turn's token count.
 *     2. Observe [pressure] StateFlow.
 *     3. On WARNING:  show a soft UI indicator.
 *     4. On CRITICAL: trigger conversation summarization before the next turn.
 *     5. On OVERFLOW: truncate oldest turns and reset, or start a new context.
 *
 * ── TOKEN ESTIMATION ─────────────────────────────────────────────────────
 *
 *   [estimateTokens] uses a simple heuristic (chars / 4) since tiktoken is
 *   not available on Android. For llama.cpp models this is accurate to ±15%.
 */
class ContextPressureManager(
    val contextWindowSize: Int = DEFAULT_CONTEXT_WINDOW
) {

    private val TAG = "ContextPressureManager"

    // ── State ─────────────────────────────────────────────────────────────────

    private val _pressure = MutableStateFlow(PressureReport())
    val pressure: StateFlow<PressureReport> = _pressure.asStateFlow()

    @Volatile private var usedTokens = 0
    @Volatile private var turnCount  = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record that [tokens] tokens have been consumed in the current context.
     *
     * @param tokens   Exact token count (from llama.cpp) or estimate.
     * @param isExact  True if [tokens] came directly from llama.cpp output.
     */
    fun addTokens(tokens: Int, isExact: Boolean = false) {
        usedTokens += tokens
        turnCount++
        val report = buildReport()
        _pressure.value = report
        if (report.level == PressureLevel.CRITICAL || report.level == PressureLevel.OVERFLOW) {
            LoggingService.warn(TAG, "AIRI_PROOF CONTEXT_PRESSURE level=${report.level} used=${report.usedTokens} max=${report.maxTokens} pct=${report.usedPercent}%")
        } else {
            Log.d(TAG, "AIRI_PROOF CONTEXT_PRESSURE level=${report.level} used=${report.usedTokens}/$contextWindowSize")
        }
    }

    /**
     * Estimate the token count for [text] using a char/4 heuristic.
     * Useful for pre-flight budget checks before an LLM call.
     */
    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /**
     * Returns true if adding [estimatedTokens] would push the context into
     * WARNING territory. Use this to decide whether to summarize first.
     */
    fun wouldPressure(estimatedTokens: Int): Boolean =
        (usedTokens + estimatedTokens) * 100 / contextWindowSize >= WARN_THRESHOLD_PCT

    /**
     * Reset the token counter — call this when starting a new conversation
     * or after successful summarization / context truncation.
     */
    fun reset() {
        usedTokens = 0
        turnCount  = 0
        _pressure.value = buildReport()
        Log.i(TAG, "AIRI_PROOF CONTEXT_PRESSURE RESET contextWindow=$contextWindowSize")
    }

    /**
     * Manually set the used token count (e.g. after injecting a summary).
     */
    fun setUsed(tokens: Int) {
        usedTokens = tokens.coerceAtLeast(0)
        _pressure.value = buildReport()
    }

    /** Current token usage snapshot. */
    fun snapshot(): PressureReport = _pressure.value

    /** Available headroom in tokens. */
    val remaining: Int get() = (contextWindowSize - usedTokens).coerceAtLeast(0)

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildReport(): PressureReport {
        val pct   = if (contextWindowSize > 0) usedTokens * 100 / contextWindowSize else 0
        val level = when {
            pct >= 100            -> PressureLevel.OVERFLOW
            pct >= CRITICAL_THRESHOLD_PCT -> PressureLevel.CRITICAL
            pct >= WARN_THRESHOLD_PCT     -> PressureLevel.WARNING
            else                          -> PressureLevel.NOMINAL
        }
        return PressureReport(
            usedTokens    = usedTokens,
            maxTokens     = contextWindowSize,
            usedPercent   = pct,
            level         = level,
            turnCount     = turnCount,
            recommendation = when (level) {
                PressureLevel.NOMINAL   -> null
                PressureLevel.WARNING   -> "Consider summarizing the conversation soon."
                PressureLevel.CRITICAL  -> "Summarize the conversation before the next LLM call."
                PressureLevel.OVERFLOW  -> "Context overflow — truncate oldest turns or start a new context immediately."
            }
        )
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    enum class PressureLevel { NOMINAL, WARNING, CRITICAL, OVERFLOW }

    data class PressureReport(
        val usedTokens:     Int           = 0,
        val maxTokens:      Int           = DEFAULT_CONTEXT_WINDOW,
        val usedPercent:    Int           = 0,
        val level:          PressureLevel = PressureLevel.NOMINAL,
        val turnCount:      Int           = 0,
        val recommendation: String?       = null
    ) {
        val isHealthy: Boolean get() = level == PressureLevel.NOMINAL || level == PressureLevel.WARNING
        val remaining: Int     get() = (maxTokens - usedTokens).coerceAtLeast(0)
    }

    companion object {
        private const val WARN_THRESHOLD_PCT     = 70
        private const val CRITICAL_THRESHOLD_PCT = 90
        const val DEFAULT_CONTEXT_WINDOW         = 4096
        const val CONTEXT_8K                     = 8192
        const val CONTEXT_32K                    = 32768
    }
}
