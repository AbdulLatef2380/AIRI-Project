package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.*
import com.airi.assistant.brain.AgentGoal
import com.airi.assistant.brain.PlanStep

class AIRIAccessibilityService : AccessibilityService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Main + job

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
        Log.d("AIRI_ACC", "✅ Service Connected")
    }

    // ==============================
    // 🧠 Goal Execution Entry Point
    // ==============================

    suspend fun executeGoal(goal: AgentGoal): Boolean {
        Log.d("AIRI_AGENT", "🚀 Starting Goal: ${goal.description}")

        for ((index, step) in goal.steps.withIndex()) {

            Log.d("AIRI_AGENT", "Executing Step ${index + 1}/${goal.steps.size}")

            val stepSuccess = when (step) {

                is PlanStep.Click -> {
                    performClickByText(step.target)
                }

                is PlanStep.Scroll -> {
                    performScrollForward()
                }

                is PlanStep.Wait -> {
                    delay(step.millis)
                    true
                }
            }

            if (!stepSuccess) {
                Log.e("AIRI_AGENT", "❌ Step Failed. Aborting Goal.")
                return false
            }

            delay(500) // UI settling
        }

        Log.d("AIRI_AGENT", "✅ Goal Completed Successfully")
        return true
    }

    // ==============================
    // 🛠 Accessibility Actions
    // ==============================

    private fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)

        var clicked = false

        nodes?.forEach { node ->
            if (!clicked) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
                        clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) break
                    }
                    current = current.parent
                }
            }
            node.recycle()
        }

        root.recycle()
        return clicked
    }

    private fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val success = findScrollableAndScroll(root)
        root.recycle()
        return success
    }

    private fun findScrollableAndScroll(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findScrollableAndScroll(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    // ==============================
    // 📡 Screen Context Extraction
    // ==============================

    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return ""

        val builder = StringBuilder()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            node.text?.let { builder.append(it).append(" ") }
            node.contentDescription?.let { builder.append(it).append(" ") }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                traverse(child)
                child?.recycle()
            }
        }

        traverse(root)
        root.recycle()

        return builder.toString().trim()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            ScreenContextHolder.lastScreenText = extractScreenContext()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        ScreenContextHolder.serviceInstance = null
    }
}
