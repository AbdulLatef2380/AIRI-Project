package com.airi.assistant.agent.execution

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * مسجل تنفيذ الخطط (Execution Logger)
 * يقوم بتسجيل كل خطة يتم تنفيذها لتقييمها لاحقاً.
 */
object ExecutionLogger {
    private const val TAG = "ExecutionLogger"
    // SupervisorJob: one failed ExperienceStore.saveRecord() must not kill the
    // entire scope and silence all future logging for this session.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * تسجيل بداية تنفيذ خطة جديدة
     */
    fun logStart(goal: String, plan: String) {
        Log.i(TAG, "Starting execution for goal: $goal")
        // يمكننا تخزين الحالة "قيد التنفيذ" هنا إذا لزم الأمر
    }

    /**
     * تسجيل نهاية تنفيذ الخطة مع النتيجة
     */
    fun logEnd(goal: String, plan: String, steps: String, result: Any?, score: Float) {
        val record = ExecutionRecord(
            goal = goal,
            plan = plan,
            steps = steps,
            result = result?.toString() ?: "",
            score = score
        )
        
        scope.launch {
            try {
                // سيتم حفظ السجل في قاعدة البيانات عبر ExperienceStore لاحقاً
                ExperienceStore.saveRecord(record)
                Log.d(TAG, "Execution record saved for goal: $goal (Score: $score)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save execution record: ${e.message}")
            }
        }
    }
}
