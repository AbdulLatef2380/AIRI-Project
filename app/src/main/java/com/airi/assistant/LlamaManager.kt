package com.airi.assistant

import android.content.Context

/**
 * LlamaManager - يربط عملية تحميل الملفات بمحرك الذكاء الاصطناعي.
 * تم التحديث: لم يعد يستخدم Constructor لـ LlamaNative لأنه أصبح Object.
 */
class LlamaManager(private val context: Context) {

    // 1. قمنا بحذف السطر: private val native = LlamaNative(context)
    // لأنه لا يمكن استدعاء الـ Object كـ Constructor.
    
    private val downloader = ModelDownloadManager(context)

    fun initializeModel(onReady: () -> Unit) {

        if (!downloader.isModelDownloaded()) {
            throw IllegalStateException("Model not downloaded")
        }

        val modelPath = downloader.getModelFile().absolutePath
        
        // 2. الاستدعاء الآن يتم مباشرة عبر اسم الـ Object: LlamaNative
        LlamaNative.loadModel(modelPath)
        
        onReady()
    }
}
