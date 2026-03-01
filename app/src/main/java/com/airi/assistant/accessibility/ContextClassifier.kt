package com.airi.assistant.accessibility

object ContextClassifier {
    fun getAppCategory(packageName: String): String {
        return when {
            packageName.contains("chrome") || packageName.contains("browser") -> "متصفح ويب (تحليل مقالات/بحث)"
            packageName.contains("github") || packageName.contains("stackoverflow") || packageName.contains("bin") -> "أدوات مبرمجين (تحليل كود)"
            packageName.contains("whatsapp") || packageName.contains("telegram") || packageName.contains("messaging") -> "تطبيق محادثة (مساعدة في الرد)"
            packageName.contains("settings") -> "إعدادات النظام (دعم فني)"
            packageName.contains("youtube") -> "منصة فيديو"
            else -> "تطبيق إنتاجية عام"
        }
    }
}
