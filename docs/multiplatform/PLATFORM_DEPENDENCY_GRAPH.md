# رسم تبعيات المنصة في AIRI

## نتيجة الفحص الآلي

شغّل `scripts/airi_platform_dependency_scan.py` على `cp-foundation` بعد دمج خط Android المرجعي. فحص التقرير **822 ملف مصدر**: `412` ملفاً يحمل إشارات Android مباشرة، و`290` ملفاً يحمل إشارات native/JNI/C++، و`132` مرشحاً أولياً للمشاركة، و`5` ملفات تتطلب عزل API خاص بالـJVM. هذه أرقام كشف أولي وليست نسب إعادة استخدام نهائية؛ الملف المصنف `COMMON_CANDIDATE` لا يصبح مشتركاً قبل أن يترجم في `commonMain` ويجتاز اختباراته.

| إشارة حد المنصة | عدد الملفات | القراءة المعمارية |
| --- | ---: | --- |
| `android.*` | 320 | Android framework متسرب عبر طبقات التطبيق الحالية. |
| `androidx.*` | 137 | Compose وlifecycle وWorkManager وRoom مرتبطة بملف Android الحالي. |
| `Context` | 212 | أكبر مصدر لاقتران المنصة؛ يجب منعه من الدخول إلى النواة. |
| `Intent` | 48 | يظل في adapters الخاصة بـ Android أو يتحول إلى domain command محايد. |
| Android filesystem | 35 | acquisition للمرفقات والتخزين يحتاج عقوداً واضحة. |
| WorkManager | 14 | جدولة Android لا تُنقل؛ تستبدل بعقد Scheduler في طبقة المنصة. |
| Room | 4 | persistence Android قائم لكنه ليس عقد التخزين المشترك. |
| JNI/C++ | 290 | runtime النماذج المحلي حد native صريح ولا يُنقل كملف Android. |
| JVM file APIs | 51 | لا تدخل `commonMain`؛ تحتاج `jvmMain` أو عقد filesystem حقيقي. |

## الرسم الحالي وحدود النقل

```mermaid
flowchart TD
    UI[UI Android: Compose / Activities / Screens] --> Presentation[Presentation: ViewModels / UI state]
    Presentation --> Application[Application: use cases / coordinators]
    Application --> Agent[Agent runtime: planning / execution / policies]
    Agent --> Memory[Memory & RAG: admission / retrieval / repositories]
    Agent --> Routing[Model routing / provider fallback]
    Routing --> Providers[Cloud providers / SSE / connectors]
    Routing --> LocalModel[Local model runtime]
    Memory --> Persistence[Persistence]
    UI --> AttachmentAcquire[Attachment acquisition]
    Application --> Scheduling[Scheduling / notifications]
    Application --> Auth[Authentication / OAuth]

    UI -. Android Compose, Activity, Context .-> Android[Android platform]
    Presentation -. Lifecycle, viewModelScope .-> Android
    AttachmentAcquire -. Uri, ContentResolver, picker .-> Android
    Scheduling -. WorkManager, notifications .-> Android
    Persistence -. Room / DataStore .-> Android
    Auth -. App links, Android OAuth .-> Android
    LocalModel -. JNI / NDK / libairi_native.so .-> AndroidNative[Android native runtime]

    Agent --> Core[Future AIRI Core]
    Memory --> Core
    Routing --> Core
    Application --> Core
```

> **قاعدة الحدود:** تتجه التبعيات من تطبيق المنصة إلى النواة. لا يسمح لأي package في `core` باستيراد `android.*` أو `androidx.*` أو Room أو WorkManager أو JNI أو APIs ملفات JVM. النواة تعرف العقد والنماذج والسياسات فقط؛ توفر المنصة implementations وتدخلها عند التكوين.

## أماكن التسرب ذات الأولوية

| الطبقة الحالية | التسرب المرصود | الضرر إذا نُقل كما هو | الحد الصحيح | الإجراء المتسلسل |
| --- | --- | --- | --- | --- |
| UI | Compose Android، `LocalContext`، launchers، `Activity` | واجهة غير قابلة للبناء خارج Android. | `ui-android`، ثم UI مشترك اختياري لاحقاً. | لا تنقل UI في Gate 2. |
| Presentation | `ViewModel`، lifecycle scopes، Android dispatchers | lifecycle مختلف بين سطح المكتب وWeb. | presentation state خالص + host adapters. | أخرج state reducers لاحقاً، لا ViewModels الحالية. |
| Agent | بعض الملفات خالصة وبعض tools مرتبطة بـContext/files | تسرب قدرات غير مسموحة للمنصات الأخرى. | `core-agent` + `PlatformToolRegistry`. | ابدأ بالنماذج والسياسات فقط. |
| Memory | Room وDAOs وfilesystem Android بجانب admission/RAG | نقل database Android يربط core بقاعدة واحدة. | `core-memory` + `MemoryRepository` contract. | استخرج `MemoryAdmissionPolicy` وnormalizer قبل repository. |
| Routing | cloud policies خالصة لكن HTTP والـSSE مزودان حاليان | مكدس شبكة Android قد يتسلل إلى core. | `core-routing` + `ModelProvider` contract. | استخرج `RoutingPolicy` وerror model أولاً. |
| Local models | `external fun` و`System.loadLibrary` وC++ | Android JNI لا يعمل على Windows/Linux/Web. | `PlatformModelRuntime`. | أبق Android runtime في platform-android؛ نفذ Desktop فقط بعد إثبات native build. |
| Attachments | `Uri` و`ContentResolver` وAndroid picker | مسارات وpermissions غير قابلة للحمل. | `AttachmentRef` + acquisition adapters. | استخرج policy والتحقق، لا acquisition. |
| Scheduling | WorkManager وnotifications | لا يوجد تنفيذ مماثل تلقائياً عبر كل منصة. | `PlatformScheduler`. | وثّق semantics أولاً، ثم adapter لكل منصة. |
| Authentication | Android redirect وsecure storage | مخاطر state/token عند نقل callbacks بلا تصميم. | `PlatformWebAuth` و`SecureTokenStore`. | حافظ على PKCE/state في core، callback/storage في platform. |
| Voice | microphone وTTS وSTT Android | نموذج permissions/media يختلف جذرياً. | `PlatformVoiceEngine`. | state/models المشتركة أولاً؛ engines لاحقاً. |

## نواة قابلة للاستخراج أولاً

أثبت Gate 2A وGate 2B نقل `MemoryAdmissionPolicy` و`MemoryTextNormalizer` إلى `core-domain/commonMain`، ويستهلكهما Android عبر `MemoryManager` و`MemoryEvolutionEngine` مع اختبارات `commonTest` وبناء JVM/Android. تظل `domain/AttachmentPolicy.kt` و`execution/ExecutionGenerationGate.kt` و`execution/router/RoutingPolicy.kt` ونماذج execution/agent مرشحين لاحقين؛ لكل منها تحليل transitive مستقل قبل النقل لأن تصنيف الملف وحده لا يثبت portability.

لا تُنقل مسارات تبدو "خالصة" بمجرد الاسم؛ يراجع كل Gate imports والتبعيات transitive وواجهته العامة واختبار `commonTest` قبل النقل. ملفات accessibility أو نماذج UI models المصنفة آلياً مرشحة للفحص لا للاستخراج المباشر، لأن استخدامها downstream قد يكون Android-specific.

## حواجز فرض مستمرة

سيضيف `scripts/airi_cross_platform_health.py` في Gate 2 فحوصاً للحالات التالية: Android imports في `core/**/commonMain`، اعتماد source set عكسي، غياب `actual` عند وجود `expect`، إعلان قدرة لمنصة لا تمتلك اختبار قبول، وتكرار implementation منصي يمكن أن يكون منطق أعمال مشتركاً. فاحص التبعيات الحالي هو الدليل الأساسي لنقطة البداية وتُعاد تشغيله عند كل milestone.
