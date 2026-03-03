package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

// استيراد المكونات من مكتبة core ومن حزم المساعد
import com.airi.core.chain.AdaptiveStrategy
import com.airi.core.chain.FailureStrategy
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.TaskChainer
import com.airi.core.chain.RetryPolicy        
import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.overlay.OverlayBridge
import com.airi.assistant.adaptive.AdaptiveDecisionEngine
import com.airi.assistant.adaptive.InteractionTracker

class AIRIAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
        Log.d("AIRI_ACC", "Service Connected & Linked to Holder")
    }

    // --- 🛠️ دوال التنفيذ الفعلي (Physical Actions) ---

    private fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return false

        for (node in nodes) {
            // محاولة الضغط على العنصر نفسه أو البحث في الآباء إذا لم يكن قابلاً للضغط
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) {
                        node.recycle()
                        return true
                    }
                }
                current = current.parent
            }
            node.recycle()
        }
        return false
    }

    private fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        // محاولة البحث عن عنصر قابل للتمرير في الشجرة
        fun findAndScroll(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findAndScroll(child)) return true
            }
            return false
        }
        return findAndScroll(root)
    }

    // --- 🧠 محرك التنفيذ الذاتي (Autonomous Engine) ---

    fun executeAutonomousGoal(goal: AgentGoal) {
        serviceScope.launch {
            Log.d("AIRI_AGENT", "🚀 Starting Autonomous Task: ${goal.id}")

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
                            val success = performClickByText(executingGoal.description)
                            Log.d("AIRI_AGENT", "DirectAction (Click) result: $success for ${executingGoal.id}")
                        }

                        AdaptiveStrategy.ScrollAndRetry -> {
                            val success = performScrollForward()
                            Log.d("AIRI_AGENT", "ScrollAndRetry result: $success")
                            delay(400) // انتظار استقرار الواجهة بعد السكرول
                        }

                        AdaptiveStrategy.WaitAndRecheck -> {
                            Log.d("AIRI_AGENT", "WaitAndRecheck: Waiting 800ms...")
                            delay(800)
                        }

                        AdaptiveStrategy.FallbackPath -> {
                            Log.d("AIRI_AGENT", "FallbackPath triggered - Logging only for now")
                        }
                    }
                },
                contextProvider = {
                    extractScreenContext()
                },
                verifierProvider = { verifyingGoal ->
                    suspend {
                        val context = extractScreenContext()
                        val isSuccess = context.contains(verifyingGoal.description, ignoreCase = true)
                        Log.d("AIRI_VERIFIER", "Verified ${verifyingGoal.id}: $isSuccess")
                        isSuccess
                    }
                },
                failureStrategyProvider = { FailureStrategy.ABORT }
            )
        }
    }

    // --- 📡 معالجة أحداث النظام وتحليل السياق ---

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val root = rootInActiveWindow ?: return
            val screenText = extractText(root)
            if (screenText.isBlank()) return

            val sourceApp = event.packageName?.toString() ?: "unknown"
            ScreenContextHolder.lastScreenText = screenText

            serviceScope.launch(Dispatchers.Default) {
                val detectedIntent = IntentDetector.detectIntent(screenText)

                ContextEngine.saveContext(
                    screenText = screenText,
                    sourceApp = sourceApp,
                    detectedIntent = detectedIntent
                )

                if (AdaptiveDecisionEngine.shouldDisplay(sourceApp, detectedIntent)) {
                    val suggestions = SuggestionEngine.generateSuggestions(screenText)

                    if (suggestions.isNotEmpty()) {
                        InteractionTracker.recordShown(sourceApp, detectedIntent)

                        withContext(Dispatchers.Main) {
                            OverlayBridge.showSuggestion(suggestions.first(), screenText)
                        }
                    }
                }
            }
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
        return builder.toString().replace(Regex("\\s+"), " ").trim().take(2000)
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
        serviceScope.cancel()
    }
}
