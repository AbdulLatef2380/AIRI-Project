package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

import com.airi.core.chain.*
import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.brain.GoalExecutor

class AIRIAccessibilityService : AccessibilityService(), GoalExecutor {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
        Log.d("AIRI_ACC", "Service Connected as GoalExecutor")
    }

    // --- 🏗️ تنفيذ واجهة GoalExecutor ---

    override suspend fun executeGoal(goal: AgentGoal): Boolean {
        Log.d("AIRI_ACC", "📥 Brain Goal Received: ${goal.description}")
        return executeAutonomousGoal(goal)
    }

    // --- 🧠 المحرك التنفيذي التسلسلي (Professional Logic) ---

    private suspend fun executeAutonomousGoal(goal: AgentGoal): Boolean = withContext(Dispatchers.Main) {
        Log.d("AIRI_AGENT", "🚀 Starting Execution Loop for Goal: ${goal.id}")

        for ((index, step) in goal.steps.withIndex()) {
            Log.d("AIRI_AGENT", "Executing Step ${index + 1}/${goal.steps.size}: $step")

            // تنفيذ الخطوة الحالية
            val stepSuccess = executeStep(step)

            if (!stepSuccess) {
                Log.e("AIRI_AGENT", "❌ Step Failed: $step. Aborting entire goal.")
                return@withContext false // فشل الخطة كاملة
            }

            // انتظار بسيط لاستقرار الواجهة (UI Settling Time)
            delay(600)
        }

        Log.d("AIRI_AGENT", "✅ All steps completed successfully for: ${goal.description}")
        return@withContext true
    }

    /**
     * مُوزع المهام (Step Dispatcher)
     */
    private suspend fun executeStep(step: PlanStep): Boolean {
        return when (step) {
            is PlanStep.Click -> performClickByText(step.text)
            is PlanStep.ScrollForward -> performScrollForward()
            is PlanStep.WaitFor -> waitForElement(step.text, step.timeout)
            else -> false
        }
    }

    // --- 🛠️ دوال الأفعال الفيزيائية (Physical Actions) ---

    private fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        var isClicked = false

        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (!isClicked) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null) {
                        if (current.isClickable) {
                            isClicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (isClicked) {
                                Log.d("AIRI_ACC", "Click Success on: $text")
                                break
                            }
                        }
                        val parent = current.parent
                        if (current != node) current.recycle()
                        current = parent
                    }
                }
                node.recycle()
            }
        }
        root.recycle()
        return isClicked
    }

    private fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val success = findAndScrollRecursive(root)
        root.recycle()
        return success
    }

    private fun findAndScrollRecursive(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
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

    private suspend fun waitForElement(text: String, timeout: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            if (extractScreenContext().contains(text, ignoreCase = true)) {
                Log.d("AIRI_ACC", "Element found: $text")
                return true
            }
            delay(300)
        }
        return false
    }

    // --- 📡 تحليل السياق واستخراج النصوص ---

    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return ScreenContextHolder.lastScreenText
        val text = extractText(root)
        root.recycle()
        return text
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append(" ") }
            n.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                traverse(child)
                child?.recycle()
            }
        }
        traverse(node)
        return sb.toString().replace(Regex("\\s+"), " ").trim().take(2000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // إدارة السياق التلقائي
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val root = rootInActiveWindow ?: return
            ScreenContextHolder.lastScreenText = extractText(root)
            root.recycle()
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
