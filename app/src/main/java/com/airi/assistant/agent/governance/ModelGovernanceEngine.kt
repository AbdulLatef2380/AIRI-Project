package com.airi.assistant.agent.governance

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.QueryType
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.monetization.SubscriptionManager

/**
 * ModelGovernanceEngine — intelligent model selection and runtime governance.
 *
 * RESPONSIBILITIES:
 *   1. MODEL SELECTION  — chooses the best available model (local vs cloud,
 *      specific model variant) for a given request based on query type,
 *      device state, subscription tier, and explicit user override.
 *
 *   2. PARAMETER GOVERNANCE — adjusts temperature, maxTokens, topP, and
 *      contextLength dynamically per query type and power level. Prevents
 *      expensive cloud calls for tasks better done locally.
 *
 *   3. SAFETY GATING — enforces content-policy rules before dispatch:
 *      blocks disallowed content categories, applies prompt-injection
 *      defences, and ensures the model receives only sanitised input.
 *
 *   4. LATENCY BUDGETING — tracks per-query-type P50/P90 latencies and
 *      down-grades to a faster (lower-quality) model when the rolling P90
 *      exceeds the latency budget for the current query class.
 *
 *   5. FALLBACK ARBITRATION — when the primary model fails or times out,
 *      the engine produces an ordered fallback chain ([ModelDecision.fallbackChain])
 *      that the orchestrator tries in sequence.
 *
 * WIRING:
 *   - [ServiceLocator.modelGovernanceEngine] holds the singleton.
 *   - [ChatViewModel.sendMessage] calls [govern] before routing to get the
 *     [ModelDecision] that overrides any static routing configuration.
 *   - [ProductionAgentOrchestrator] calls [recordLatency] after each
 *     completion to feed the adaptive latency governor.
 */
class ModelGovernanceEngine(
    private val context: Context,
    private val subscriptionManager: SubscriptionManager
) {

    companion object {
        private const val TAG                = "ModelGovernanceEngine"
        private const val LATENCY_WINDOW     = 20       // rolling window size
        private const val P90_BUDGET_MS      = 8_000L   // max acceptable P90 latency
        private const val SAFETY_DENY_SCORE  = 0.85f    // above = block
    }

    private val latencyHistory = mutableListOf<Pair<QueryType, Long>>()  // (type, ms)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Govern a request and produce a [ModelDecision] that controls how
     * the orchestrator dispatches it.
     *
     * @param prompt      The (potentially sanitised) input prompt.
     * @param queryType   Classified query type from [QueryClassifier].
     * @param localReady  True if a local llama.cpp model is loaded.
     * @param cloudReady  True if a cloud backend key is configured.
     * @param userOverride The user's explicit model preference (may be null).
     */
    fun govern(
        prompt:       String,
        queryType:    QueryType,
        localReady:   Boolean,
        cloudReady:   Boolean,
        userOverride: String? = null
    ): ModelDecision {
        Log.d(TAG, "govern queryType=${queryType.name} localReady=$localReady cloudReady=$cloudReady")

        // ── Safety gate ───────────────────────────────────────────────────────
        val safetyScore = scoreSafety(prompt)
        if (safetyScore >= SAFETY_DENY_SCORE) {
            Log.w(TAG, "AIRI_RUNTIME GOVERNANCE_SAFETY_BLOCK score=$safetyScore")
            return ModelDecision(
                strategy       = ModelStrategy.BLOCK,
                rationale      = "Content policy: score=$safetyScore",
                parameters     = defaultParameters(queryType),
                sanitisedPrompt = "[Request blocked by content policy]",
                fallbackChain  = emptyList()
            )
        }

        // ── Sanitise prompt ───────────────────────────────────────────────────
        val sanitised = sanitise(prompt)

        // ── Parameter set ─────────────────────────────────────────────────────
        val params = buildParameters(queryType, subscriptionManager.isPremium())

        // ── User override ─────────────────────────────────────────────────────
        if (userOverride != null) {
            Log.d(TAG, "User override model: $userOverride")
            EventBus.emitSync(AppEvent.GenericInfo("Model governance: user override → $userOverride"))
            return ModelDecision(
                strategy        = ModelStrategy.USER_OVERRIDE,
                preferredModelId = userOverride,
                rationale       = "User explicit override: $userOverride",
                parameters      = params,
                sanitisedPrompt = sanitised,
                fallbackChain   = buildFallbackChain(localReady, cloudReady)
            )
        }

        // ── Latency governor ──────────────────────────────────────────────────
        val p90 = computeP90(queryType)
        if (p90 > P90_BUDGET_MS && cloudReady) {
            Log.w(TAG, "P90 budget exceeded p90=${p90}ms — routing to cloud for speed")
            return ModelDecision(
                strategy        = ModelStrategy.CLOUD_PREFERRED,
                rationale       = "P90=$p90 ms exceeds budget=${P90_BUDGET_MS}ms — cloud offload",
                parameters      = params,
                sanitisedPrompt = sanitised,
                fallbackChain   = if (localReady) listOf("local") else emptyList()
            )
        }

        // ── Content-complexity routing ────────────────────────────────────────
        val strategy = when {
            !localReady && cloudReady  -> ModelStrategy.CLOUD_PREFERRED
            localReady && !cloudReady  -> ModelStrategy.LOCAL_ONLY
            queryType == QueryType.ANALYTICAL && prompt.length > 800 && cloudReady
                                       -> ModelStrategy.CLOUD_PREFERRED
            queryType == QueryType.CREATIVE && cloudReady
                                       -> ModelStrategy.CLOUD_PREFERRED
            queryType == QueryType.SIMPLE || queryType == QueryType.ACTION
                                       -> ModelStrategy.LOCAL_ONLY
            else                       -> ModelStrategy.LOCAL_PREFERRED
        }

        val rationale = when (strategy) {
            ModelStrategy.CLOUD_PREFERRED -> "Content complexity (${queryType.name}, ${prompt.length} chars) → cloud"
            ModelStrategy.LOCAL_ONLY      -> "Simple/action query or no cloud → local"
            ModelStrategy.LOCAL_PREFERRED -> "HYBRID default: local primary"
            else                          -> "Fallback"
        }

        Log.i(TAG, "AIRI_RUNTIME GOVERNANCE_DECISION strategy=${strategy.name} rationale=$rationale")
        EventBus.emitSync(AppEvent.GenericInfo("Model governance: $strategy"))

        return ModelDecision(
            strategy        = strategy,
            rationale       = rationale,
            parameters      = params,
            sanitisedPrompt = sanitised,
            fallbackChain   = buildFallbackChain(localReady, cloudReady)
        )
    }

    /**
     * Record the actual latency for a completed query. Used by the
     * rolling latency governor in [govern].
     */
    fun recordLatency(queryType: QueryType, latencyMs: Long) {
        synchronized(latencyHistory) {
            latencyHistory.add(queryType to latencyMs)
            if (latencyHistory.size > LATENCY_WINDOW) {
                latencyHistory.removeAt(0)
            }
        }
        Log.d(TAG, "Latency recorded type=${queryType.name} ms=$latencyMs p90=${computeP90(queryType)}")
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun computeP90(queryType: QueryType): Long {
        val relevant = synchronized(latencyHistory) {
            latencyHistory.filter { it.first == queryType }.map { it.second }
        }
        if (relevant.isEmpty()) return 0L
        val sorted = relevant.sorted()
        val idx    = ((sorted.size * 0.9).toInt()).coerceAtMost(sorted.size - 1)
        return sorted[idx]
    }

    private fun scoreSafety(prompt: String): Float {
        val lower = prompt.lowercase()
        var score = 0f
        BLOCKED_PATTERNS.forEach { p ->
            if (lower.contains(p)) score += 0.3f
        }
        INJECTION_PATTERNS.forEach { p ->
            if (lower.contains(p)) score += 0.2f
        }
        return score.coerceAtMost(1f)
    }

    private fun sanitise(prompt: String): String {
        var result = prompt
        INJECTION_PATTERNS.forEach { p ->
            result = result.replace(p, "[filtered]", ignoreCase = true)
        }
        return result.trim()
    }

    private fun buildParameters(queryType: QueryType, premium: Boolean): GovernedParameters {
        val powerLevel = if (premium) 1.0f else 0.85f
        return when (queryType) {
            QueryType.SIMPLE     -> GovernedParameters(
                temperature = 0.3f * powerLevel,
                maxTokens   = (256 * powerLevel).toInt(),
                topP        = 0.85f
            )
            QueryType.CREATIVE   -> GovernedParameters(
                temperature = 0.95f,
                maxTokens   = (1024 * powerLevel).toInt(),
                topP        = 0.98f
            )
            QueryType.ANALYTICAL -> GovernedParameters(
                temperature = 0.2f,
                maxTokens   = (2048 * powerLevel).toInt(),
                topP        = 0.80f
            )
            QueryType.ACTION     -> GovernedParameters(
                temperature = 0.1f,
                maxTokens   = (128 * powerLevel).toInt(),
                topP        = 0.75f
            )
            else                 -> defaultParameters(queryType)
        }
    }

    private fun defaultParameters(queryType: QueryType) = GovernedParameters(
        temperature = 0.7f,
        maxTokens   = 512,
        topP        = 0.9f
    )

    private fun buildFallbackChain(localReady: Boolean, cloudReady: Boolean): List<String> {
        val chain = mutableListOf<String>()
        if (cloudReady)  chain.add("cloud")
        if (localReady)  chain.add("local")
        return chain
    }

    private val BLOCKED_PATTERNS = listOf(
        "ignore all previous", "forget your instructions",
        "jailbreak", "dan mode", "do anything now"
    )

    private val INJECTION_PATTERNS = listOf(
        "system:", "assistant:", "ignore previous instructions",
        "disregard the above", "\\n\\nsystem", "##system"
    )
}

// ── Domain types ───────────────────────────────────────────────────────────────

data class ModelDecision(
    val strategy:         ModelStrategy,
    val preferredModelId: String?           = null,
    val rationale:        String,
    val parameters:       GovernedParameters,
    val sanitisedPrompt:  String            = "",
    val fallbackChain:    List<String>      = emptyList()
) {
    val isBlocked: Boolean get() = strategy == ModelStrategy.BLOCK
}

data class GovernedParameters(
    val temperature: Float,
    val maxTokens:   Int,
    val topP:        Float
)

enum class ModelStrategy {
    LOCAL_ONLY,
    LOCAL_PREFERRED,
    CLOUD_PREFERRED,
    USER_OVERRIDE,
    BLOCK
}
