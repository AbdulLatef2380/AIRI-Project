package com.airi.assistant.core

data class IntentEvent(
    val rawText: String,
    val source: InputSource,
    val timestamp: Long = System.currentTimeMillis()
)

enum class InputSource {
    VOICE,
    TEXT,
    SCREEN
}

data class IntentResult(
    val type: IntentType,
    val confidence: Float,
    val extractedData: Map<String, String> = emptyMap()
)

enum class IntentType {
    SYSTEM_COMMAND,
    APP_CONTROL,
    SCREEN_ANALYSIS,
    CONVERSATION,
    AUTOMATION,
    UNKNOWN
}

class IntentRouter {

    fun route(event: IntentEvent): IntentResult {
        val normalized = normalize(event.rawText)

        // 1. أوامر النظام
        detectSystemCommand(normalized)?.let { return it }

        // 2. أوامر التطبيقات
        detectAppControl(normalized)?.let { return it }

        // 3. تحليل الشاشة
        detectScreenAnalysis(normalized)?.let { return it }

        // 4. محادثة عامة
        if (isConversation(normalized)) {
            return IntentResult(IntentType.CONVERSATION, 0.7f)
        }

        return IntentResult(IntentType.UNKNOWN, 0.0f)
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase()
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
    }

    private fun detectSystemCommand(text: String): IntentResult? {
        val commands = mapOf(
            "ارجع" to "back",
            "الرجوع" to "back",
            "back" to "back",
            "افتح الواي فاي" to "wifi_on",
            "اطفئ الواي فاي" to "wifi_off"
        )

        for ((key, value) in commands) {
            if (text.contains(key)) {
                return IntentResult(
                    type = IntentType.SYSTEM_COMMAND,
                    confidence = 0.95f,
                    extractedData = mapOf("command" to value)
                )
            }
        }
        return null
    }

    private fun detectAppControl(text: String): IntentResult? {
        val openRegex = Regex("افتح (.+)")
        val match = openRegex.find(text)

        if (match != null) {
            val appName = match.groupValues[1]
            return IntentResult(
                type = IntentType.APP_CONTROL,
                confidence = 0.9f,
                extractedData = mapOf("appName" to appName)
            )
        }
        return null
    }

    private fun detectScreenAnalysis(text: String): IntentResult? {
        val patterns = listOf("ماذا ترى", "اقرأ الشاشة", "حلل الشاشة")
        for (pattern in patterns) {
            if (text.contains(pattern)) {
                return IntentResult(IntentType.SCREEN_ANALYSIS, 0.9f)
            }
        }
        return null
    }

    private fun isConversation(text: String): Boolean {
        return text.length > 4
    }
}
