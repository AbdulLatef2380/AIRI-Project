package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.overlay.OverlayBridge
import com.airi.assistant.adaptive.AdaptiveDecisionEngine
import com.airi.assistant.adaptive.InteractionTracker

class AIRIAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // ربط الخدمة بـ Holder للوصول إليها من أماكن أخرى
        // ملاحظة: تأكد من وجود كلاس ScreenContextHolder في مشروعك
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // مراقبة التغيرات في محتوى الشاشة أو النوافذ
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val root = rootInActiveWindow ?: return
            val screenText = extractText(root)

            if (screenText.isBlank()) return

            val sourceApp = event.packageName?.toString() ?: "unknown"

            serviceScope.launch {
                // 🔥 1️⃣ تحليل النية (Intent Detection)
                val detectedIntent = IntentDetector.detectIntent(screenText)

                // 🔥 2️⃣ حفظ السياق في قاعدة البيانات (Context Persistence)
                ContextEngine.saveContext(
                    screenText = screenText,
                    sourceApp = sourceApp,
                    detectedIntent = detectedIntent
                )

                // 🔥 3️⃣ استشارة محرك التكيف (Adaptive Decision Engine)
                // هل نُظهر الاقتراح أم نصمت بناءً على تاريخ تفاعل المستخدم؟
                if (AdaptiveDecisionEngine.shouldDisplay(sourceApp, detectedIntent)) {
                    
                    // توليد الاقتراحات (بناءً على النص الحالي)
                    val suggestions = SuggestionEngine.generateSuggestions(screenText)

                    if (suggestions.isNotEmpty()) {
                        // تسجيل أننا عرضنا اقتراحاً لهذا التطبيق وهذه النية
                        InteractionTracker.recordShown(sourceApp, detectedIntent)

                        // إظهار الواجهة للمستخدم
                        withContext(Dispatchers.Main) {
                            OverlayBridge.showSuggestion(
                                suggestions.first(),
                                screenText
                            )
                        }
                    }
                } else {
                    Log.d("AIRI_ADAPTIVE", "Suppressed: Score too low for $sourceApp | $detectedIntent")
                }

                Log.d("AIRI_CONTEXT", "Intent: $detectedIntent | App: $sourceApp")
            }
        }
    }

    /**
     * استخراج النص من الشاشة بشكل هيكلي
     */
    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val builder = StringBuilder()

        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return

            // جمع النصوص ووصف المحتوى (Content Description)
            n.text?.let { builder.append(it).append(" ") }
            n.contentDescription?.let { builder.append(it).append(" ") }

            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                traverse(child)
                child?.recycle() // تحرير الذاكرة
            }
        }

        traverse(node)

        return builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(2000) // الحد الأقصى للنص لتوفير الذاكرة
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
