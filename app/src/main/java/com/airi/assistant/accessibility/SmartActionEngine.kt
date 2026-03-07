package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.airi.assistant.brain.UIMemory

object SmartActionEngine {

    private const val TAG = "AIRI_SMART"

    /**
     * محرك الضغط الذكي: يحاول الوصول للهدف عبر 4 مراحل متقدمة.
     */
    fun smartClick(service: AccessibilityService, label: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val target = label.lowercase().trim()

        // 1️⃣ المرحلة الأولى: محاولة استدعاء "البصمة" من الذاكرة (Memory)
        val memoryId = UIMemory.recallNode(service, target)
        if (memoryId != null) {
            val node = findByViewId(root, memoryId)
            if (node != null && performClick(node)) {
                Log.i(TAG, "✅ Clicked using memory: $target")
                return true
            }
        }

        // 2️⃣ المرحلة الثانية: بحث مباشر بالنص الظاهر (Text Search)
        val textNodes = root.findAccessibilityNodeInfosByText(label)
        for (node in textNodes) {
            if (performClick(node)) {
                Log.i(TAG, "✅ Clicked using text: $target")
                return true
            }
        }

        // 3️⃣ المرحلة الثالثة: بحث دلالي عميق في شجرة العناصر (Semantic Search)
        val semanticNode = findSemantic(root, target)
        if (semanticNode != null && performClick(semanticNode)) {
            Log.i(TAG, "✅ Clicked using semantic search: $target")
            return true
        }

        // 4️⃣ المرحلة الرابعة: التنبؤ بالنية (Intent Prediction) 🚀
        // في حال فشل كل ما سبق، نطلب من المتنبئ إيجاد العقدة الأكثر احتمالية
        val predicted = IntentPredictor.predictNode(root, target)
        if (predicted != null && performClick(predicted)) {
            Log.i(TAG, "🚀 Clicked using intent prediction: $target")
            return true
        }

        Log.w(TAG, "❌ Target not found after 4 stages: $target")
        return false
    }

    /**
     * البحث الدلالي: يطابق الهدف مع النص، الوصف، أو الـ ID
     */
    private fun findSemantic(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        if (text.contains(target) || desc.contains(target) || id.contains(target)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findSemantic(child, target)
            if (result != null) return result
        }
        return null
    }

    /**
     * البحث عن طريق المعرف البرمجي (View ID)
     */
    private fun findByViewId(node: AccessibilityNodeInfo?, id: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName == id) return node

        for (i in 0 until node.childCount) {
            val result = findByViewId(node.getChild(i), id)
            if (result != null) return result
        }
        return null
    }

    /**
     * تنفيذ الضغط الفعلي مع دعم الزحف للأبناء (Parent Crawling)
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return false
    }
}
