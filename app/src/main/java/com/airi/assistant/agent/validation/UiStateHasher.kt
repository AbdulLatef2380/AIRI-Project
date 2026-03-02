package com.airi.assistant.agent.validation

import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

object UiStateHasher {

    fun generateHash(root: AccessibilityNodeInfo?): String {
        if (root == null) return "null"

        val builder = StringBuilder()
        traverse(root, builder)

        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(builder.toString().toByteArray())

        return hashBytes.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        builder: StringBuilder
    ) {

        builder.append(node.className)
        builder.append(node.text)
        builder.append(node.contentDescription)
        builder.append(node.childCount)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                traverse(it, builder)
            }
        }
    }
}
