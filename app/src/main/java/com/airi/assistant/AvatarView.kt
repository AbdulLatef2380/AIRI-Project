package com.airi.assistant

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView

/**
 * فئة مسؤولة عن التجسيد البصري لـ AIRI (النبض، الألوان، والحركة)
 */
class AvatarView(private val context: Context, private val imageView: ImageView) {

    private var pulseAnimation: AlphaAnimation? = null

    /**
     * تحديث مظهر AIRI بناءً على الحالة العاطفية
     */
    fun updateVisualState(state: EmotionEngine.State) {
        val color = when (state) {
            EmotionEngine.State.NEUTRAL -> "#B0BEC5" // رمادي هادئ
            EmotionEngine.State.WARM -> "#FFD54F"    // ذهبي دافئ
            EmotionEngine.State.FOCUSED -> "#64B5F6" // أزرق مركز
            EmotionEngine.State.CONCERNED -> "#E57373" // أحمر قلق
            EmotionEngine.State.CURIOUS -> "#BA68C8"  // بنفسجي فضولي
            EmotionEngine.State.CARE -> "#81C784"     // أخضر مريح
            EmotionEngine.State.EXHAUSTED -> "#9E9E9E" // رمادي غامق
            EmotionEngine.State.DETACHED -> "#607D8B"  // أزرق رمادي
        }
        
        imageView.setColorFilter(Color.parseColor(color))
        startPulse(state)
    }

    fun setEmotion(state: EmotionEngine.State) {
        updateVisualState(state)
    }

    /**
     * بدء تأثير "النبض" البصري الذي يعبر عن "حياة" AIRI
     */
    private fun startPulse(state: EmotionEngine.State) {
        pulseAnimation?.cancel()
        
        // النبض يعتمد على رصانة الحالة: أبطأ في الرعاية والهدوء
        val duration = when (state) {
            EmotionEngine.State.WARM -> 1500L // أبطأ قليلاً للرصانة
            EmotionEngine.State.CARE -> 5000L // نبض خفي جداً وشبه ثابت
            EmotionEngine.State.CURIOUS -> 1000L // أسرع قليلاً للانتباه
            else -> 3000L
        }

        pulseAnimation = AlphaAnimation(0.8f, 1.0f).apply { // تباين أقل لراحة العين
            this.duration = duration
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        
        imageView.startAnimation(pulseAnimation)
    }

    /**
     * تأثير "الوميض" عند لفت الانتباه لشيء مهم على الشاشة
     */
    fun flashAlert() {
        val flash = AlphaAnimation(1.0f, 0.0f).apply {
            duration = 200
            repeatCount = 3
            repeatMode = Animation.REVERSE
        }
        imageView.startAnimation(flash)
    }
}
