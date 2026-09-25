package com.airi.assistant.ai.skills

import java.util.Locale

data class LocalizedSkillPresentation(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val examples: List<String>,
    val limitations: List<String>,
    val toolDescriptions: Map<String, String>
)

object SkillPresentation {
    private data class Copy(val name: String, val description: String, val category: String)
    private val arabic = mapOf(
        "web_search" to Copy("بحث الويب", "يبحث في الويب عن المعلومات الحالية والأخبار والحقائق والإجابات", "البحث"),
        "website_reader" to Copy("قارئ المواقع", "يجلب المحتوى النصي الكامل لأي صفحة ويب ويستخرجه", "البحث"),
        "research_agent" to Copy("وكيل البحث", "يجري بحثًا عميقًا عبر مصادر متعددة ويقرأها ويصوغ خلاصة شاملة", "البحث"),
        "translator" to Copy("المترجم", "يترجم النص بين اللغات باستخدام نموذج الذكاء الاصطناعي النشط", "اللغات"),
        "code_assistant" to Copy("مساعد البرمجة", "يكتب الكود ويشرحه ويراجعه ويصححه ويعيد تنظيمه بأي لغة", "المطورون"),
        "task_planner" to Copy("مخطط المهام", "يقسم الأهداف المعقدة إلى خطط عملية مرتبة حسب الأولوية", "الإنتاجية"),
        "memory_manager" to Copy("مدير الذاكرة", "يبحث في ذاكرة AIRI الدائمة ويسترجع المعلومات ويحفظها", "الذاكرة"),
        "document_reader" to Copy("قارئ المستندات", "يقرأ النص ويستخرجه من مستندات الجهاز الشائعة", "المستندات"),
        "file_manager" to Copy("مدير الملفات", "يسرد الملفات في التخزين ويبحث فيها ويفحصها", "النظام"),
        "github_guardian" to Copy("حارس GitHub", "يقرأ المستودعات والملف الشخصي والنجوم والقضايا والنشاط", "المطورون"),
        "gmail_assistant" to Copy("مساعد Gmail", "يقرأ رسائل Gmail ويلخصها ويديرها", "التواصل"),
        "drive_search" to Copy("بحث Google Drive", "يبحث عن ملفات الحساب المتصل في Google Drive ويسترجعها", "البيانات"),
        "calendar_events" to Copy("أحداث التقويم", "يفحص أحداث تقويم Google وينشئها وينظم المواعيد", "الإنتاجية"),
        "telegram_messenger" to Copy("مراسلة Telegram", "يرسل رسائل Telegram وإشعاراتها عبر روبوت", "التواصل"),
        "pdf_analysis" to Copy("تحليل PDF", "يستخرج النص المحدود وبيانات الصفحات من مستندات PDF على الجهاز", "المستندات"),
        "ocr_analysis" to Copy("تحليل التعرف الضوئي", "يتعرف على النص من الصور أو صفحات PDF المعروضة على الجهاز", "المستندات"),
        "reminder_planning" to Copy("تخطيط التذكيرات", "يفهم التذكيرات أو المؤقتات لمرة واحدة ويجدولها بعد تأكيد صريح", "النظام")
    )
    fun isArabic(locale: Locale): Boolean = locale.language.equals("ar", ignoreCase = true)
    fun localized(manifest: SkillManifest, locale: Locale): LocalizedSkillPresentation {
        val copy = if (isArabic(locale)) arabic[manifest.id] else null
        val name = copy?.name ?: manifest.displayName.ifBlank { manifest.name }
        val description = copy?.description ?: manifest.description
        val category = copy?.category ?: manifest.category
        val examples = if (isArabic(locale)) listOf("استخدم مهارة «$name» لتنفيذ مهمة مرتبطة بوصفها.", "حلّل المدخلات وقدّم نتيجة موثقة مع توضيح القيود والخطوة التالية.") else manifest.examples.ifEmpty { listOf("Use $name for a task matching its description.") }
        val limitations = if (isArabic(locale)) listOf("تعتمد النتيجة على المدخلات المتاحة والأذونات والموصلات المفعّلة.") else manifest.limitations.ifEmpty { listOf("Results depend on the supplied input and available permissions.") }
        val tools = manifest.tools.associate { it.name to if (isArabic(locale)) "تنفيذ قدرة «$name» وفق سياسة الأمان والأذونات الحالية." else it.description }
        return LocalizedSkillPresentation(manifest.id, name, description, category, copy?.let { listOf(it.name, it.category) } ?: manifest.tags, examples, limitations, tools)
    }
    fun hasArabicCopy(skillId: String): Boolean = arabic.containsKey(skillId)
    fun arabicIds(): Set<String> = arabic.keys
}
