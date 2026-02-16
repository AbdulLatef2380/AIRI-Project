package com.airi.assistant

import android.util.Log

class PatternAggregator {

    private var dependencyScore: Double = 0.0
    private val scoreIncrementBehavioral = 0.001 // Small increment for behavioral signals
    private val scoreIncrementLinguistic = 0.005 // Slightly larger for linguistic patterns
    private val scoreDecrementDaily = 0.0005 // Daily decay to prevent false positives

    // Thresholds for different detachment levels
    private val thresholdLow = 0.1
    private val thresholdMedium = 0.3
    private val thresholdHigh = 0.6
    private val thresholdCritical = 0.9

    // Behavioral Signals
    fun recordInteraction(durationMinutes: Int, isFirstLastInteractionOfDay: Boolean, externalAppUsageLow: Boolean) {
        // This is a simplified example. In a real app, this would involve more complex logic
        // based on actual usage patterns over time.
        if (durationMinutes > 60) dependencyScore += scoreIncrementBehavioral
        if (isFirstLastInteractionOfDay) dependencyScore += scoreIncrementBehavioral * 2
        if (externalAppUsageLow) dependencyScore += scoreIncrementBehavioral

        // Ensure score doesn't exceed 1.0
        dependencyScore = dependencyScore.coerceAtMost(1.0)
        Log.d("PatternAggregator", "Dependency Score after behavioral: $dependencyScore")
    }

    // Indirect Linguistic Patterns
    fun recordLinguisticPattern(patternDetected: Boolean) {
        if (patternDetected) {
            dependencyScore += scoreIncrementLinguistic
        }
        dependencyScore = dependencyScore.coerceAtMost(1.0)
        Log.d("PatternAggregator", "Dependency Score after linguistic: $dependencyScore")
    }

    // Daily decay to reduce score over time if no new signals
    fun applyDailyDecay() {
        dependencyScore -= scoreDecrementDaily
        dependencyScore = dependencyScore.coerceAtLeast(0.0)
        Log.d("PatternAggregator", "Dependency Score after daily decay: $dependencyScore")
    }

    fun getDetachmentLevel(): DetachmentLevel {
        return when {
            dependencyScore >= thresholdCritical -> DetachmentLevel.CRITICAL
            dependencyScore >= thresholdHigh -> DetachmentLevel.HIGH
            dependencyScore >= thresholdMedium -> DetachmentLevel.MEDIUM
            dependencyScore >= thresholdLow -> DetachmentLevel.LOW
            else -> DetachmentLevel.NONE
        }
    }

    fun getCurrentDependencyScore(): Double {
        return dependencyScore
    }

    enum class DetachmentLevel {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }
}
