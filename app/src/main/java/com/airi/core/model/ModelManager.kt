package com.airi.core.model

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ModelManager {

    private var isLoaded = false
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isModelLoaded(): Boolean = isLoaded

    suspend fun loadModel(
        modelPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return withContext(modelScope.coroutineContext) {
            try {

                // 🔥 محاكاة تحميل (بديل مؤقت لـ JNI)
                for (i in 1..100) {
                    delay(30)
                    withContext(Dispatchers.Main) {
                        onProgress(i)
                    }
                }

                isLoaded = true
                true

            } catch (e: Exception) {
                isLoaded = false
                false
            }
        }
    }
}
