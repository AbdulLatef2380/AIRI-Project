package com.airi.core.model // تأكد أن هذا هو الباكج الصحيح للمجلد الحالي

// أضف هذا السطر تحديداً (استيراد LlamaNative)
import com.airi.assistant.LlamaNative 
import kotlinx.coroutines.*

object ModelManager {
    private var isLoaded = false

    fun isModelLoaded(): Boolean = isLoaded

    suspend fun loadModel(
        modelPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // محاكاة الـ Progress
                for (i in 1..100 step 10) {
                    delay(100)
                    withContext(Dispatchers.Main) { onProgress(i) }
                }

                // هنا كان الخطأ - الآن بعد الـ Import سيعمل
                LlamaNative.loadModel(modelPath)
                
                isLoaded = true
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
