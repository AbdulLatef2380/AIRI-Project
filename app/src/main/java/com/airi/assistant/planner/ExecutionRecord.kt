package com.airi.assistant.planner

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * سجل تنفيذ الخطة (Execution Record)
 * يمثل "خبرة" واحدة لـ AIRI في تنفيذ مهمة معينة.
 */
@Entity(tableName = "execution_records")
data class ExecutionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val goal: String,           // الهدف الأصلي للمستخدم
    val plan: String,           // الخطة التي وضعها الـ LLM (JSON)
    val steps: String,          // الخطوات التي تم تنفيذها فعلياً
    val result: String,         // النتيجة النهائية (Success/Failure + Output)
    val score: Float = 0.0f,    // تقييم جودة الخطة (0.0 to 1.0)
    val feedback: String? = null // ملاحظات إضافية أو رد فعل المستخدم
)
