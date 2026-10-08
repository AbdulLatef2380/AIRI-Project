package com.airi.assistant.agent.learning.reinforcement


/**
 * AdaptivePolicy — learned agent routing bias built on ReinforcementMemory.
 */
object AdaptivePolicy {


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
