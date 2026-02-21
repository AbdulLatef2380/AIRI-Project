package com.airi.assistant.planner

import android.util.Log

/**
 * محرك تقييم الخطط (Plan Scorer)
 * يقوم بحساب درجة جودة الخطة بناءً على عدة معايير.
 */
object PlanScorer {
    private const val TAG = "PlanScorer"

    /**
     * تقييم جودة الخطة بناءً على النتيجة النهائية
     */
    fun score(result: Any?, stepsCount: Int, timeTaken: Long): Float {
        val resultStr = result?.toString() ?: ""
        var score = 0.0f

        // 1. تقييم النجاح (Success)
        if (resultStr.contains("Success", ignoreCase = true) || resultStr.contains("OK", ignoreCase = true)) {
            score += 0.7f
        } else if (resultStr.contains("Error", ignoreCase = true) || resultStr.contains("Fail", ignoreCase = true)) {
            score += 0.1f
        }

        // 2. تقييم الكفاءة (Efficiency) - كلما كانت الخطوات أقل، كانت الدرجة أعلى (بشرط النجاح)
        if (score > 0.5f) {
            val efficiencyBonus = when {
                stepsCount <= 2 -> 0.3f
                stepsCount <= 5 -> 0.2f
                else -> 0.1f
            }
            score += efficiencyBonus
        }

        // 3. تقييم السرعة (Speed) - مكافأة للخطط السريعة
        if (timeTaken < 5000) { // أقل من 5 ثوانٍ
            score += 0.1f
        }

        // التأكد من أن الدرجة في النطاق [0.0, 1.0]
        val finalScore = score.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Plan Scored: $finalScore (Steps: $stepsCount, Time: ${timeTaken}ms)")
        return finalScore
    }

    /**
     * تقييم بناءً على رد فعل المستخدم (User Feedback)
     */
    fun scoreFromFeedback(feedback: String): Float {
        return when {
            feedback.contains("Good", ignoreCase = true) || feedback.contains("Thanks", ignoreCase = true) -> 1.0f
            feedback.contains("Bad", ignoreCase = true) || feedback.contains("Wrong", ignoreCase = true) -> 0.0f
            else -> 0.5f
        }
    }
}
