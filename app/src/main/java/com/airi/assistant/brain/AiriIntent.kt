package com.airi.assistant.brain

data class AiriIntent(
    val type: IntentType,
    val target: String? = null,
    val index: Int? = null
)
