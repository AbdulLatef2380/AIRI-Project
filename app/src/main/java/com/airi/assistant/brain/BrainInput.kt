package com.airi.assistant.brain

data class BrainInput(
    val text: String,
    val includeScreenContext: Boolean = false
)
