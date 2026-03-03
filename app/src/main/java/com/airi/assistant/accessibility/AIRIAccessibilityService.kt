package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

// استيراد المكونات من مكتبة core ومن حزم المساعد
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.TaskChainer
import com.airi.core.chain.RetryPolicy        // 🔥 إضافة الاستيراد الجديد
import com.airi.core.chain.FailureStrategy    // 🔥 إضافة الاستيراد الجديد
import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.overlay.OverlayBridge
import com.airi.assistant.adaptive.AdaptiveDecisionEngine
import com.airi.assistant.adaptive.InteractionTracker

class AIRIAccessibilityService : AccessibilityService() {

    // إدارة العمليات غير المتزامنة على الخيط الرئيسي لضمان سلامة الوصول لعناصر الواجهة
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        // ربط الخدمة بالمخزن السحابي للسياق لتمكين الوصول إليها من أي مكان
        ScreenContextHolder.serviceInstance = this
        Log.d("AIRI_ACC", "Service Connected & Linked to Holder")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // مراقبة التغيرات الهيكلية في واجهة المستخدم
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val root = rootInActiveWindow ?: return
            val screenText = extractText(root)
            if (screenText.isBlank()) return

            val sourceApp = event.packageName?.toString() ?: "unknown"
            ScreenContextHolder.lastScreenText = screenText

            // معالجة النية والسياق في الخلفية لمنع تعليق الواجهة
            serviceScope.launch(Dispatchers.Default) {
                val detectedIntent = IntentDetector.detectIntent(screenText)

                ContextEngine.saveContext(
                    screenText = screenText,
                    sourceApp = sourceApp,
                    detectedIntent = detectedIntent
                )

                // منطق عرض الاقتراحات التكيفي
                if (AdaptiveDecisionEngine.shouldDisplay(sourceApp, detectedIntent)) {
                    val suggestions = SuggestionEngine.generateSuggestions(screenText)

                    if (suggestions.isNotEmpty()) {
                        InteractionTracker.recordShown(sourceApp, detectedIntent)

                        withContext(Dispatchers.Main) {
                            OverlayBridge.showSuggestion(
                                suggestions.first(),
                                screenText
                            )
                        }
                    }
                }
            }
        }
    }

   fun executeAutonomousGoal(goal: AgentGoal) {
    serviceScope.launch {

        val chainer = TaskChainer(
            retryPolicy = RetryPolicy(
                maxAttempts = 3,
                delayBetweenAttempts = 600
            )
        )

        chainer.addGoal(goal)

        chainer.execute(
            executor = { executingGoal, strategy ->

                when (strategy) {

                    AdaptiveStrategy.DirectAction -> {
                        Log.d("AIRI_AGENT", "DirectAction: ${executingGoal.id}")
                    }

                    AdaptiveStrategy.ScrollAndRetry -> {
                        Log.d("AIRI_AGENT", "ScrollAndRetry: ${executingGoal.id}")
                    }

                    AdaptiveStrategy.WaitAndRecheck -> {
                        Log.d("AIRI_AGENT", "WaitAndRecheck: ${executingGoal.id}")
                    }

                    AdaptiveStrategy.FallbackPath -> {
                        Log.d("AIRI_AGENT", "FallbackPath: ${executingGoal.id}")
                    }
                }
            },
            contextProvider = {
                extractScreenContext()
            },
            verifierProvider = { verifyingGoal ->
                suspend {
                    val context = extractScreenContext()
                    context.contains(verifyingGoal.description, ignoreCase = true)
                }
            },
            failureStrategyProvider = { FailureStrategy.ABORT }
        )
    }
   } 
    
    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return ScreenContextHolder.lastScreenText
        return extractText(root)
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val builder = StringBuilder()

        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { builder.append(it).append(" ") }
            n.contentDescription?.let { builder.append(it).append(" ") }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                traverse(child)
                child?.recycle()
            }
        }

        traverse(node)
        return builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(2000)
    }

    override fun onInterrupt() {
        Log.e("AIRI_ACC", "Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenContextHolder.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // إلغاء كافة العمليات لضمان عدم حدوث تسريب للذاكرة
        serviceScope.cancel()
    }
}
