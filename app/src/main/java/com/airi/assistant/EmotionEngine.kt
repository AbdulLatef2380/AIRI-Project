package com.airi.assistant

import android.util.Log

/**
 * محرك المشاعر (Emotion Engine)
 * تم تحديثه ليكون نظام تأثير حقيقي (Influence System) وليس مجرد طبقة تجميلية.
 * يتضمن الآن: Decay Function, Reinforcement Logic, Trigger Weighting.
 */
class EmotionEngine {

    enum class State {
        NEUTRAL, WARM, FOCUSED, CONCERNED, CURIOUS, CARE, EXHAUSTED, DETACHED
    }

    private var currentState: State = State.NEUTRAL
    private var emotionalScore: Float = 0.0f // من -1.0 إلى 1.0
    private var interactionCount: Int = 0
    
    // Decay settings
    private val decayRate = 0.05f
    private val exhaustionThreshold = 50

    /**
     * معالجة تأثير حدث معين على الحالة العاطفية (Trigger Weighting)
     */
    fun processTrigger(type: String, intensity: Float) {
        interactionCount++
        
        val weight = when (type) {
            "USER_PRAISE" -> 0.2f
            "USER_CRITICISM" -> -0.3f
            "TASK_SUCCESS" -> 0.1f
            "TASK_FAILURE" -> -0.2f
            "SYSTEM_ERROR" -> -0.4f
            else -> 0.05f
        }
        
        emotionalScore += weight * intensity
        emotionalScore = emotionalScore.coerceIn(-1.0f, 1.0f)
        
        updateState()
        Log.d("EmotionEngine", "Trigger: $type, Score: $emotionalScore, State: $currentState")
    }

    /**
     * وظيفة التلاشي (Decay Function) - تعيد AIRI للحالة الطبيعية بمرور الوقت
     */
    fun applyDecay() {
        if (emotionalScore > 0) {
            emotionalScore = (emotionalScore - decayRate).coerceAtLeast(0.0f)
        } else if (emotionalScore < 0) {
            emotionalScore = (emotionalScore + decayRate).coerceAtMost(0.0f)
        }
        updateState()
    }

    private fun updateState() {
        // Reinforcement Logic: الإرهاق يؤثر على الحالة العاطفية
        if (interactionCount > exhaustionThreshold) {
            currentState = State.EXHAUSTED
            return
        }

        currentState = when {
            emotionalScore > 0.7f -> State.WARM
            emotionalScore > 0.3f -> State.CURIOUS
            emotionalScore < -0.7f -> State.DETACHED
            emotionalScore < -0.3f -> State.CONCERNED
            else -> State.NEUTRAL
        }
    }

    fun getCurrentState(): State = currentState

    fun setEmotion(state: State) {
        currentState = state
    }

    fun resetInteractionCount() {
        interactionCount = 0
        if (currentState == State.EXHAUSTED || currentState == State.DETACHED) {
            currentState = State.NEUTRAL
        }
    }
}
