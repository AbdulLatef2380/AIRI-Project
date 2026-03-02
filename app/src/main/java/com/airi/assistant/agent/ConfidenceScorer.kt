package com.airi.assistant.agent

object ConfidenceScorer {

    fun score(intent: String, context: String): Double {
        var base = 0.5

        if (context.contains(intent, ignoreCase = true))
            base += 0.2

        if (context.length > 100)
            base += 0.1

        if (intent.contains("delete", true) ||
            intent.contains("send", true))
            base -= 0.2

        return base.coerceIn(0.0, 1.0)
    }
}
