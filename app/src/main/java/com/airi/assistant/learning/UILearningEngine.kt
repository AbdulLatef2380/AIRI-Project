package com.airi.assistant.learning

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object UILearningEngine {

    private const val TAG = "AIRI_LEARN"

    fun learnScreen(
        context: Context,
        packageName: String,
        root: AccessibilityNodeInfo?
    ) {

        if (root == null) return

        val prefs = context.getSharedPreferences(
            "airi_ui_learning",
            Context.MODE_PRIVATE
        )

        val editor = prefs.edit()

        traverse(root, packageName, editor)

        editor.apply()
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        pkg: String,
        editor: android.content.SharedPreferences.Editor
    ) {

        if (node == null) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (node.isClickable) {

            val label = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                else -> id
            }

            if (label.isNotEmpty()) {

                val key = "$pkg:$label"

                editor.putString(key, id)

                Log.d(TAG, "Learned UI element $key -> $id")
            }
        }

        for (i in 0 until node.childCount) {

            traverse(
                node.getChild(i),
                pkg,
                editor
            )
        }
    }

    fun recallElement(
        context: Context,
        packageName: String,
        label: String
    ): String? {

        val prefs = context.getSharedPreferences(
            "airi_ui_learning",
            Context.MODE_PRIVATE
        )

        val key = "$packageName:$label"

        return prefs.getString(key, null)
    }
}
