P com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.*

import com.airi.core.chain.*
import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.data.ContextEngine
import com.airi.assistant.overlay.OverlayBridge
import com.airi.assistant.adaptive.AdaptiveDecisionEngine
import com.airi.assistant.adaptive.InteractionTracker
import com.airi.assistant.brain.GoalExecutor

class AIRIAccessibilityService : AccessibilityService(), GoalExecutor {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenContextHolder.serviceInstance = this
        Log.d("AIRI_ACC", "Service Connected as GoalExecutor")
    }

    // --- 🏗️ تنفيذ واجهة GoalExecutor (الآن Suspend) ---

    override suspend fun executeGoal(goal: AgentGoal): Boolean {
        Log.d("AIRI_ACC", "📥 Brain Goal Received: ${goal.description}")
        return executeAutonomousGoal(goal)
    }

    // --- 🧠 المحرك التنفيذي (تعيد Boolean للدماغ) ---

    private suspend fun executeAutonomousGoal(goal: AgentGoal): Boolean = withContext(Dispatchers.Main) {
        Log.d("AIRI_AGENT", "🚀 Starting Autonomous Run: ${goal.id}")

        val chainer = TaskChainer(
            retryPolicy = RetryPolicy(maxAttempts = 3, delayBetweenAttempts = 1000)
        )
        chainer.addGoal(goal)

        // تنفيذ الخطة وانتظار النتيجة النهائية
        val finalResult = chainer.execute(
            executor = { executingGoal, strategy ->
                var success = true
                for (step in executingGoal.steps) {
                    val stepResult = when (step) {
                        is PlanStep.Click -> {
                            if (strategy == AdaptiveStrategy.ScrollAndRetry) {
                                performScrollForward()
                                delay(600)
                            }
                            performClickByText(step.text)
                        }
                        is PlanStep.ScrollForward -> performScrollForward()
                        is PlanStep.WaitFor -> waitForElement(step.text, step.timeout)
                    }

                    if (!stepResult) {
                        success = false
                        break
                    }
                    delay(500) // وقت استقرار الواجهة
                }
                success
            },
            contextProvider = { extractScreenContext() },
            verifierProvider = { verifyingGoal ->
                suspend {
                    // التحقق: هل الوصف موجود في السياق بعد التنفيذ؟
                    extractScreenContext().contains(verifyingGoal.description, ignoreCase = true)
                }
            },
            failureStrategyProvider = { FailureStrategy.RETRY }
        )

        Log.d("AIRI_AGENT", "🏁 Final Execution Success: $finalResult")
        return@withContext finalResult
    }

    // --- 🛠️ دوال الأفعال الفيزيائية (نفس الكود السابق مع تنظيف الذاكرة) ---

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
                            if (isClicked) break
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
            if (extractScreenContext().contains(text, ignoreCase = true)) return true
            delay(300)
        }
        return false
    }

    // --- 📡 تحليل السياق (نفس الكود السابق) ---

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
                val intent = IntentDetector.detectIntent(screenText)
                ContextEngine.saveContext(screenText, sourceApp, intent)
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

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
