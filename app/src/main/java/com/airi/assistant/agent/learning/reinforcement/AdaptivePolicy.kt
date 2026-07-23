package com.airi.assistant.agent.learning.reinforcement

import android.util.Log

/**
 * AdaptivePolicy — learned agent routing bias built on ReinforcementMemory.
 */
object AdaptivePolicy {

    private const val TAG = "AdaptivePolicy"

    // 0.0 = keyword only, 1.0 = learned only
    private const val LEARNED_WEIGHT = 0.35

    /**
     * Blend keyword score with learned reinforcement score.
     */
    fun adjustScore(
        baseScore: Int,
        context: String,
        key: String
    ): Int {
        val learnedDelta = ReinforcementMemory.getAdjustment(context, key)
        val blended = baseScore + (learnedDelta * LEARNED_WEIGHT).toInt()

        if (com.airi.assistant.BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "adjustScore base=$baseScore learned=$learnedDelta blended=$blended ctx=$context key=$key"
            )
        }

        return blended
    }

    /**
     * Return the preferred agent from the candidate list.
     */
    fun preferredAgentFor(
        context: String,
        candidates: List<Pair<String, Int>>
    ): String? {

        if (candidates.isEmpty()) {
            return null
        }

        val winner = candidates
            .map { (id, base) ->
                id to adjustScore(base, context, id)
            }
            .maxByOrNull { it.second }
            ?.first

        if (com.airi.assistant.BuildConfig.DEBUG && winner != null) {
            Log.d(TAG, "preferredAgent=$winner context=$context")
        }

        return winner
    }

    /**
     * User thumbs up/down.
     */
    fun recordUserFeedback(
        agentId: String,
        context: String,
        positive: Boolean
    ) {
        ReinforcementMemory.recordUserFeedback(
            context,
            agentId,
            positive
        )

        Log.i(
            TAG,
            "User feedback ${if (positive) "+" else "-"} → agent=$agentId ctx=$context"
        )
    }

    /**
     * User corrected routing.
     */
    fun recordUserCorrection(
        context: String,
        originalAgentId: String,
        preferredAgentId: String
    ) {
        ReinforcementMemory.recordUserCorrection(
            context,
            originalAgentId,
            preferredAgentId
        )

        Log.i(
            TAG,
            "Routing correction: $originalAgentId → $preferredAgentId ctx=$context"
        )
    }

    /**
     * Rank all agents for a context.
     */
    fun rankAgents(
        context: String,
        agentIds: List<String>
    ): List<String> {
        return ReinforcementMemory.rank(context, agentIds)
    }
}
