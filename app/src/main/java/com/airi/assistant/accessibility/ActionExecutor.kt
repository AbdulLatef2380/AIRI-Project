package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

object ActionExecutor {

    private const val TAG = "AIRI_ACTION"

    /**
     * الضغط على عنصر عبر النص
     */
    fun clickByText(service: AccessibilityService, text: String): Boolean {

        val root = service.rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {

            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Clicked node by text: $text")
                return true
            }

            var parent = node.parent

            while (parent != null) {

                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked parent node for: $text")
                    return true
                }

                parent = parent.parent
            }
        }

        Log.w(TAG, "Node not found for text: $text")
        return false
    }

    /**
     * الضغط على أول عنصر قابل للضغط
     */
    fun clickFirst(service: AccessibilityService): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val node = findFirstClickable(root) ?: return false

        Log.i(TAG, "Clicked first clickable node")

        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * الضغط حسب الترتيب
     */
    fun clickByIndex(service: AccessibilityService, index: Int): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val list = mutableListOf<AccessibilityNodeInfo>()

        collectClickableNodes(root, list)

        if (index >= list.size) return false

        Log.i(TAG, "Clicked node index: $index")

        return list[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * إدخال نص
     */
    fun inputText(service: AccessibilityService, text: String): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val editableNode = findEditableNode(root) ?: run {
            Log.w(TAG, "Editable field not found")
            return false
        }

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        val result = editableNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )

        Log.i(TAG, "Text inserted: $text")

        return result
    }

    /**
     * البحث عن أول عنصر قابل للضغط
     */
    private fun findFirstClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {

        if (node == null) return null

        if (node.isClickable) return node

        for (i in 0 until node.childCount) {

            val result = findFirstClickable(node.getChild(i))

            if (result != null) return result
        }

        return null
    }

    /**
     * جمع كل العناصر القابلة للضغط
     */
    private fun collectClickableNodes(
        node: AccessibilityNodeInfo?,
        list: MutableList<AccessibilityNodeInfo>
    ) {

        if (node == null) return

        if (node.isClickable) {
            list.add(node)
        }

        for (i in 0 until node.childCount) {

            collectClickableNodes(node.getChild(i), list)
        }
    }

    /**
     * البحث عن حقل قابل للكتابة
     */
    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {

        if (node == null) return null

        if (node.isEditable) return node

        for (i in 0 until node.childCount) {

            val result = findEditableNode(node.getChild(i))

            if (result != null) return result
        }

        return null
    }

    /**
     * زر الرجوع
     */
    fun pressBack(service: AccessibilityService) {

        service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )

        Log.i(TAG, "Back pressed")
    }
}
