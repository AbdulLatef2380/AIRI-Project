package com.airi.core.model

import com.airi.assistant.LlamaNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * ModelManager - النسخة المطورة التي تدعم التقدم الحقيقي.
 */
object ModelManager {

    private var isLoaded = false

    fun isModelLoaded(): Boolean = isLoaded

    /**
     * تحميل النموذج مع ربط الـ Progress الحقيقي القادم من C++
     */
    suspend fun loadModel(
        modelPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // استخدام suspendCoroutine لتحويل الـ Callback إلى نتيجة مباشرة
                suspendCoroutine<Boolean> { continuation ->
                    
                    LlamaNative.loadModelWithProgress(modelPath, object : LlamaNative.ProgressCallback {
                        override fun onProgress(percent: Int) {
                            // تأمين تحديث الواجهة على الخيط الرئيسي
                            CoroutineScope(Dispatchers.Main).launch {
                                onProgress(percent)
                            }
                        }
                    })
                    
                    // بعد انتهاء الدالة الناتيف من العمل بالكامل
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
