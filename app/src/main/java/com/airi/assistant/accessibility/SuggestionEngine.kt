package com.airi.assistant.accessibility

/**
 * محرك الاقتراحات الذكي - النسخة المحدثة (Intent-Based)
 * يقوم بتحويل "النية" المكتشفة إلى خيارات ملموسة للمستخدم.
 */
object SuggestionEngine {

    fun generateSuggestions(context: String): List<String> {
        val suggestions = mutableListOf<String>()
        
        // 🔥 استدعاء محرك النوايا بدون Query (وضع الاكتشاف التلقائي)
        // نمرر نصاً فارغاً للـ Query لأننا نعتمد على السياق فقط هنا
        val detectedIntent = IntentDetector.detectIntent(userQuery = "", context = context)

        // تحويل النية المكتشفة (IntentType) إلى اقتراحات فعلية
        when (detectedIntent) {
            IntentType.SUMMARIZE -> {
                suggestions.add("📄 تلخيص المحتوى الحالي")
                suggestions.add("⏳ استخراج النقاط الرئيسية")
            }
            
            IntentType.DEBUG_ERROR -> {
                suggestions.add("🐞 تحليل الخطأ البرمجي")
                suggestions.add("🔍 البحث عن حل في الويب")
            }
            
            IntentType.BATTERY_DIAGNOSIS -> {
                suggestions.add("🔋 تحليل استهلاك البطارية")
            }
            
            IntentType.CODE_ANALYSIS -> {
                suggestions.add("💻 شرح هذا الكود")
                suggestions.add("✨ تحسين صياغة الكود")
            }

            IntentType.GENERAL -> {
                // منطق إضافي للسياقات العامة التي لم تصل لدرجة "نية مؤكدة"
                if (context.contains("محادثة") || context.contains("chat")) {
                    suggestions.add("✍️ اقتراح رد ذكي")
                } else if (context.length > 500) {
                    suggestions.add("🧠 ماذا يوجد في هذه الشاشة؟")
                }
            }
            
            else -> {
                // في حال وجود أنواع نيات أخرى مضافة في IntentType
                if (context.isNotBlank()) {
                    suggestions.add("🧐 تحليل السياق الحالي")
                }
            }
        }

        // 🧠 اللمسة الأخيرة: إعادة ترتيب القائمة بناءً على ما يضغط عليه المستخدم أكثر
        return BehaviorEngine.adjustSuggestionPriority(suggestions)
    }
}
