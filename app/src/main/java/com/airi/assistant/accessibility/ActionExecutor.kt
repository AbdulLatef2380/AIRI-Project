package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

object ActionExecutor {

    fun clickByText(service: AccessibilityService, text: String): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {

            if (node.isClickable) {

                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }

            var parent = node.parent

            while (parent != null) {

                if (parent.isClickable) {

                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }

                parent = parent.parent
            }
        }

        return false
    }

    /**
     * ✅ النسخة الأفضل والمحدثة: الكتابة في أي حقل Editable دون الاعتماد على ID
     */
    fun inputText(service: AccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false

        // استخدام الدالة الجديدة للبحث عن حقل يقبل الكتابة
        val editableNode = findEditableNode(root) ?: return false

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        return editableNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
    }

    /**
     * 🧠 البحث الذكي المتكرر عن أول عقدة تقبل الكتابة في الشجرة
     */
    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findEditableNode(child)
            if (result != null) return result
        }

        return null
    }

    /**
     * تنفيذ أمر الرجوع للخلف
     */
    fun pressBack(service: AccessibilityService) {
        service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
    }
}
