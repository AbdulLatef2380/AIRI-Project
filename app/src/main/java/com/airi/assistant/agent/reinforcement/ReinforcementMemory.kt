package com.airi.assistant.agent.reinforcement

object ReinforcementMemory {

    private val successWeights = mutableMapOf<String, Int>()
    private val failureWeights = mutableMapOf<String, Int>()

    fun recordSuccess(key: String) {
        successWeights[key] = (successWeights[key] ?: 0) + 1
    }

    fun recordFailure(key: String) {
        failureWeights[key] = (failureWeights[key] ?: 0) + 1
    }

    fun getAdjustment(key: String): Int {
        val success = successWeights[key] ?: 0
        val failure = failureWeights[key] ?: 0
        return (success * 3) - (failure * 4)
    }
}
