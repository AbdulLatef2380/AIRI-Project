package com.airi.assistant.agent.node

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

object NodeActionExecutor {

    fun typeText(
        node: AccessibilityNodeInfo,
        text: String
    ): Boolean {

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        return node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
