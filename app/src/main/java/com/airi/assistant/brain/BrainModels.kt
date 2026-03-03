package com.airi.assistant.brain

import com.airi.core.chain.AgentGoal

/**
 * البيانات المدخلة للدماغ
 */
data class BrainInput(
    val text: String,
    val source: InputSource,
    val includeScreenContext: Boolean = false
)

/**
 * مصادر الإدخال المدعومة
 */
enum class InputSource {
    CHAT,
    VOICE,
    SYSTEM
}

/**
 * مخرجات الدماغ بعد المعالجة أو التنفيذ
 */
data class BrainOutput(
    val responseText: String,
    val executedGoalId: String? = null
)

/**
 * 🔥 الواجهة المحدثة: المحرك التنفيذي للأهداف
 * تعيد [Boolean] ليعرف الدماغ ما إذا كانت الخطة قد نجحت أم فشلت
 */
interface GoalExecutor {
    suspend fun executeGoal(goal: AgentGoal): Boolean
}
