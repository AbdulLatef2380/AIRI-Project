package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.airi.assistant.brain.UIMemory

object SmartActionEngine {

    private const val TAG = "AIRI_SMART"

    fun smartClick(service: AccessibilityService, label: String): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val target = label.lowercase().trim()

        // 1️⃣ محاولة باستخدام الذاكرة
        val memoryId = UIMemory.recallNode(service, target)

        if (memoryId != null) {

            val node = findByViewId(root, memoryId)

            if (node != null) {

                if (performClick(node)) {

                    Log.i(TAG, "Clicked using memory: $target")
                    return true
                }
            }
        }

        // 2️⃣ بحث بالنص
        val textNodes = root.findAccessibilityNodeInfosByText(label)

        for (node in textNodes) {

            if (performClick(node)) {

                Log.i(TAG, "Clicked using text: $target")
                return true
            }
        }

        // 3️⃣ بحث دلالي عبر الشجرة
        val semanticNode = findSemantic(root, target)

        if (semanticNode != null) {

            if (performClick(semanticNode)) {

                Log.i(TAG, "Clicked using semantic search: $target")
                return true
            }
        }

        Log.w(TAG, "Target not found: $target")

        return false
    }

    private fun findSemantic(
        node: AccessibilityNodeInfo?,
        target: String
    ): AccessibilityNodeInfo? {

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

    private fun findByViewId(
        node: AccessibilityNodeInfo?,
        id: String
    ): AccessibilityNodeInfo? {

        if (node == null) return null

        val viewId = node.viewIdResourceName ?: ""

        if (viewId == id) {
            return node
        }

        for (i in 0 until node.childCount) {

            val result = findByViewId(node.getChild(i), id)

            if (result != null) return result
        }

        return null
    }

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
