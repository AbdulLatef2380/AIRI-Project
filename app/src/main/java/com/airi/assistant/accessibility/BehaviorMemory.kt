package com.airi.assistant.accessibility

object BehaviorMemory {

    private val actionUsage = mutableMapOf<String, Int>()

    fun recordAction(action: String) {
        actionUsage[action] = (actionUsage[action] ?: 0) + 1
    }

    fun getUsageScore(action: String): Int {
        return actionUsage[action] ?: 0
    }

    fun reset() {
        actionUsage.clear()
    }
}
