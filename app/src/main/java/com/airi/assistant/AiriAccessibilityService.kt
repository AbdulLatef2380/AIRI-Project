package com.airi.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
// 🔥 تم استبدال الـ imports القديمة بالـ AgentGoal التابع لـ brain
import com.airi.assistant.brain.AgentGoal
import kotlinx.coroutines.*

/**
 * "العين الثالثة" لـ AIRI.
 * خدمة الوصول المسؤولة عن فهم سياق الشاشة بشكل غير متطفل.
 */
class AiriAccessibilityService : AccessibilityService(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext = Dispatchers.Main + job

    private lateinit var guardianEngine: GuardianEngine

    companion object {
        private var instance: AiriAccessibilityService? = null
        fun getInstance(): AiriAccessibilityService? {
            return instance
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // ملاحظة: تأكد من تحديث GuardianEngine ليتوافق مع السياق الجديد إذا لزم الأمر
        guardianEngine = GuardianEngine(this)
        Log.d("AIRI_VISION", "✅ العين الثالثة مفعلة وجاهزة.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        job.cancel()
    }

    override fun onServiceConnected() {
    super.onServiceConnected()
    ScreenContextHolder.service = this
    }
    
    private var lastScreenHash: Int = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingContextRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 1. حماية الخصوصية النشطة
        val packageName = event.packageName?.toString() ?: ""
        if (::guardianEngine.isInitialized && guardianEngine.analyzeAppBehavior(packageName, event.eventType)) {
            Log.w("AIRI_GUARDIAN", "⚠️ سلوك مشبوه تم اكتشافه في: $packageName")
        }

        // 2. تصفية الأحداث (Debounce) لمنع إرهاق المعالج
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            pendingContextRunnable?.let { handler.removeCallbacks(it) }
            pendingContextRunnable = Runnable {
                val currentContext = getScreenContext()
                val currentHash = currentContext.hashCode()
                
                if (currentHash != lastScreenHash) {
                    lastScreenHash = currentHash
                    // إرسال السياق للناقل المركزي (تأكد من توافق AiriCore مع BrainInput)
                    launch {
                        AiriCore.send(AiriCore.AiriEvent.ScreenContext(currentContext))
                    }
                }
            }
            handler.postDelayed(pendingContextRunnable!!, 1000) 
        }
    }

    override fun onInterrupt() {}

    /**
     * تحليل محتوى الشاشة الحالي وتحويله إلى وصف نصي
     */
    fun getScreenContext(): String {
        val rootNode = rootInActiveWindow ?: return "الشاشة غير متاحة حالياً."
        val contextBuilder = StringBuilder()
        
        contextBuilder.append("التطبيق الحالي: ${rootNode.packageName}\n")
        parseNodes(rootNode, contextBuilder)
        
        return contextBuilder.toString()
    }

    private fun parseNodes(node: AccessibilityNodeInfo, builder: StringBuilder) {
        if (node.isPassword) return // حماية كلمات المرور

        node.text?.let {
            if (it.isNotBlank()) {
                builder.append("- $it\n")
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { parseNodes(it, builder) }
        }
    }

    /**
     * دالة مساعدة لتنفيذ الأهداف القادمة من الدماغ (تستخدمها GoalExecutor)
     */
    fun performGoal(goal: AgentGoal): Boolean {
        Log.d("AIRI_VISION", "Executing goal via Accessibility: ${goal.description}")
        // هنا يتم وضع منطق محاكاة النقر أو التفاعل بناءً على خطوات الهدف
        return true 
    }
}
