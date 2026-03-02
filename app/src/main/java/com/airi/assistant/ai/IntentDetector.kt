package com.airi.assistant.ai

object IntentDetector {

    fun detectIntent(screenText: String): String {
        val text = screenText.lowercase()

        return when {
            containsAny(text, listOf("login", "sign in", "password", "otp", "verification")) ->
                "AUTH_FLOW"

            containsAny(text, listOf("pay", "payment", "confirm", "$", "transaction")) ->
                "PAYMENT_CONFIRMATION"

            containsAny(text, listOf("delete", "remove", "erase", "permanently")) ->
                "DESTRUCTIVE_ACTION"

            containsAny(text, listOf("subscribe", "upgrade", "premium")) ->
                "SUBSCRIPTION_FLOW"

            containsAny(text, listOf("email", "message", "chat")) ->
                "COMMUNICATION"

            else -> "GENERAL_VIEW"
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
}
