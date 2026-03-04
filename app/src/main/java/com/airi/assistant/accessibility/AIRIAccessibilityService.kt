package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.*
import com.airi.assistant.brain.AgentGoal
import com.airi.assistant.brain.PlanStep
import com.airi.assistant.brain.GoalExecutor

class AIRIAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 🏗️ المنفذ الداخلي (Internal Executor)
     * هذا الكائن هو الذي سيتم تمريره للدماغ للتحكم في المتصفح/التطبيقات
     */
    private val executor = object : GoalExecutor() {
        override suspend fun executeGoal(goal: AgentGoal): Boolean {
            Log.d("AIRI_ACC", "📥 Brain Goal Received: ${goal.description}")
            return executeAutonomousGoal(goal)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // ربط النسخة الحالية بالـ Holder ليتمكن الـ OverlayService من الوصول للـ executor
        ScreenContextHolder.serviceInstance = executor 
        Log.d("AIRI_ACC", "✅ Service Connected: Internal Executor Ready")
    }

    // --- 🧠 المحرك التنفيذي التسلسلي ---

    private suspend fun executeAutonomousGoal(goal: AgentGoal): Boolean = withContext(Dispatchers.Main) {
        Log.d("AIRI_AGENT", "🚀 Starting Execution Loop for Goal: ${goal.id}")

        for ((index, step) in goal.steps.withIndex()) {
            Log.d("AIRI_AGENT", "Executing Step ${index + 1}/${goal.steps.size}: $step")

            val stepSuccess = when (step) {
                is PlanStep.Click -> performClickByText(step.text)
                is PlanStep.ScrollForward -> performScrollForward()
                is PlanStep.WaitFor -> waitForElement(step.text, step.timeout)
                // إضافة تنفيذ الحالات الجديدة إذا وجدت في الـ Brain
                is PlanStep.Wait -> {
                    delay(2000) // تنفيذ Wait افتراضي
                    true
                }
                else -> false
            }

            if (!stepSuccess) {
                Log.e("AIRI_AGENT", "❌ Step Failed: $step. Aborting.")
                return@withContext false
            }
            delay(600) // استقرار الواجهة
        }
        return@withContext true
    }

    // --- 🛠️ دوال الأفعال الفيزيائية (Accessibility Actions) ---

    private fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        var isClicked = false

        nodes?.forEach { node ->
            if (!isClicked) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) {
                        isClicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (isClicked) break
                    }
                    current = current.parent
                }
            }
            node.recycle()
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
            if (extractScreenContext().contains(text, ignoreCase = true)) return true
            delay(300)
        }
        return false
    }

    // --- 📡 تحليل السياق ---

    private fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append(" ") }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                traverse(child)
                child?.recycle()
            }
        }
        
        traverse(root)
        root.recycle()
        return sb.toString().trim()
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
        serviceScope.cancel()
        ScreenContextHolder.serviceInstance = null
    }
}
