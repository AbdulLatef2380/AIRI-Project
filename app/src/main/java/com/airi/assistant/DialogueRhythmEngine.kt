package com.airi.assistant

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * محرك إيقاع الحوار: يضيف لمسات بشرية (توقفات، تردد خفيف) لكسر جمود الآلة.
 */
class DialogueRhythmEngine {

    /**
     * حساب فترة التوقف (Pause) بناءً على تعقيد النص والحالة العاطفية
     */
    suspend fun simulateThinking(text: String, state: EmotionEngine.State) {
        val baseDelay = when (state) {
            EmotionEngine.State.FOCUSED -> 500L
            EmotionEngine.State.CARE -> 1500L
            else -> 1000L
        }
        
        // إضافة تأخير إضافي بناءً على طول النص (محاكاة القراءة/التفكير)
        val complexityDelay = (text.length * 10L).coerceAtMost(2000L)
        
        // إضافة عنصر عشوائي بسيط (بشري)
        val randomJitter = Random.nextLong(100, 500)
        
        delay(baseDelay + complexityDelay + randomJitter)
    }

    /**
     * إضافة "تردد لفظي" خفيف في بداية الجمل المعقدة أو الحساسة
     */
    fun applyHumanTouch(response: String, state: EmotionEngine.State): String {
        if (state == EmotionEngine.State.CARE && Random.nextFloat() > 0.7f) {
            val fillers = listOf("حسناً... ", "أفهمك... ", "دعني أفكر... ")
            return fillers.random() + response
        }
        return response
    }
}
