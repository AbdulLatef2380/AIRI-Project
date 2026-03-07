package com.airi.assistant.accessibility

object ScreenContextHolder {

    var lastProcessedContext: String = ""

    var lastScreenText: String = ""

    var lastContextHash: Int = 0

    var serviceInstance: AIRIAccessibilityService? = null

    fun triggerExtraction(): String {
        return serviceInstance?.extractScreenContext() ?: lastScreenText
    }

    fun reset() {
        lastScreenText = ""
        lastContextHash = 0
        serviceInstance = null
    }
}
