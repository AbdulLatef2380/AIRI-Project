package com.airi.assistant.agent.reinforcement

object AdaptivePolicy {

    fun adjustScore(baseScore: Int, key: String): Int {
        val reinforcement = ReinforcementMemory.getAdjustment(key)
        return baseScore + reinforcement
    }
}
