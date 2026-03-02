package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.TaskChainer
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

    /**
     * 🔥 Autonomous Execution Entry Point
     */
    fun executeAutonomousGoal(goal: AgentGoal) {
        serviceScope.launch {
            Log.d("AIRI_AGENT", "🚀 Starting Autonomous Task: ${goal.id}")

            val chainer = TaskChainer()
            chainer.addGoal(goal)

            chainer.execute { executingGoal ->
                Log.d("AIRI_AGENT", "⚙ Executing goal: ${executingGoal.id}")

                // 🔥 هنا مستقبلاً سنربط StateMachine + ExecutionEngine
                // حالياً مجرد logging لضمان البناء السليم
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
        return builder.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(2000)
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
