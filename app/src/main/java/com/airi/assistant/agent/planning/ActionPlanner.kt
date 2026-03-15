package com.airi.assistant.agent.planning

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
