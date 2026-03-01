package com.airi.assistant.accessibility

object ScreenContextHolder {

    // آخر نص تم التقاطه من الشاشة
    var lastScreenText: String = ""

    // مرجع مباشر إلى AccessibilityService
    var serviceInstance: AIRIAccessibilityService? = null
}
