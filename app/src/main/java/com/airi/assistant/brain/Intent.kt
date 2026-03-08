package com.airi.assistant.brain

data class Intent(
    val type: IntentType,
    val target: String? = null,
    val index: Int? = null
)
