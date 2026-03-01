package com.airi.assistant.accessibility

object SessionMemory {
    var lastIntent: IntentType = IntentType.GENERAL
    var lastAppCategory: String = ""
    var lastContextSnapshot: String = ""
    var lastUserQuery: String = ""
    var lastPackageName: String = ""

    fun update(intent: IntentType, context: String, userQuery: String, packageName: String) {
        // إذا انتقل المستخدم لتطبيق آخر، نقوم بتصفير الذاكرة جزئياً لمنع "تداخل السياقات"
        if (lastPackageName.isNotEmpty() && lastPackageName != packageName) {
            clear()
        }

        lastIntent = intent
        lastContextSnapshot = context
        lastUserQuery = userQuery
        lastPackageName = packageName
        lastAppCategory = extractCategory(context)
    }

    private fun extractCategory(context: String): String {
        return Regex("\\[App Category: (.*?)\\]")
            .find(context)
            ?.groupValues?.get(1)
            ?: "Unknown"
    }

    fun clear() {
        lastIntent = IntentType.GENERAL
        lastAppCategory = ""
        lastContextSnapshot = ""
        lastUserQuery = ""
        lastPackageName = ""
    }
}
