package com.airi.assistant.agent.planning

import com.airi.assistant.core.intent.IntentType

object ActionPlanner {

    fun plan(intent: AiriIntent): List<AiriIntent> {

        return when (intent.type) {

            IntentType.CLICK -> {
                listOf(intent)
            }

            IntentType.TYPE -> {
                listOf(intent)
            }

            else -> listOf(intent)
        }
    }
}
