package com.airi.core.model

data class EmotionalState(
    val valence: Float,      // -1.0 to 1.0
    val arousal: Float,      // 0.0 to 1.0
    val dominance: Float     // 0.0 to 1.0
)
