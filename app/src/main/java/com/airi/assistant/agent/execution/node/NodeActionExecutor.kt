package com.airi.assistant.agent.execution.node

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object NodeActionExecutor {

    private const val TAG = "NodeActionExecutor"

    fun click(node: AccessibilityNodeInfo): Boolean {
        return try {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Click result: $result on '${node.text ?: node.contentDescription}'")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Click failed: ${e.message}")
            false
        }
    }

    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Type result: $result for text='$text'")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Type failed: ${e.message}")
            false
        }
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "Long click failed: ${e.message}")
            false
        }
    }

    fun focus(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } catch (e: Exception) {
            Log.e(TAG, "Focus failed: ${e.message}")
            false
        }
    }
}
