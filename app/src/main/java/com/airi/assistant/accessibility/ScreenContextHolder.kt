package com.airi.assistant.accessibility

object ScreenContextHolder {

    var lastScreenText: String = ""

    var lastContextHash: Int = 0

    fun triggerExtraction(): String {
        return lastScreenText
    }

    fun reset() {
        lastScreenText = ""
        lastContextHash = 0
    }
}
