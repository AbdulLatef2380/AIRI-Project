package com.airi.assistant.agent.reinforcement

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
