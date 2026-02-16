package com.airi.assistant

import android.os.SystemClock

/**
 * مدير ميزانية الحواس: يضمن عدم تجاوز AIRI لحدود الإزعاج أو استهلاك الطاقة.
 * يطبق قواعد التهدئة (Cooldowns) والأولويات.
 */
class SensoryBudgetManager {

    private var lastVisualHintTime: Long = 0
    private var lastHapticTime: Long = 0
    
    private val HINT_COOLDOWN = 5 * 60 * 1000L // 5 دقائق بين التلميحات البصرية
    private val HAPTIC_COOLDOWN = 2000L        // ثانيتان بين الاهتزازات

    /**
     * التحقق مما إذا كان مسموحاً بتقديم تلميح بصري (Proactive Hint)
     */
    fun canShowVisualHint(importance: Int, isCareMode: Boolean): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        
        // لا تلميحات في وضع الرعاية إلا للأهمية القصوى
        if (isCareMode && importance < 9) return false
        
        // التحقق من فترة التهدئة
        if (currentTime - lastVisualHintTime < HINT_COOLDOWN) return false
        
        lastVisualHintTime = currentTime
        return true
    }

    /**
     * التحقق مما إذا كان مسموحاً بالاهتزاز الحسي
     */
    fun canVibrate(): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastHapticTime < HAPTIC_COOLDOWN) return false
        
        lastHapticTime = currentTime
        return true
    }
}
