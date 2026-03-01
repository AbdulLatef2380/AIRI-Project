package com.airi.assistant.accessibility

/**
 * حامل البيانات المركزي لسياق الشاشة.
 * يربط بين خدمة الوصول (Accessibility Service) وواجهة المستخدم (Overlay).
 */
object ScreenContextHolder {

    // النص الأخير الذي تمت معالجته
    var lastScreenText: String = ""

    // 🔑 البصمة الذكية لمنع التكرار (Stability Guard)
    var lastContextHash: Int = 0

    // 🔗 مرجع حي للخدمة (ضروري لعمل trigger Extraction يدوياً)
    var serviceInstance: AIRIAccessibilityService? = null

    /**
     * يحاول استخراج السياق فوراً إذا كانت الخدمة متاحة، 
     * وإلا يعيد آخر نص مخزن.
     */
    fun triggerExtraction(): String {
        return serviceInstance?.extractScreenContext() ?: lastScreenText
    }

    /**
     * تنظيف البيانات عند إيقاف الخدمة
     */
    fun reset() {
        lastScreenText = ""
        lastContextHash = 0
        serviceInstance = null
    }
}
