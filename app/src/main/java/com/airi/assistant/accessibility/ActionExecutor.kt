package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

object ActionExecutor {

    /**
     * الضغط على عنصر بناءً على النص الظاهر فيه (مع ميزة الزحف للأعلى للعثور على عنصر قابل للضغط)
     */
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
     * الضغط على أول عنصر قابل للضغط يتم العثور عليه في الشاشة
     */
    fun clickFirst(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = NodeFinder.findFirstClickable(root) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * الضغط على عنصر قابل للضغط بناءً على ترتيبه (Index) في القائمة المستخرجة
     */
    fun clickByIndex(service: AccessibilityService, index: Int): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = NodeFinder.findClickableByIndex(root, index) ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * الكتابة في أي حقل Editable يتم العثور عليه تلقائياً
     */
    fun inputText(service: AccessibilityService, text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
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
     * البحث المتكرر عن أول عقدة تقبل الكتابة
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
     * تنفيذ أمر "الرجوع" للنظام
     */
    fun pressBack(service: AccessibilityService) {
        service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
    }
}
