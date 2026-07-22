package com.airi.assistant.agent.learning.reinforcement

import android.util.Log

/**
 * AdaptivePolicy — learned agent routing bias built on [ReinforcementMemory].
 *
 * REAL EXECUTION:
 *   - [adjustScore] blends a base routing score with learned preference signals
 *     from [ReinforcementMemory], giving AIRI memory about which agents work
 *     well for which intents.
 *   - [preferredAgentFor] ranks a list of candidate agent IDs by their
 *     blended (keyword + learned) score and returns the winner.
 *   - [recordUserFeedback] is the primary learning entry-point for the UI
 *     layer (thumbs up / thumbs down on agent responses).
 *   - [recordUserCorrection] is called when the user says "no, use X instead"
 *     and the orchestrator re-routes.
 *
 * WIRING:
 *   - [SubAgentRegistry.route] (or its caller) calls [adjustScore] after the
 *     keyword + canHandle pass to break ties with learned preference.
 *   - [ChatViewModel] or [ProductionAgentOrchestrator] calls [recordUserFeedback]
 *     on thumbs-up/down events.
 */
object AdaptivePolicy {

    private const val TAG = "AdaptivePolicy"

    // Blending weight: 0.0 = pure keyword score, 1.0 = pure learned score
    private const val LEARNED_WEIGHT = 0.35

    /**
     * Blend the keyword-match [baseScore] with the learned preference signal
     * for ([context], [key]).
     *
     * @param baseScore   Integer keyword-match score from [SubAgentRegistry.route].
     * @param context     Routing context string (e.g. "query_type_analytical").
     * @param key         Agent ID or action key to look up.
     * @return            Blended integer score; higher = more preferred.
     */
    fun adjustScore(baseScore: Int, context: String, key: String): Int {
        val learnedDelta = ReinforcementMemory.getAdjustment(context, key)
        val blended = baseScore + (learnedDelta * LEARNED_WEIGHT).toInt()
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "adjustScore base=$baseScore learned=$learnedDelta blended=$blended ctx=$context key=$key")
        return blended
    }

    /**
     * Rank [candidates] by blended score and return the top agent ID,
     * or null if [candidates] is empty.
     *
     * @param context     Contextual routing key (e.g. input intent type).
     * @param candidates  List of (agentId, keywordScore) pairs.
     * @return            The agentId of the highest-scored candidate.
     */
    fun preferredAgentFor(
        context:    String,
        candidates: List<Pair<String, Int>>
    ): String? {
        if (candidates.isEmpty()) return null
        return candidates
            .map  { (id, base) -> id to adjustScore(base, context, id) }
            .maxByOrNull { (_, score) -> score }
            ?.first
            if (com.airi.assistant.BuildConfig.DEBUG) .also { Log.d(TAG, "preferredAgent=$it context=$context") }
    }

    /**
     * Record that the user gave positive or negative feedback on an agent response.
     *
     * @param agentId   The agent that produced the response.
     * @param context   The routing context in which it was used.
     * @param positive  true = thumbs up, false = thumbs down.
     */
    fun recordUserFeedback(agentId: String, context: String, positive: Boolean) {
        ReinforcementMemory.recordUserFeedback(context, agentId, positive)
        Log.i(TAG, "User feedback ${if (positive) "+" else "-"} → agent=$agentId ctx=$context")
    }

    /**
     * Record that the user explicitly corrected routing from [originalAgentId]
     * to [preferredAgentId]. Both signals are written with 1.5× weight.
     */
    fun recordUserCorrection(
        context:         String,
        originalAgentId: String,
        preferredAgentId: String
    ) {
        ReinforcementMemory.recordUserCorrection(context, originalAgentId, preferredAgentId)
        Log.i(TAG, "Routing correction: $originalAgentId → $preferredAgentId ctx=$context")
    }

    /**
     * Suggest a sorted list of agent IDs for [context], ranked by learned
     * preference. Useful for the observability screen or proactive suggestions.
     *
     * @param context    The routing context.
     * @param agentIds   All candidate agent IDs to rank.
     */
    fun rankAgents(context: String, agentIds: List<String>): List<String> =
        ReinforcementMemory.rank(context, agentIds)
}
