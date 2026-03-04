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
     * تنفيذ الأهداف القادمة من الدماغ عبر الـ Accessibility
     */
    private val executor = object : GoalExecutor() {
        override suspend fun executeGoal(goal: AgentGoal): Boolean {
            Log.d("AIRI_ACC", "📥 Brain Goal Received: ${goal.description}")
            return executeAutonomousGoal(goal)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // تمرير الـ executor الداخلي للمستودع المركزي
        ScreenContextHolder.serviceInstance = executor 
        Log.d("AIRI_ACC", "✅ Service Connected: Internal Executor Ready")
    }

    // --- 🧠 المحرك التنفيذي التسلسلي ---

    private suspend fun executeAutonomousGoal(goal: AgentGoal): Boolean = withContext(Dispatchers.Main) {
        Log.d("AIRI_AGENT", "🚀 Starting Execution Loop for Goal: ${goal.id}")

        for ((index, step) in goal.steps.withIndex()) {
            Log.d("AIRI_AGENT", "Executing Step ${index + 1}/${goal.steps.size}")

            val stepSuccess = when (step) {
                is PlanStep.Click -> {
                    performClickByText(step.text)
                }

                is PlanStep.Scroll -> {
                    performScrollForward()
                }

                is PlanStep.Wait -> {
                    // استخدام delay للحفاظ على استجابة الخدمة وعدم تجميد الـ UI thread
                    delay(step.millis)
                    true
                }
            }

            if (!stepSuccess) {
                Log.e("AIRI_AGENT", "❌ Step Failed. Aborting entire goal.")
                return@withContext false
            }

            // وقت استقرار الواجهة (UI Settling Time)
            delay(500)
        }

        Log.d("AIRI_AGENT", "✅ All steps completed successfully.")
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

    // --- 📡 إدارة السياق والبيانات ---

    /**
     * ✅ تم التغيير إلى Public: استخراج النصوص من الشاشة الحالية
     */
    fun extractScreenContext(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            // استخراج النصوص ووصف المحتوى (Content Description)
            n.text?.let { sb.append(it).append(" ") }
            n.contentDescription?.let { sb.append(it).append(" ") }
            
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
        // تحديث السياق المخزن عند حدوث تغييرات في الشاشة
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
