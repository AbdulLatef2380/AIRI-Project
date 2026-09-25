package com.airi.assistant.ai.skills

import java.util.Locale

/** User-facing copy for a skill. Runtime identity and execution metadata stay in [SkillManifest]. */
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

/**
 * Arabic presentation layer for the canonical skill catalog.
 *
 * The map is deliberately keyed by immutable IDs: translating a title must never
 * alter `/skill:<id>` routing, persistence, permissions, or model selection.
 */
object SkillPresentation {
    private data class Copy(val name: String, val description: String, val category: String)

    private val arabic = mapOf(
        "api_quality_reviewer" to Copy("مراجع جودة واجهات API", "يراجع عقود واجهات API من حيث الوضوح والتوافق وسلوك الفشل", "الهندسة"),
        "architecture_advisor" to Copy("مستشار المعمارية", "يقارن خيارات المعمارية وفق القيود وأنماط الفشل المحتملة", "الهندسة"),
        "calendar_events" to Copy("أحداث التقويم", "يفحص أحداث تقويم Google وينشئها وينظم المواعيد", "الإنتاجية"),
        "checklist_generator" to Copy("منشئ قوائم التحقق", "يحوّل الهدف أو الإجراء إلى قائمة تحقق قابلة للتحقق", "الإنتاجية"),
        "code_assistant" to Copy("مساعد البرمجة", "يكتب الكود ويشرحه ويراجعه ويصححه ويعيد تنظيمه بأي لغة", "المطورون"),
        "code_review_advanced" to Copy("مراجعة كود متقدمة", "يراجع الكود من حيث الصحة والأمان وقابلية الصيانة والاختبارات", "المطورون"),
        "complexity_reducer" to Copy("مخفّض التعقيد", "يقلل التعقيد مع الحفاظ على السلوك وقابلية الاختبار", "جودة البرمجيات"),
        "compose_recomposition_auditor" to Copy("مدقق إعادة تركيب Compose", "يحدد عمليات إعادة التركيب غير الضرورية وحالة الواجهة غير المستقرة", "جودة البرمجيات"),
        "concurrency_auditor" to Copy("مدقق التزامن", "يبحث عن أخطاء الحجب والإلغاء والتسابق والمشغلات", "جودة البرمجيات"),
        "daily_task_organizer" to Copy("منظم المهام اليومية", "ينظم المهام المعطاة في خطة يومية واقعية", "الإنتاجية"),
        "data_insight_advanced" to Copy("رؤى بيانات متقدمة", "يحوّل البيانات الجدولية أو النصية إلى رؤى حذرة قابلة للتفسير", "التحليل"),
        "decision_matrix" to Copy("مصفوفة القرار", "يقارن الخيارات وفق معايير واضحة ومقايضات معلنة", "الإنتاجية"),
        "document_reader" to Copy("قارئ المستندات", "يقرأ النص ويستخرجه من مستندات الجهاز الشائعة", "المستندات"),
        "drive_search" to Copy("بحث Google Drive", "يبحث عن ملفات الحساب المتصل في Google Drive ويسترجعها", "البيانات"),
        "email_drafter" to Copy("كاتب البريد الإلكتروني", "يصوغ بريدًا احترافيًا انطلاقًا من النية والسياق", "التواصل"),
        "fact_checker" to Copy("مدقق الحقائق", "يفصل الادعاءات ويقيّم الأدلة ويبلغ عن درجة عدم اليقين", "التحليل"),
        "file_manager" to Copy("مدير الملفات", "يسرد الملفات في التخزين ويبحث فيها ويفحصها", "النظام"),
        "final_answer_verification" to Copy("مدقق الإجابة النهائية", "يفحص مسودة الإجابة مقابل الأدلة ونتائج التنفيذ", "الجودة"),
        "github_guardian" to Copy("حارس GitHub", "يقرأ المستودعات والملف الشخصي والنجوم والقضايا والنشاط", "المطورون"),
        "gmail_assistant" to Copy("مساعد Gmail", "يقرأ رسائل Gmail ويلخصها ويديرها", "التواصل"),
        "incident_analyzer" to Copy("محلل الحوادث", "يحلل الخط الزمني للحادث وينتج إجراءات لمعالجة السبب الجذري", "العمليات"),
        "interview_coach" to Copy("مدرب المقابلات", "يجهز أسئلة المقابلة ويقدم ملاحظات قابلة للتنفيذ", "التطوير الشخصي"),
        "json_extractor" to Copy("مستخرج JSON", "يستخرج حقولًا منظمة من نص غير منظم", "البيانات"),
        "meeting_agenda" to Copy("جدول أعمال الاجتماع", "ينشئ جدول أعمال مركزًا مع النتائج والمسؤوليات", "التواصل"),
        "meeting_summarizer" to Copy("ملخص الاجتماعات", "يلخص محضر الاجتماع إلى قرارات وعناصر عمل", "التواصل"),
        "memory_leak_auditor" to Copy("مدقق تسرب الذاكرة", "يبحث عن مخاطر الاحتفاظ وضغط الذاكرة في كود التطبيق", "جودة البرمجيات"),
        "memory_manager" to Copy("مدير الذاكرة", "يبحث في ذاكرة AIRI الدائمة ويسترجع المعلومات ويحفظها", "الذاكرة"),
        "ocr_analysis" to Copy("تحليل التعرف الضوئي", "يتعرف على النص من الصور أو صفحات PDF المعروضة على الجهاز", "المستندات"),
        "pdf_analysis" to Copy("تحليل PDF", "يستخرج النص المحدود وبيانات الصفحات من مستندات PDF على الجهاز", "المستندات"),
        "performance_profiler" to Copy("محلل الأداء", "يحدد الاختناقات من الكود أو آثار الأداء أو نتائج القياس", "جودة البرمجيات"),
        "prompt_evaluator" to Copy("مقيّم المطالبات", "يقيّم المطالبات ويحسنها عبر النقد وإعادة الصياغة", "الذكاء الاصطناعي"),
        "refactoring_planner" to Copy("مخطط إعادة الهيكلة", "ينشئ خطة تدريجية لتحسين الجودة انطلاقًا من نقطة ساخنة في الكود", "المطورون"),
        "reminder_planning" to Copy("تخطيط التذكيرات", "يفهم التذكيرات أو المؤقتات لمرة واحدة ويجدولها بعد تأكيد صريح", "النظام"),
        "replanning_strategy" to Copy("استراتيجية إعادة التخطيط", "ينشئ خطة تعافٍ محدودة بعد خطوة فاشلة أو منخفضة الثقة", "الإنتاجية"),
        "requirements_extractor" to Copy("مستخرج المتطلبات", "يحوّل النثر إلى متطلبات وظيفية قابلة للاختبار", "الهندسة"),
        "research_agent" to Copy("وكيل البحث", "يجري بحثًا عميقًا عبر مصادر متعددة ويقرأها ويصوغ خلاصة شاملة", "البحث"),
        "security_threat_model" to Copy("نموذج التهديدات الأمنية", "ينمذج التهديدات وإجراءات التخفيف لميزة أو نظام", "الأمان"),
        "sentiment_analyzer" to Copy("محلل المشاعر", "يحلل المشاعر والنبرة ودرجة عدم اليقين في النص", "التحليل"),
        "sql_assistant" to Copy("مساعد SQL", "يكتب استعلامات SQL أو يشرحها مع فحوصات أمان", "المطورون"),
        "startup_latency_auditor" to Copy("مدقق زمن بدء التشغيل", "يقلل أعمال بدء التشغيل البارد والدافئ بأمان", "جودة البرمجيات"),
        "study_tutor" to Copy("المدرس الدراسي", "يشرح موضوعًا وينشئ أسئلة تدريبية", "التعلم"),
        "summarizer" to Copy("الملخص", "ينشئ ملخصًا موجزًا وأمينًا للنص", "الكتابة"),
        "task_planner" to Copy("مخطط المهام", "يقسم الأهداف المعقدة إلى خطط عملية مرتبة حسب الأولوية", "الإنتاجية"),
        "telegram_messenger" to Copy("مراسلة Telegram", "يرسل رسائل Telegram وإشعاراتها عبر روبوت", "التواصل"),
        "test_strategy" to Copy("مصمم استراتيجية الاختبار", "يصمم خطة اختبار متعددة الطبقات انطلاقًا من المتطلبات والمخاطر", "جودة البرمجيات"),
        "text_rewriter" to Copy("معيد صياغة النص", "يعيد صياغة النص وفق النبرة أو الجمهور أو مستوى الوضوح المطلوب", "الكتابة"),
        "translator" to Copy("المترجم", "يترجم النص بين اللغات باستخدام نموذج الذكاء الاصطناعي النشط", "اللغات"),
        "web_search" to Copy("بحث الويب", "يبحث في الويب عن المعلومات الحالية والأخبار والحقائق والإجابات", "البحث"),
        "website_reader" to Copy("قارئ المواقع", "يجلب المحتوى النصي الكامل لأي صفحة ويب ويستخرجه", "البحث")
    )

    fun isArabic(locale: Locale): Boolean = locale.language.equals("ar", ignoreCase = true)

    fun localized(manifest: SkillManifest, locale: Locale): LocalizedSkillPresentation {
        val copy = if (isArabic(locale)) arabic[manifest.id] else null
        val name = copy?.name ?: manifest.displayName.ifBlank { manifest.name }
        val description = copy?.description ?: manifest.description
        val category = copy?.category ?: manifest.category
        val examples = if (isArabic(locale)) listOf("استخدم مهارة «$name» لتنفيذ مهمة مرتبطة بوصفها.", "حلّل المدخلات وقدّم نتيجة موثقة مع توضيح القيود والخطوة التالية.")
        else manifest.examples.ifEmpty { listOf("Use $name for a task matching its description.") }
        val limitations = if (isArabic(locale)) listOf("تعتمد النتيجة على المدخلات المتاحة والأذونات والموصلات المفعّلة.")
        else manifest.limitations.ifEmpty { listOf("Results depend on the supplied input and available permissions.") }
        val toolDescriptions = manifest.tools.associate { tool ->
            tool.name to if (isArabic(locale)) "تنفيذ قدرة «$name» وفق سياسة الأمان والأذونات الحالية." else tool.description
        }
        return LocalizedSkillPresentation(manifest.id, name, description, category, copy?.let { listOf(it.name, it.category) } ?: manifest.tags, examples, limitations, toolDescriptions)
    }

    fun hasArabicCopy(skillId: String): Boolean = arabic.containsKey(skillId)
    fun arabicIds(): Set<String> = arabic.keys
}
