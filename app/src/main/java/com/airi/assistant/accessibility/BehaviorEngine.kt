package com.airi.assistant.accessibility

object BehaviorEngine {

    fun adjustSuggestionPriority(
        baseSuggestions: List<String>
    ): List<String> {

        return baseSuggestions
            .sortedByDescending { BehaviorMemory.getUsageScore(it) }
    }
}
