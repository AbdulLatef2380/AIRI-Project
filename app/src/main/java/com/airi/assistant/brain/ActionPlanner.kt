package com.airi.assistant.brain

object ActionPlanner {

    fun plan(intent: Intent): List<Intent> {

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
