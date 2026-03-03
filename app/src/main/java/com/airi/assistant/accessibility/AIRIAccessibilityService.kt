package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

// استيراد المكونات الأساسية
import com.airi.core.chain.AdaptiveStrategy
import com.airi.core.chain.FailureStrategy
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.TaskChainer
import com.airi.core.chain.RetryPolicy
import com.airi.core.chain.PlanStep
import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.overlay.OverlayBridge
import com.airi.assistant.adaptive.AdaptiveDecisionEngine
import com.airi.assistant.adaptive.InteractionTracker
import com.airi.assistant.brain.GoalExecutor // 🔥 الربط مع واجهة الدماغ

class AIRIAccessibilityService : AccessibilityService(), GoalExecutor {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
        Log.d("AIRI_ACC", "Service Connected & Linked to Holder as GoalExecutor")
    }

    // --- 🏗️ تنفيذ واجهة GoalExecutor ---

    override fun executeGoal(goal: AgentGoal) {
        Log.d("AIRI_ACC", "📥 Goal received for execution: ${goal.id}")
        executeAutonomousGoal(goal)
    }

    // --- 🛠️ دوال التنفيذ الفيزيائي (Physical Actions) مع إدارة الذاكرة ---

    private fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        var clicked = false

        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (!clicked) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (clicked) {
                                Log.d("AIRI_ACC", "Successfully clicked: $text")
                                break
                            }
                        }
                        val parent = current.parent
                        // لا نحتاج لعمل recycle لـ node هنا لأننا سنقوم بذلك في نهاية الحلقة
                        if (current != node) current.recycle() 
                        current = parent
                    }
                }
                node.recycle()
            }
        }
        root.recycle()
        return clicked
    }

    private fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val success = findAndScrollRecursive(root)
        root.recycle()
        return success
    }

    private fun findAndScrollRecursive(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndScrollRecursive(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // --- 🧠 المحرك التنفيذي التكيفي (Adaptive Executor) ---

    fun executeAutonomousGoal(goal: AgentGoal) {
        serviceScope.launch {
            Log.d("AIRI_AGENT", "🚀 Starting Execution: ${goal.description}")

            val chainer = TaskChainer(
                retryPolicy = RetryPolicy(maxAttempts = 3, delayBetweenAttempts = 800)
            )
            chainer.addGoal(goal)

            chainer.execute(
                executor = { executingGoal, strategy ->
                    var allStepsSucceeded = true

                    for (step in executingGoal.steps) {
                        Log.d("AIRI_AGENT", "Processing Step: $step | Strategy: $strategy")

                        val success = when (step) {
                            is PlanStep.Click -> {
                                // إذا كانت الاستراتيجية "تمرير ثم محاولة"، نفذ السكرول أولاً
                                if (strategy == AdaptiveStrategy.ScrollAndRetry) {
                                    performScrollForward()
                                    delay(500)
                                }
                                performClickByText(step.text)
                            }

                            is PlanStep.ScrollForward -> performScrollForward()

                            is PlanStep.WaitFor -> {
                                // في استراتيجية "انتظار إضافي"، نضاعف المهلة
                                val timeout = if (strategy == AdaptiveStrategy.WaitAndRecheck) 
                                    step.timeout * 2 else step.timeout
                                waitForElement(step.text, timeout)
                            }
                        }

                        if (!success) {
                            Log.w("AIRI_AGENT", "Step FAILED: $step. Aborting run for retry.")
                            allStepsSucceeded = false
                            break 
                        }
                        delay(400) // استقرار الواجهة بين الخطوات
                    }
                    allStepsSucceeded
                },
                contextProvider = { extractScreenContext() },
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

    private suspend fun waitForElement(text: String, timeout: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            if (extractScreenContext().contains(text, ignoreCase = true)) return true
            delay(250)
        }
        return false
    }

    // --- 📡 تحليل السياق وأحداث النظام ---

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            val root = rootInActiveWindow ?: return
            val screenText = extractText(root)
            root.recycle()

            if (screenText.isBlank()) return

            val sourceApp = event.packageName?.toString() ?: "unknown"
            ScreenContextHolder.lastScreenText = screenText

            serviceScope.launch(Dispatchers.Default) {
                val detectedIntent = IntentDetector.detectIntent(screenText)
                ContextEngine.saveContext(screenText, sourceApp, detectedIntent)

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
        val text = extractText(root)
        root.recycle()
        return text
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

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenContextHolder.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
