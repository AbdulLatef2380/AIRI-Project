# قائمة جاهزية إطلاق AIRI

**تاريخ التدقيق:** 13 أغسطس 2026
**الفرع محل التدقيق:** `architecture-refactor`
**الالتزام الأساسي:** `a5859d8922341fec88de8e9a372bcb67559e8b0d`
**قاعدة الإثبات:** لا تعني نتيجة فحص ساكن أو مراجعة مصدر أن ميزة ما اجتازت بناء Android أو اختبار جهاز فعلي.

> **الحكم الحالي:** لا تستوفي النسخة بوابة الإطلاق. لا يوجد بناء Debug أو Release موثق، ولا APK/AAB للفحص، وتظل مسارات المحادثة والصوت والموفرات والترحيل غير متحققة وقت التشغيل.

| بوابة القبول | الحالة | الدليل | الملفات الرئيسية | الاختبار | التحقق وقت التشغيل | الخطر المتبقي |
|---|---|---|---|---|---|---|
| تثبيت خط الأساس | PASS | تم توثيق الفرع والالتزام وGradle 8.5 وAGP 8.2.2 وKotlin 1.9.22 وcompile/target SDK 34 وmin SDK 26 وNDK 25.2.9519653 وCMake 3.22.1. | `app/build.gradle.kts`, `gradle/libs.versions.toml` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | التزام التدقيق متقدم محلياً عن البعيد بسبب DNS. |
| تجميع Debug | BLOCKED_BY_ENVIRONMENT | فشل الغلاف قبل التهيئة بسبب DNS عند تنزيل Gradle؛ وفشل Gradle المحلي قبل التهيئة لأن AGP 8.2.2 غير مخزن محلياً. | `gradle/wrapper`, `build.gradle.kts` | `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:lintDebug` | NOT_RUNTIME_VERIFIED | أخطاء Kotlin أو الموارد أو JNI قد تبقى غير مكتشفة. |
| تجميع وحزمة Release | BLOCKED_BY_ENVIRONMENT | لم يبدأ Gradle بسبب AGP غير المتاح. | `app/build.gradle.kts` | `:app:compileReleaseKotlin`, `:app:assembleRelease`, `:app:bundleRelease` | NOT_RUNTIME_VERIFIED | لا توجد أدلة R8 أو Packaging أو حجم أو ABI للحزمة. |
| توقيع الإصدار | NOT_VERIFIED | متغيرات `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` غير موجودة في البيئة. | `app/build.gradle.kts` | فحص حضور المتغيرات فقط | NOT_RUNTIME_VERIFIED | **RELEASE SIGNING NOT CONFIGURED** في بيئة التدقيق. |
| فحص المصدر الأساسي | PASS_WITH_LIMITATION | الفاحص الساكن يمر 23/23 في شجرة Git المدققة. | `tools/verify_core_changes.py` | `python3 tools/verify_core_changes.py` | NOT_RUNTIME_VERIFIED | الفاحص لا يحل محل Gradle أو الاختبارات. |
| الحوار والبث والإلغاء | PASS_WITH_LIMITATION | ملكية generation ID وحاجز callbacks ومسار Stop موجودة في المصدر. | `ChatViewModel.kt`, `HybridOrchestrator.kt`, `ChatScreen.kt` | فحص ساكن 23/23 | NOT_RUNTIME_VERIFIED | لا دليل على بث فعلي أو إلغاء موفر أو استعادة واجهة على جهاز. |
| معالجة أخطاء الحوار | PASS_WITH_LIMITATION | توجد تصنيفات وخريطة واجهة لأخطاء الصوت؛ لم تحاكَ أخطاء موفر أو شبكة حقيقية. | `ChatScreen.kt`, `HybridOrchestrator.kt` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | retry/regenerate وفشل الشبكة بحاجة اختبار جهاز وخدمة حقيقية. |
| قاعدة البيانات والترحيلات | NOT_RUNTIME_VERIFIED | Room عند الإصدار 6 مع ترحيلات 1→6؛ لا schemas مصدرة ولا اختبار ترقية فعلي. أُوقف SQLCipher افتراضياً لأن مسار الترقية لم يثبت. | `AiriDatabase.kt`, `AiriDatabaseMigrationHelper.kt` | مراجعة مصدر واختبار Room مضاف لمعرف الإدراج | NOT_RUNTIME_VERIFIED | يجب اختبار تثبيت جديد وترقية كل إصدار سابق واسترجاع بيانات. |
| الذاكرة وRAG | PASS_WITH_LIMITATION | سياسة قبول، حدود جلسة، ذاكرة طويلة الأجل صريحة، بحث مقيّد بالجلسة، وتطبيع عربي موجودة. | `MemoryAdmissionPolicy.kt`, `MemoryManager.kt`, `EmbeddingService.kt`, `MemoryTextNormalizer.kt` | اختبارات وحدة مصدرية وفحص ساكن | NOT_RUNTIME_VERIFIED | الدقة الدلالية وحذف الذاكرة والتعارض والتحميل الطويل تحتاج جهازاً. |
| اختيار `@` للمعرفة | PASS_WITH_LIMITATION | اقتراحات وإدراج مراجع وإعادة تحقق قبل التنفيذ موجودة. | `ChatScreen.kt`, `ChatViewModel.kt` | فحص ساكن | NOT_RUNTIME_VERIFIED | لا اختبار واجهة أو وصول السياق إلى موفر حي. |
| اختيار `/` للمهارة | PASS_WITH_LIMITATION | قائمة الاقتراحات تستبعد المهارات غير المتصلة وتتحقق من المرجع. | `ChatScreen.kt`, `ChatViewModel.kt`, `SkillRegistry.kt` | فحص ساكن | NOT_RUNTIME_VERIFIED | لا اختبار لمسار مستخدم أو تنفيذ مهارة فعلية. |
| تثبيت المهارات والسوق | PASS_WITH_LIMITATION | التسجيل الديناميكي يطلب endpoint قابل للتنفيذ؛ حُذف MCP التجريبي ذو handshake الوهمي. | `SkillRegistry.kt`, `MarketplaceRepository.kt`, `ConnectorBootstrap.kt`, `McpConnector.kt` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | API السوق والـ manifest البعيد وتدفق الموافقة غير مختبرين. |
| المهام المجدولة | PASS_WITH_LIMITATION | العمل فريد لكل job ومعرفه محفوظ ونتائج التنفيذ دائمة وواجهة حالات موجودة. | `ScheduledJobOrchestrator.kt`, `ScheduledAgentWorker.kt`, `AgentTasksScreen.kt` | فحص ساكن | NOT_RUNTIME_VERIFIED | WorkManager وDoze وإعادة التشغيل والإشعارات غير مختبرة. |
| المرفقات | NOT_RUNTIME_VERIFIED | مسارات الحفظ والإزالة موجودة في المصدر. | `ChatViewModel.kt`, `ChatScreen.kt` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | لا اختبار صورة أو ملف أو فشل تحميل أو إلغاء أو معاينة. |
| الصوت وكلمة التنبيه | PASS_WITH_LIMITATION | تهدئة wake word، حراسة الإيقاف، ملكية TTS واحدة، ورسائل مترجمة موجودة. | `HotwordService.kt`, `LiveVoiceService.kt`, `VoiceAgentRouter.kt` | فحص ساكن | NOT_RUNTIME_VERIFIED | لا ميكروفون أو Vosk أو TTS أو Bluetooth أو Android audio-focus قيد الاختبار. |
| الموفرات والخدمات الخارجية | NOT_RUNTIME_VERIFIED | PKCE وحالة OAuth الأحادية موجودان؛ Zapier يرفض بدء OAuth بلا إعداد. | `OAuthStateRegistry.kt`, `ZapierConnector.kt`, `OpenAiProvider.kt` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | مفاتيح وموفرات وFirebase وOAuth وrate limit غير متاحة. |
| الأمان والخصوصية | PASS_WITH_LIMITATION | لا نمط أسرار واضح في المسح؛ حُذف منفذ shell غير المستخدم؛ حُذفت pins غير موثقة؛ التطبيق يمنع النسخ الاحتياطي. | `AndroidManifest.xml`, `network_security_config.xml`, `LlmCertPins.kt`, `ToolExecutor.kt` (محذوف) | مسح ثابت ومراجعة Manifest | NOT_RUNTIME_VERIFIED | يلزم تحليل APK واختبار deep link وFileProvider وFirebase وقواعد الشبكة. |
| التخزين والتنظيف | PASS_WITH_LIMITATION | حدود للرسائل والحقائق وسجلات التدقيق وcache المرفقات ومسارات حذف artifacts موجودة. | `MemoryManager.kt`, `AuditRepository.kt`, `ArtifactManager.kt`, `ChatViewModel.kt` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | لا قياس نمو التخزين أو ضغط الذاكرة على جهاز. |
| الأداء والذاكرة | NOT_RUNTIME_VERIFIED | توجد تهيئة مؤجلة وتحرير عند ضغط الذاكرة وتحسينات استرجاع؛ لا أرقام مقاسة. | `AIRIApplication.kt`, `ChatViewModel.kt`, `MemoryManager.kt` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | لا cold/warm start أو RAM أو CPU أو jank أو جهاز منخفض المواصفات. |
| RTL والترجمة | PASS_WITH_LIMITATION | تماثل مفاتيح الإنجليزية والعربية والإسبانية والصينية 100%؛ أضيفت أوصاف وصول أساسية. | `values*/strings.xml`, `ChatScreen.kt` | فحص ساكن 23/23 | NOT_RUNTIME_VERIFIED | الإسبانية والصينية تحتوي احتياطيات إنجليزية، ولا اختبار RTL أو scale خط. |
| الوصولية | PASS_WITH_LIMITATION | أوصاف لأفعال شريط الدردشة الأساسية موجودة. | `ChatScreen.kt`, `strings.xml` | مراجعة مصدر | NOT_RUNTIME_VERIFIED | لا TalkBack أو focus order أو contrast أو touch-target audit كامل. |
| APK/AAB | BLOCKED_BY_ENVIRONMENT | لا artifacts في `app/build` بعد فشل Gradle. | `app/build` | فحص ملفات | NOT_RUNTIME_VERIFIED | الحجم، native `.so`، الموارد، debug symbols غير قابلة للفحص. |
| استقرار/Crash | NOT_RUNTIME_VERIFIED | لا TODO/FIXME في Kotlin حسب المسح، لكن عدد force unwrap/casts يحتاج تحليل lint وتشغيلاً. | شجرة Kotlin | مسح ساكن | NOT_RUNTIME_VERIFIED | JNI وRoom وFirebase وVosk وWorkManager لم تشغل. |

## الشروط غير القابلة للتجاوز قبل النشر العام

| الأولوية | الشرط | سبب الحظر | معيار الخروج |
|---|---|---|---|
| P0 | تنفيذ Debug وRelease build وLint واختبارات الوحدة | لا يمكن اعتماد مصدر لم يُجمع. | نجاح الأوامر في CI أو Android Studio متصل. |
| P0 | توقيع إصدار وناتج AAB/APK وفحصه | لا توجد حزمة قابلة للتوزيع أو توقيع مفعل. | توقيع آمن في CI وفحص artifact. |
| P0 | مسارات الحوار والإلغاء والمرفقات والذاكرة على جهاز | هذه وظائف المنتج الأساسية، لا دليل Runtime عليها. | اجتياز رحلات المستخدم الموثقة على جهاز. |
| P0 | ترقية Room واسترداد البيانات | لا يوجد اختبار Migration شامل؛ SQLCipher مؤجل بأمان. | fixtures وترقية 1→6 وفحص البيانات. |
| P1 | موفرات OAuth/Firebase والصوت والجدولة | الخدمات أو المفاتيح غير متاحة في بيئة التدقيق. | اختبار اتصال وفشل واسترداد وDoze. |
| P1 | أداء وRTL وإتاحة الوصول | لا توجد قياسات أو اختبارات TalkBack أو جهاز منخفض المواصفات. | تقرير قياس ومرور يدوي موثق. |

## تصنيف القرار

**🔴 NOT READY**

القرار ناتج عن حواجز إصدار حرجة غير متحققة: فشل بوابة البناء قبل التهيئة، غياب توقيع الإصدار وAPK/AAB، وغياب اختبار وقت التشغيل لمسارات المنتج الأساسية. لا يغيّر ذلك أن إصلاحات مصدرية مهمة اجتازت فحصاً ساكناً؛ لكنه يمنع أي ادعاء بجاهزية المستخدم الواقعي أو النشر العام.
