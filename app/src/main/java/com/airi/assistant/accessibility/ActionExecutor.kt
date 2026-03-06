package com.airi.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

object ActionExecutor {

    fun clickByText(service: AccessibilityService, text: String): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {

            var current: AccessibilityNodeInfo? = node

            while (current != null) {

                if (current.isClickable) {

                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true

                }

                current = current.parent
            }
        }

        return false
    }

    fun inputText(service: AccessibilityService, text: String): Boolean {

        val root = service.rootInActiveWindow ?: return false

        val nodes = root.findAccessibilityNodeInfosByViewId("android:id/edit")

        for (node in nodes) {

            if (node.isEditable) {

                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )

                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    arguments
                )

                return true
            }
        }

        return false
    }

    fun pressBack(service: AccessibilityService) {

        service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
    }

}
