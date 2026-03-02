package com.airi.assistant.agent

object BehaviorPolicy {

    fun requiresConfirmation(intent: String, confidence: Double): Boolean {

        if (confidence < 0.6)
            return true

        if (intent.contains("delete", true))
            return true

        if (intent.contains("payment", true))
            return true

        return false
    }
}
