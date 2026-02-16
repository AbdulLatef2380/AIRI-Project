package com.airi.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * محرك الحالة العاطفية المتقدم لـ AIRI (State Machine)
 * يعتمد على نظام النقاط التراكمية والعطالة العاطفية.
 */
class EmotionEngine {

    // الحالات العاطفية الأساسية
    enum class State {
        NEUTRAL,    // الحالة المستقرة
        WARM,       // ودودة (تحتاج نقاط إيجابية تراكمية)
        FOCUSED,    // مركزة (عند تنفيذ مهام تقنية)
        CONCERNED,  // قلقة (نقاط سلبية أو أخطاء)
        CURIOUS,    // فضولية (أسئلة استكشافية)
        CARE        // نمط الرعاية (دعم صامت وطمأنينة)
    }

    private var currentState = State.NEUTRAL
    private var emotionalScore = 0 // من -10 إلى +10
    private val threshold = 3      // العتبة اللازمة لتغيير الحالة
    private val decayRate = 1      // معدل العودة للحالة الطبيعية

    /**
     * معالجة مدخلات المستخدم وتحديث الحالة العاطفية بناءً على "الأوزان"
     */
    fun processInput(text: String): State {
        val sentiment = analyzeSentiment(text)
        updateScore(sentiment)
        
        val nextState = determineNextState()
        if (nextState != currentState) {
            currentState = nextState
        } else {
            applyInertia() // العودة التدريجية إذا لم يتغير شيء
        }
        
        return currentState
    }

    private fun analyzeSentiment(text: String): Int {
        val positiveWords = listOf("شكراً", "رائع", "أحب", "ممتاز", "جميل")
        val negativeWords = listOf("خطأ", "سيء", "حزين", "مشكلة", "فشل")
        
        var score = 0
        if (positiveWords.any { text.contains(it) }) score += 2
        if (negativeWords.any { text.contains(it) }) score -= 2
        if (text.contains("؟")) score += 1 // الفضول
        
        return score
    }

    private fun updateScore(change: Int) {
        emotionalScore = min(10, max(-10, emotionalScore + change))
    }

    private fun determineNextState(): State {
        return when {
            emotionalScore >= threshold -> State.WARM
            emotionalScore <= -threshold -> State.CONCERNED
            else -> State.NEUTRAL
        }
    }

    private fun applyInertia() {
        if (emotionalScore > 0) emotionalScore -= decayRate
        if (emotionalScore < 0) emotionalScore += decayRate
    }

    fun getCurrentState(): State = currentState

    fun getEmotionDrawable(): Int {
        return when (currentState) {
            State.NEUTRAL -> android.R.drawable.ic_menu_help
            State.WARM -> android.R.drawable.btn_star_big_on
            State.FOCUSED -> android.R.drawable.ic_menu_edit
            State.CONCERNED -> android.R.drawable.ic_dialog_alert
            State.CURIOUS -> android.R.drawable.ic_menu_view
            State.CARE -> android.R.drawable.ic_menu_compass // أيقونة تمثل التوجيه الهادئ
        }
    }

    /**
     * تفعيل نمط الرعاية (Care Mode)
     * يتم استدعاؤه عندما يستشعر النظام حاجة المستخدم للهدوء أو الدعم الصامت.
     */
    fun activateCareMode() {
        currentState = State.CARE
        emotionalScore = 5 // استقرار إيجابي
    }
}
