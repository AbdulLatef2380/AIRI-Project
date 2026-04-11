package com.airi.assistant.agent.planning

import com.airi.assistant.core.intent.IntentType

data class AiriIntent(
    val type: IntentType,
    val target: String? = null,
    val index: Int? = 0
)
