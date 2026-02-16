package com.airi.assistant

import android.util.Log

/**
 * بروتوكول الانسحاب اللطيف: يطبق تعديلات دقيقة على سلوك AIRI بناءً على درجة الاعتمادية المتراكمة.
 * لا يتخذ قرارات، بل يضبط النبرة والوتيرة واللغة لتشجيع التوازن بلطف.
 */
class GracefulDetachmentProtocol(private val patternAggregator: PatternAggregator) {

    /**
     * يحدد تعديل النبرة المطلوب بناءً على مستوى الاعتمادية.
     */
    fun getAdjustedTone(): ToneAdjustment {
        val level = patternAggregator.getDetachmentLevel()
        Log.d("GracefulDetachment", "Adjusting tone for level: $level")
        return when (level) {
            PatternAggregator.DetachmentLevel.NONE -> ToneAdjustment.NORMAL
            PatternAggregator.DetachmentLevel.LOW -> ToneAdjustment.SLIGHTLY_NEUTRAL
            PatternAggregator.DetachmentLevel.MEDIUM -> ToneAdjustment.NEUTRAL
            PatternAggregator.DetachmentLevel.HIGH -> ToneAdjustment.MORE_NEUTRAL
            PatternAggregator.DetachmentLevel.CRITICAL -> ToneAdjustment.VERY_NEUTRAL
        }
    }

    /**
     * يحدد تعديل وتيرة الاستجابة المطلوب.
     */
    fun getAdjustedPace(): PaceAdjustment {
        val level = patternAggregator.getDetachmentLevel()
        Log.d("GracefulDetachment", "Adjusting pace for level: $level")
        return when (level) {
            PatternAggregator.DetachmentLevel.NONE -> PaceAdjustment.NORMAL
            PatternAggregator.DetachmentLevel.LOW -> PaceAdjustment.SLIGHT_DELAY
            PatternAggregator.DetachmentLevel.MEDIUM -> PaceAdjustment.MODERATE_DELAY
            PatternAggregator.DetachmentLevel.HIGH -> PaceAdjustment.LONGER_DELAY
            PatternAggregator.DetachmentLevel.CRITICAL -> PaceAdjustment.VERY_LONG_DELAY
        }
    }

    /**
     * يقترح تعديلاً على اللغة لتشجيع الارتباط بالعالم الحقيقي.
     */
    fun getAdjustedLanguagePrompt(): String? {
        return when (patternAggregator.getDetachmentLevel()) {
            PatternAggregator.DetachmentLevel.MEDIUM -> "وجودك في العالم الحقيقي مهم بقدر أفكارك هنا."
            PatternAggregator.DetachmentLevel.HIGH -> "أتمنى أن تجد وقتاً للاستمتاع بلحظات خارج عالمنا الرقمي أيضاً."
            PatternAggregator.DetachmentLevel.CRITICAL -> "من المهم أن نحافظ على توازن صحي بين عالمنا هنا والعالم الحقيقي."
            else -> null
        }
    }

    // التعدادات تمثل مستويات التعديل المختلفة، وليست تشخيصات.

    enum class ToneAdjustment {
        NORMAL, // النبرة العادية الدافئة
        SLIGHTLY_NEUTRAL, // حيادية طفيفة
        NEUTRAL, // حيادية واضحة
        MORE_NEUTRAL, // أكثر حيادية
        VERY_NEUTRAL // حيادية شبه كاملة، مع الحفاظ على الاحترام
    }

    enum class PaceAdjustment {
        NORMAL, // استجابة فورية
        SLIGHT_DELAY, // تأخير بسيط جداً (مللي ثانية)
        MODERATE_DELAY, // تأخير ملحوظ قليلاً
        LONGER_DELAY, // تأخير أطول
        VERY_LONG_DELAY // أطول تأخير مقبول قبل أن يبدو كنظام معطل
    }
}
