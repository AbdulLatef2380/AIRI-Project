package com.airi.assistant.agent.reinforcement

object ReinforcementMemory {

    private val memory = mutableMapOf<String, Int>()

    fun recordSuccess(context: String, key: String) {
        val composite = "${context}_$key"
        memory[composite] = (memory[composite] ?: 0) + 2
    }

    fun recordFailure(context: String, key: String) {
        val composite = "${context}_$key"
        memory[composite] = (memory[composite] ?: 0) - 3
    }

    fun getAdjustment(context: String, key: String): Int {
        val composite = "${context}_$key"
        return memory[composite] ?: 0
    }
}
