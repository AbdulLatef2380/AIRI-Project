package com.airi.core.model

import com.airi.assistant.LlamaNative
import kotlinx.coroutines.* import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * ModelManager - النسخة النهائية المدمجة
 * مسؤول عن إدارة دورة حياة النموذج وتحديث التقدم (Progress) الحقيقي القادم من C++.
 */
object ModelManager {

    private var isLoaded = false
    
    // إنشاء Scope خاص بالمدير لضمان استقرار العمليات الخلفية ومنع تداخل المهام
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * التحقق من حالة جاهزية النموذج
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * تحميل النموذج مع ربط الـ Progress الحقيقي القادم من C++
     * @param modelPath مسار ملف الـ GGUF
     * @param onProgress وظيفة لتحديث شريط التقدم في واجهة المستخدم
     */
    suspend fun loadModel(
        modelPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        // التحول إلى السياق الخاص بالنموذج (IO Thread)
        return withContext(modelScope.coroutineContext) {
            try {
                // استخدام suspendCoroutine لتحويل دالة الـ Callback الخاصة بـ Llama إلى نتيجة مباشرة
                suspendCoroutine<Boolean> { continuation ->
                    
                    LlamaNative.loadModelWithProgress(modelPath, object : LlamaNative.ProgressCallback {
                        override fun onProgress(percent: Int) {
                            // تأمين تحديث الواجهة عبر الخيط الرئيسي (Main Thread) لضمان عدم حدوث Crash
                            modelScope.launch(Dispatchers.Main) {
                                onProgress(percent)
                            }
                        }
                    })
                    
                    // بمجرد انتهاء دالة الـ Native من تنفيذ التحميل بالكامل
                    isLoaded = true
                    continuation.resume(true)
                }
            } catch (e: Exception) {
                isLoaded = false
                false
            }
        }
    }
}
