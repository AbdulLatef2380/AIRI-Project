package com.airi.assistant.agent.planning

data class AiriIntent(
    val type: IntentType,
    val target: String? = null,
    val index: Int? = 0
)
