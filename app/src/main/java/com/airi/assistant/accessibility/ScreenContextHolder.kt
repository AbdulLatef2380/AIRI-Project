package com.airi.assistant.accessibility

object ScreenContextHolder {

    var lastScreenText: String = ""

    var serviceInstance: AIRIAccessibilityService? = null

    fun triggerExtraction(): String {
        return serviceInstance?.extractScreenContext() ?: "خدمة الوصول غير مفعلة"
    }
}
