package com.airi.assistant.agent.loop.tool

/**
 * Truthful, request-scoped explanation for a required tool that did not reach the model.
 * This is deliberately presentation-independent: the same facts can be rendered for the
 * user, the model snapshot, and execution traces without inventing an execution result.
 */
data class ToolAdmissionDiagnostic(
    val toolName: String,
    val registered: Boolean,
    val ready: Boolean,
    val exposed: Boolean,
    val blockedReason: String,
    val nextAction: String,
) {
    fun machineLine(): String =
        "$toolName: registered=$registered; ready=$ready; exposed=$exposed; " +
            "blocked_reason=${blockedReason.replace(';', ',')}; next_action=$nextAction"

    fun humanLine(arabic: Boolean): String = if (arabic) {
        val registration = if (registered) "مسجلة" else "غير مسجلة"
        val readiness = if (ready) "جاهزة" else "غير جاهزة"
        val exposure = if (exposed) "معروضة" else "غير معروضة"
        "الأداة $toolName: $registration، $readiness، $exposure؛ السبب: $blockedReason؛ الإجراء التالي: $nextAction."
    } else {
        "Tool $toolName is ${if (registered) "registered" else "not registered"}, " +
            "${if (ready) "ready" else "not ready"}, and ${if (exposed) "exposed" else "not exposed"}; " +
            "reason: $blockedReason; next action: $nextAction."
    }
}
