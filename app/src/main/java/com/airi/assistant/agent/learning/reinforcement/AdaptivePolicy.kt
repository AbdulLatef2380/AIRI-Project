package com.airi.assistant.agent.learning.reinforcement

object AdaptivePolicy {

    fun adjustScore(
        baseScore: Int,
        context: String,
        key: String
    ): Int {
        val reinforcement =
            ReinforcementMemory.getAdjustment(context, key)

        return baseScore + reinforcement
    }
}
