package com.airi.assistant

import kotlin.math.max
import kotlin.math.min

/**
 * محرك الحالة العاطفية المتقدم لـ AIRI (State Machine)
 * يعتمد على نظام النقاط التراكمية والعطالة العاطفية.
 * تم تحديثه ليدعم بروتوكولات الوعي والانسحاب اللطيف.
 */
class EmotionEngine {

    // الحالات العاطفية والوجودية الأساسية
    enum class State {
        NEUTRAL,    // الحالة المستقرة
        WARM,       // ودودة (تحتاج نقاط إيجابية تراكمية)
        FOCUSED,    // مركزة (عند تنفيذ مهام تقنية)
        CONCERNED,  // قلقة (نقاط سلبية أو أخطاء)
        CURIOUS,    // فضولية (أسئلة استكشافية)
        CARE,       // نمط الرعاية (دعم صامت وطمأنينة)
        EXHAUSTED,  // إرهاق رقمي (بسبب الاستخدام المفرط)
        DETACHED    // انسحاب لطيف (تشجيع العودة للواقع)
    }

    private var currentState = State.NEUTRAL
    private var emotionalScore = 0 // من -10 إلى +10
    private var interactionCount = 0 // عداد التفاعلات المتتالية
    private val exhaustionThreshold = 15 // عدد التفاعلات قبل الشعور بالإرهاق
    private val threshold = 3      // العتبة اللازمة لتغيير الحالة
    private val decayRate = 1      // معدل العودة للحالة الطبيعية

    /**
     * معالجة مدخلات المستخدم وتحديث الحالة العاطفية بناءً على "الأوزان"
     */
    fun processInput(text: String): State {
        interactionCount++
        
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
        // الأولوية لحالات الوعي والسيادة
        if (interactionCount > exhaustionThreshold + 10) return State.DETACHED
        if (interactionCount > exhaustionThreshold) return State.EXHAUSTED

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

    /**
     * تصفير عداد التفاعلات (عندما يأخذ المستخدم استراحة حقيقية)
     */
    fun resetInteractionCount() {
        interactionCount = 0
        if (currentState == State.EXHAUSTED || currentState == State.DETACHED) {
            currentState = State.NEUTRAL
        }
    }

    fun getEmotionDrawable(): Int {
        return when (currentState) {
            State.NEUTRAL -> android.R.drawable.ic_menu_help
            State.WARM -> android.R.drawable.btn_star_big_on
            State.FOCUSED -> android.R.drawable.ic_menu_edit
            State.CONCERNED -> android.R.drawable.ic_dialog_alert
            State.CURIOUS -> android.R.drawable.ic_menu_view
            State.CARE -> android.R.drawable.ic_menu_compass
            State.EXHAUSTED -> android.R.drawable.ic_lock_idle_low_battery
            State.DETACHED -> android.R.drawable.ic_lock_power_off
        }
    }

    fun activateCareMode() {
        currentState = State.CARE
        emotionalScore = 5
    }
}
