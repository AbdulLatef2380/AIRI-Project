package com.airi.assistant.agent.decision

import android.util.Log
import com.airi.assistant.BuildConfig

class PatternAggregator {

    private var dependencyScore: Double = 0.0
    private val scoreIncrementBehavioral = 0.001
    private val scoreIncrementLinguistic = 0.005
    private val scoreDecrementDaily = 0.0005

    private val thresholdLow = 0.1
    private val thresholdMedium = 0.3
    private val thresholdHigh = 0.6
    private val thresholdCritical = 0.9

    fun recordInteraction(durationMinutes: Int, isFirstLastInteractionOfDay: Boolean, externalAppUsageLow: Boolean) {
        if (durationMinutes > 60) dependencyScore += scoreIncrementBehavioral
        if (isFirstLastInteractionOfDay) dependencyScore += scoreIncrementBehavioral * 2
        if (externalAppUsageLow) dependencyScore += scoreIncrementBehavioral
        dependencyScore = dependencyScore.coerceAtMost(1.0)
        if (BuildConfig.DEBUG) Log.d("PatternAggregator", "Dependency Score after behavioral: $dependencyScore")
    }

    fun recordLinguisticPattern(patternDetected: Boolean) {
        if (patternDetected) dependencyScore += scoreIncrementLinguistic
        dependencyScore = dependencyScore.coerceAtMost(1.0)
        if (BuildConfig.DEBUG) Log.d("PatternAggregator", "Dependency Score after linguistic: $dependencyScore")
    }

    fun applyDailyDecay() {
        dependencyScore -= scoreDecrementDaily
        dependencyScore = dependencyScore.coerceAtLeast(0.0)
        if (BuildConfig.DEBUG) Log.d("PatternAggregator", "Dependency Score after daily decay: $dependencyScore")
    }

    fun getDetachmentLevel(): DetachmentLevel {
        return when {
            dependencyScore >= thresholdCritical -> DetachmentLevel.CRITICAL
            dependencyScore >= thresholdHigh     -> DetachmentLevel.HIGH
            dependencyScore >= thresholdMedium   -> DetachmentLevel.MEDIUM
            dependencyScore >= thresholdLow      -> DetachmentLevel.LOW
            else                                 -> DetachmentLevel.NONE
        }
    }

    fun getCurrentDependencyScore(): Double = dependencyScore

    enum class DetachmentLevel {
        NONE, LOW, MEDIUM, HIGH, CRITICAL
    }
}
