package com.airi.assistant.core

import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityPipeline {

    fun processScreen(root: AccessibilityNodeInfo?) {

        if (root == null) return

        val screenHash = ScreenHasher.hash(root)

        AiriCore.handleScreen(screenHash)

    }

}
