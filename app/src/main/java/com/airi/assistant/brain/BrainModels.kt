package com.airi.assistant.brain


data class BrainInput(
    val text: String,
    val source: InputSource,
    val includeScreenContext: Boolean = false
)

enum class InputSource {
    CHAT,
    VOICE,
    SYSTEM
}

 * مخرجات الدماغ: النص الراجع ومعرف الهدف المنفذ (إ
data class BrainOutput(
    val responseText: String,
    val executedGoalId: String? = null
)
