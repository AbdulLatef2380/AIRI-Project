package com.airi.assistant.brain

object ActionPlanner {

    fun plan(intent: AiriIntent?): List<Action> {

        if (intent == null) return emptyList()

        return when (intent.type) {

            IntentType.CLICK -> listOf(
                Action.FindNode(intent.target),
                Action.Click
            )

            IntentType.TYPE -> listOf(
                Action.FindInput,
                Action.Type(intent.target)
            )

            IntentType.BACK -> listOf(
                Action.Back
            )

            else -> emptyList()
        }
    }
}
