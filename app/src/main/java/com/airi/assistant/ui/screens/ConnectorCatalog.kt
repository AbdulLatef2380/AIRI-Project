package com.airi.assistant.ui.screens

import android.net.Uri
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorType
import java.util.Locale

data class ConnectorPresentation(
    val name: String,
    val description: String,
    val category: String,
    val version: String,
    val projectAccess: String,
    val modelDependency: String,
    val capabilities: List<String>,
    val howToUse: String,
    val authentication: String,
    val iconUrl: String?
)

private data class Copy(
    val name: String,
    val description: String,
    val howToUse: String,
    val category: String? = null
)

/**
 * UI-only presentation boundary. Stable connector IDs, capability IDs and provider
 * URLs remain untouched; only user-facing copy is localized here.
 */
private object ConnectorCopy {
    private val ar = mapOf(
        "google_gmail" to Copy("Gmail", "الوصول إلى رسائل Gmail المصرح بها والبحث فيها وتنظيمها.", "اربط حساب Google ثم امنح AIRI صلاحية Gmail المطلوبة لقراءة الرسائل أو البحث عنها.", "Google"),
        "google_calendar" to Copy("تقويم Google", "قراءة أحداث التقويم والمواعيد القادمة من الحساب المصرح به.", "اربط حساب Google واختر صلاحية التقويم، ثم اطلب من AIRI البحث عن المواعيد أو تلخيصها.", "Google"),
        "google_drive" to Copy("Google Drive", "البحث في الملفات والمجلدات التي سمحت للحساب بالوصول إليها.", "اربط حساب Google ثم اطلب البحث عن ملف أو مستند بالاسم أو النوع.", "Google"),
        "google_docs" to Copy("مستندات Google", "الوصول المستقبلي إلى المستندات وتحريرها وفق الصلاحيات الممنوحة.", "يتطلب تفعيل موصل Google Docs وإذن المستندات قبل الاستخدام.", "Google"),
        "google_sheets" to Copy("جداول Google", "قراءة جداول البيانات وتحديثها بعد منح الإذن المناسب.", "يتطلب تفعيل الموصل ثم اختيار جدول البيانات والنطاق المراد قراءته أو تحديثه.", "Google"),
        "google_contacts" to Copy("جهات اتصال Google", "الوصول إلى جهات الاتصال التي يصرح بها المستخدم.", "يتطلب تفعيل الموصل ومنح صلاحية جهات الاتصال قبل البحث أو القراءة.", "Google"),
        "google_tasks" to Copy("مهام Google", "إدارة قوائم المهام والمهام المرتبطة بحساب Google.", "اربط الحساب ثم اطلب إنشاء مهمة أو عرض المهام القادمة بعد منح الإذن.", "Google"),
        "google_meet" to Copy("Google Meet", "معلومات اجتماعات Google Meet ودعم إجراءات الاجتماعات المعتمدة.", "يتطلب تفعيل الموصل وإذن الاجتماعات؛ لا ينفذ انضماماً أو تغييراً دون إجراء مصرح.", "Google"),
        "microsoft_outlook" to Copy("بريد Outlook", "الوصول إلى رسائل البريد في Outlook وتنظيمها وفق الإذن.", "اربط حساب Microsoft ثم اطلب البحث في البريد أو تلخيص الرسائل المصرح بها.", "Microsoft"),
        "microsoft_calendar" to Copy("تقويم Outlook", "قراءة المواعيد والأحداث من تقويم Microsoft.", "اربط حساب Microsoft وامنح صلاحية التقويم قبل عرض المواعيد أو البحث فيها.", "Microsoft"),
        "microsoft_onedrive" to Copy("OneDrive", "البحث في ملفات OneDrive المصرح بها والوصول إلى بياناتها.", "اربط حساب Microsoft ثم اطلب البحث عن الملفات أو المجلدات.", "Microsoft"),
        "microsoft_teams" to Copy("Microsoft Teams", "دعم الوصول إلى مساحات Teams ومعلومات التعاون المصرح بها.", "يتطلب حساب Microsoft وصلاحيات Teams المناسبة قبل أي قراءة أو إجراء.", "Microsoft"),
        "microsoft_sharepoint" to Copy("SharePoint", "الوصول إلى المواقع والملفات التي يصرح بها المستخدم.", "اربط حساب المؤسسة ثم حدد الموقع أو الملف المطلوب وفق صلاحيات الحساب.", "Microsoft"),
        "microsoft_todo" to Copy("Microsoft To Do", "قراءة قوائم المهام والمهام في Microsoft To Do.", "اربط حساب Microsoft ثم اطلب عرض المهام أو إضافة مهمة بعد التأكيد عند الحاجة.", "Microsoft"),
        "github" to Copy("GitHub", "قراءة المستودعات والقضايا وإنشاء القضايا عند منح صلاحية الكتابة.", "أدخل رمز GitHub الشخصي ثم اطلب مستودعاً أو قضية محددة؛ الإنشاء يتطلب تأكيداً.", "التطوير"),
        "gitlab" to Copy("GitLab", "الوصول إلى المستودعات والقضايا وسير العمل في GitLab.", "اربط حساب GitLab أو رمز الوصول ثم حدد المشروع والإجراء المطلوب.", "التطوير"),
        "bitbucket" to Copy("Bitbucket", "الوصول إلى مستودعات Bitbucket ومعلومات التطوير.", "اربط حساب Atlassian أو رمز الوصول ثم اختر مساحة العمل والمستودع.", "التطوير"),
        "jira" to Copy("Jira", "قراءة قضايا Jira ومشاريعها وإدارة العمل وفق الصلاحيات.", "اربط حساب Atlassian ثم حدد المشروع أو رقم القضية قبل تنفيذ أي تعديل.", "التطوير"),
        "linear" to Copy("Linear", "الوصول إلى القضايا والفرق والمشاريع في Linear.", "أدخل مفتاح Linear ثم اطلب البحث عن قضية أو مشروع؛ التعديلات تحتاج تأكيداً.", "التطوير"),
        "slack" to Copy("Slack", "الوصول إلى قنوات Slack والرسائل التي يسمح بها الحساب.", "اربط مساحة عمل Slack وحدد القناة؛ الإرسال لا يتم إلا بإجراء مصرح.", "التواصل"),
        "discord" to Copy("Discord", "الوصول إلى خوادم وقنوات Discord عبر الصلاحيات الممنوحة.", "اربط حساب أو تطبيق Discord ثم اختر الخادم والقناة قبل أي إجراء.", "التواصل"),
        "telegram" to Copy("Telegram", "إرسال رسائل Telegram عبر روبوت أو حساب مصرح.", "أدخل رمز روبوت Telegram ثم حدد المحادثة؛ إرسال الرسائل إجراء كتابي يتطلب تأكيداً.", "التواصل"),
        "notion" to Copy("Notion", "قراءة صفحات Notion وإنشاء صفحة عند منح صلاحية الكتابة.", "اربط Notion واختر الصفحات المشتركة مع التكامل؛ اذكر الصفحة أو قاعدة البيانات المطلوبة.", "الإنتاجية"),
        "trello" to Copy("Trello", "الوصول إلى اللوحات والبطاقات وقوائم العمل في Trello.", "اربط حساب Trello ثم حدد اللوحة والقائمة قبل القراءة أو التعديل.", "الإنتاجية"),
        "asana" to Copy("Asana", "قراءة المشاريع والمهام وتتبع العمل في Asana.", "اربط Asana ثم حدد المشروع أو المهمة؛ إنشاء أو تعديل المهام يحتاج تأكيداً.", "الإنتاجية"),
        "clickup" to Copy("ClickUp", "الوصول إلى مساحات ClickUp وقوائم المهام والمشاريع.", "اربط ClickUp وحدد المساحة والقائمة أو المهمة المطلوبة.", "الإنتاجية"),
        "monday" to Copy("Monday.com", "قراءة لوحات Monday.com وإدارة عناصر العمل المصرح بها.", "اربط الحساب ثم اختر اللوحة؛ تغييرات العناصر لا تنفذ دون تأكيد.", "الإنتاجية"),
        "todoist" to Copy("Todoist", "قراءة قوائم Todoist وإدارة المهام والمواعيد.", "اربط Todoist ثم اطلب عرض المهام أو إنشاء مهمة مع تأكيد الإجراء الكتابي.", "الإنتاجية"),
        "dropbox" to Copy("Dropbox", "الوصول إلى الملفات والمجلدات في Dropbox.", "اربط Dropbox ثم ابحث باسم الملف أو المجلد؛ المشاركة والحذف يتطلبان تأكيداً.", "الملفات"),
        "box" to Copy("Box", "البحث في ملفات Box والوصول إلى المحتوى المصرح به.", "اربط حساب Box ثم حدد المجلد أو الملف المطلوب.", "الملفات"),
        "figma" to Copy("Figma", "الوصول إلى ملفات وتصميمات Figma التي يسمح بها الحساب.", "اربط Figma ثم حدد الملف أو المشروع؛ التعديل يتطلب موصل كتابة مكتمل.", "التصميم"),
        "canva" to Copy("Canva", "الوصول إلى تصميمات Canva المصرح بها.", "اربط Canva ثم اختر التصميم أو المشروع المطلوب.", "التصميم"),
        "airtable" to Copy("Airtable", "قراءة قواعد Airtable وإدارة السجلات وفق الصلاحيات.", "اربط Airtable وحدد القاعدة والجدول؛ إنشاء أو تعديل السجلات يحتاج تأكيداً.", "الأتمتة"),
        "zapier" to Copy("Zapier", "تشغيل تدفقات Zap وأتمتة الخدمات الخارجية.", "اربط Zapier ثم اختر التدفق؛ أي إجراء خارجي يجب عرضه وطلب تأكيده قبل التشغيل.", "الأتمتة"),
        "zoom" to Copy("Zoom", "الوصول إلى الاجتماعات ومعلوماتها وفق حساب Zoom.", "اربط Zoom ثم حدد الاجتماع؛ الانضمام أو التعديل يتطلب إجراءً واضحاً من المستخدم.", "الاجتماعات"),
        "remote_llm" to Copy("نماذج الذكاء السحابية", "توجيه طلبات الدردشة إلى مزودي النماذج الذين أعددتهم.", "أضف مفتاح المزود واختر النموذج من إعدادات التنفيذ قبل إرسال الطلب.", "الذكاء الاصطناعي"),
        "android_intent" to Copy("إجراءات Android", "فتح التطبيقات والروابط وتنفيذ نوايا Android المسموح بها.", "اطلب فتح تطبيق أو رابط؛ سيطلب AIRI تدخل المستخدم عندما تكون العملية حساسة.", "الجهاز"),
        "voice_mtmd" to Copy("الصوت", "تحويل الصوت إلى نص عبر محرك الصوت المحلي المتاح.", "فعّل محرك الصوت وامنح إذن الميكروفون ثم ابدأ الإدخال الصوتي.", "الجهاز"),
        "clipboard" to Copy("الحافظة", "قراءة أو نسخ النص من حافظة الجهاز وفق الإجراء المطلوب.", "اطلب قراءة الحافظة أو نسخ نص محدد؛ لا تُرسل البيانات إلى الخارج تلقائياً.", "الجهاز"),
        "device_apps" to Copy("تطبيقات الجهاز", "العثور على التطبيقات وفتحها والانتقال إلى الروابط.", "اذكر اسم التطبيق أو الرابط؛ يتولى Android عملية الفتح مع احترام القيود.", "الجهاز"),
        "contacts" to Copy("جهات اتصال الجهاز", "قراءة جهات الاتصال المحلية بعد منح إذن Android.", "اضغط اتصال ثم امنح إذن جهات الاتصال، وبعدها اطلب البحث بالاسم أو الرقم.", "الجهاز"),
        "system_info" to Copy("معلومات النظام", "عرض حالة البطارية والشبكة ومعلومات الجهاز الأساسية.", "الموصل جاهز تلقائياً؛ اطلب حالة البطارية أو الشبكة أو ملخص النظام.", "النظام"),
        "n8n" to Copy("n8n", "تشغيل سير عمل n8n عبر webhook مضبوط من المستخدم.", "أدخل عنوان webhook ثم اختبر الاتصال؛ التشغيل الخارجي يحتاج تأكيداً.", "الأتمتة"),
        "ifttt" to Copy("IFTTT", "تشغيل Applets وأحداث IFTTT عبر تكامل مصرح.", "اربط IFTTT ثم اختر الحدث؛ راجع البيانات قبل تشغيل الأتمتة.", "الأتمتة"),
        "notion_mcp" to Copy("Notion عبر MCP", "الوصول إلى أدوات Notion التي يعرضها خادم MCP المكوّن.", "أضف خادم MCP ثم نفّذ handshake؛ الأدوات المتاحة تظهر بعد نجاح الاتصال.", "الإضافات"),
        "google" to Copy("خدمات Google", "واجهة موحدة لـ Gmail وتقويم Google وDrive.", "اربط حساب Google ثم استخدم الخدمة المطلوبة فقط ضمن الصلاحيات المعروضة.", "Google")
    )

    fun forMeta(meta: ConnectorMeta, locale: Locale): Copy? = if (locale.language.equals("ar", true)) ar[meta.id] else null
}

private val officialIcons = mapOf(
    "github" to "https://github.com/favicon.ico",
    "telegram" to "https://telegram.org/img/t_logo.png",
    "notion" to "https://www.notion.so/images/favicon.ico",
    "notion_mcp" to "https://www.notion.so/images/favicon.ico",
    "zapier" to "https://cdn.zapier.com/zapier/images/logos/zapier-logomark.png",
    "ifttt" to "https://ifttt.com/favicon.ico",
    "n8n" to "https://n8n.io/favicon.ico",
    "google" to "https://www.google.com/favicon.ico",
    "remote_llm" to "https://ai.google.dev/favicon.ico",
    "android_intent" to "https://www.android.com/static/2016/img/favicon.ico",
    "microsoft_outlook" to "https://outlook.live.com/favicon.ico",
    "microsoft_calendar" to "https://outlook.live.com/favicon.ico",
    "microsoft_onedrive" to "https://onedrive.live.com/favicon.ico",
    "microsoft_teams" to "https://teams.microsoft.com/favicon.ico",
    "microsoft_sharepoint" to "https://www.microsoft.com/favicon.ico",
    "microsoft_todo" to "https://to-do.live.com/favicon.ico"
)

fun ConnectorMeta.presentation(locale: Locale = Locale.getDefault()): ConnectorPresentation {
    val copy = ConnectorCopy.forMeta(this, locale)
    val category = copy?.category ?: this.category ?: when (type) {
        ConnectorType.API -> "AI & APIs"
        ConnectorType.APP -> "Apps & Services"
        ConnectorType.LOCAL -> "Device & Local"
        ConnectorType.MCP -> "Extensions"
        ConnectorType.SYSTEM -> "System"
    }
    val projectAccess = when (type) {
        ConnectorType.LOCAL, ConnectorType.SYSTEM -> if (copy != null) "سطح الجهاز وأذونات Android التي يمنحها المستخدم" else "The device surface and Android permissions granted by the user"
        ConnectorType.MCP -> if (copy != null) "الأدوات التي يعرضها خادم MCP المكوّن" else "The tools exposed by the configured MCP server"
        else -> if (copy != null) "النقطة الطرفية والموارد التي يصرح بها المستخدم" else "The endpoint and resources authorized by the user"
    }
    val modelDependency = if (type == ConnectorType.API) {
        if (copy != null) "يوفر هذا الموصل واجهة API أو سطح نموذج" else "This connector provides an API or model surface"
    } else {
        if (copy != null) "لا يعلن العقد العام اعتماداً على نموذج" else "The connector does not declare a model requirement in its public metadata"
    }
    val capabilities = if (this.capabilities.isNotEmpty()) {
        this.capabilities.map { capability ->
            val permission = when (capability.permission.name) {
                "READ" -> if (copy != null) "قراءة" else "Read"
                "WRITE" -> if (copy != null) "كتابة" else "Write"
                "DESTRUCTIVE" -> if (copy != null) "حساس" else "Destructive"
                else -> if (copy != null) "إداري" else "Admin"
            }
            if (copy != null) "$permission: ${capability.description}" else "${capability.description} ($permission)"
        }
    } else if (this.tags.isNotEmpty()) {
        listOf(if (copy != null) "الوسوم: ${tags.joinToString()}" else "Declared tags: ${tags.joinToString()}")
    } else emptyList()
    val auth = authenticationType?.name?.let { raw ->
        if (copy == null) raw else when (raw) {
            "OAUTH2" -> "OAuth 2.0"
            "OAUTH2_AND_API" -> "OAuth 2.0 ومفتاح API"
            "API_KEY" -> "مفتاح API"
            "PERSONAL_ACCESS_TOKEN" -> "رمز وصول شخصي"
            "MCP" -> "خادم MCP"
            "WEBHOOK" -> "Webhook"
            "LOCAL" -> "محلي"
            else -> "لا يتطلب مصادقة"
        }
    } ?: if (copy != null) "تحددها حالة الموصل" else "Defined by connector state"
    return ConnectorPresentation(
        name = copy?.name ?: name,
        description = copy?.description ?: description,
        category = category,
        version = "Contract-managed",
        projectAccess = projectAccess,
        modelDependency = modelDependency,
        capabilities = capabilities,
        howToUse = copy?.howToUse ?: "Use this connector only through its declared capabilities and permissions.",
        authentication = auth,
        iconUrl = iconUrl ?: officialIcons[id] ?: website?.let { runCatching { "https://${Uri.parse(it).host}/favicon.ico" }.getOrNull() }
    )
}
