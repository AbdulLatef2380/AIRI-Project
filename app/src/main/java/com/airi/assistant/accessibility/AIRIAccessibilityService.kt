package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

// استيراد المكونات الأساسية و الـ PlanStep الجديد
import com.airi.core.chain.AdaptiveStrategy
import com.airi.core.chain.FailureStrategy
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.TaskChainer
import com.airi.core.chain.RetryPolicy
import com.airi.core.chain.PlanStep // 🔥 الاستيراد الجديد لدعم الخطوات
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
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (success) {
                        Log.d("AIRI_ACC", "Successfully clicked on: $text")
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

    // --- 🧠 محرك التخطيط والتنفيذ (Planner & Executor) ---

    fun executeAutonomousGoal(goal: AgentGoal) {
        serviceScope.launch {
            Log.d("AIRI_AGENT", "🚀 Executing Plan: ${goal.id} - ${goal.description}")

            val chainer = TaskChainer(
                retryPolicy = RetryPolicy(
                    maxAttempts = 3,
                    delayBetweenAttempts = 600
                )
            )

            chainer.addGoal(goal)

            chainer.execute(
                executor = { executingGoal, strategy ->
                    // 🔥 تنفيذ الخطوات التسلسلية داخل الهدف
                    for (step in executingGoal.steps) {
                        Log.d("AIRI_AGENT", "Processing Step: $step")
                        
                        when (step) {
                            is PlanStep.Click -> {
                                val success = performClickByText(step.text)
                                Log.d("AIRI_AGENT", "Click '${step.text}' result: $success")
                            }

                            is PlanStep.ScrollForward -> {
                                performScrollForward()
                                delay(400) // انتظار استقرار الواجهة بعد السكرول
                            }

                            is PlanStep.WaitFor -> {
                                val start = System.currentTimeMillis()
                                Log.d("AIRI_AGENT", "Waiting for: ${step.text}")
                                while (System.currentTimeMillis() - start < step.timeout) {
                                    if (extractScreenContext().contains(step.text, ignoreCase = true)) {
                                        Log.d("AIRI_AGENT", "Found element: ${step.text}")
                                        break
                                    }
                                    delay(200)
                                }
                            }
                        }
                    }
                    
                    // هنا يمكن دمج الـ strategy لاحقاً لتعديل سلوك الخطوات عند الفشل
                },
                contextProvider = {
                    extractScreenContext()
                },
                verifierProvider = { verifyingGoal ->
                    suspend {
                        // التحقق النهائي بعد تنفيذ كافة الخطوات
                        val context = extractScreenContext()
                        val isSuccess = context.contains(verifyingGoal.description, ignoreCase = true)
                        Log.d("AIRI_VERIFIER", "Final goal verification: $isSuccess")
                        isSuccess
                    }
                },
                failureStrategyProvider = { FailureStrategy.ABORT }
            )
        }
    }

    // --- 📡 تحليل السياق وأحداث النظام ---

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
