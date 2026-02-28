package com.airi.core.model

// استيراد LlamaNative من مساره في المجلد الرئيسي
import com.airi.assistant.LlamaNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * مدير دورة حياة النموذج (Model Lifecycle Management)
 * مسؤول عن التحميل، مراقبة التقدم، وحماية النظام من الاستخدام قبل الجاهزية.
 */
object ModelManager {

    private var isLoaded = false

    /**
     * التحقق مما إذا كان "العقل" جاهزاً للعمل
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * دالة تحميل النموذج
     * @param modelPath المسار الكامل لملف الـ GGUF
     * @param onProgress Callback لتحديث شريط التقدم في الواجهة
     */
    suspend fun loadModel(
        modelPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. محاكاة تقدم التحميل (مؤقتاً حتى نربط Progress حقيقي من JNI)
                for (i in 1..100 step 10) {
                    delay(50) // تأخير بسيط لمحاكاة القراءة من الذاكرة
                    withContext(Dispatchers.Main) {
                        onProgress(i)
                    }
                }

                // 2. استدعاء المحرك الناتيف لتحميل الملف فعلياً في الذاكرة RAM
                // ملاحظة: LlamaNative هنا هو Singleton Object
                val result = LlamaNative.loadModel(modelPath)
                
                // يمكنك هنا فحص الـ result إذا كان الناتيف يرسل رسالة خطأ معينة
                
                isLoaded = true
                true
            } catch (e: Exception) {
                isLoaded = false
                false
            }
        }
    }
}
