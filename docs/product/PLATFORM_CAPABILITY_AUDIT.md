# خارطة طبقات AIRI المطلوبة

## منهج التصنيف

هذا الجرد يصف ما يظهر في **مصدر AIRI ومسارات تشغيله الحالية** على `cp-foundation`. لا تكفي شاشة أو اسم فئة لاعتبار طبقة مكتملة؛ تصنف القدرة `IMPLEMENTED` عندما تملك مساراً حياً، و`PARTIAL` عندما يوجد جزء تشغيلي لكن ينقصه امتداد أو تحقق، و`MISSING` عندما لا يوجد تنفيذ المنتج المطلوب. أما `EXTERNAL_VERIFICATION_REQUIRED` فيعني أن المصدر موجود لكن يحتاج جهازاً أو credentials أو خدمة منشورة.

| # | القدرة المطلوبة | الحالة | المسار الموجود والدليل | الفجوة أو التحسين التالي |
|---:|---|---|---|---|
| 1 | تخزين واستضافة الملفات والوسائط | `PARTIAL` | مرفقات خاصة في `filesDir`، `StorageRepository`، `MediaLibrary`، وArtifact entities/preview؛ نموذج GGUF يمكن تنزيله أو استيراده محلياً. | لا توجد خدمة object storage منشورة لرفع ومشاركة الصور/الفيديو/المستندات عبر حسابات وأجهزة؛ يلزم اختيار backend وتفويض upload/resume/quota. |
| 2 | المصادقة وتسجيل الدخول | `PARTIAL` | `LoginScreen` وFirebase Auth و`SessionManager` لتحديث ID token وربط الجهاز. | تحتاج Firebase/OAuth production وإعداد Google/GitHub redirect/consent واختبار حسابات حقيقية؛ لا يعد وجود الشاشة دليلاً على مزود منشور. |
| 3 | لوحة Canvas للنماذج الأولية والهياكل السلكية | `MISSING` | لا يوجد `CanvasScreen` أو `Wireframe` أو `Prototype` في المصدر. | يجب بناؤها كمساحة عمل منفصلة: عقد/عناصر، undo/redo، export artifact، وموافقة قبل التوليد أو النشر. |
| 4 | التحكم وعرض مخرجات الطرفية | `IMPLEMENTED` محلياً | `TerminalScreen` و`TerminalRuntime` يعرضان scrollback، history، search، copy، cancel، وoutput من `SandboxExecutor`. | التنفيذ Android sandbox محدود ومحوكم؛ لا يوجد PTY/Desktop shell أو وصول نظام غير مقيد. |
| 5 | قاعدة البيانات المنظمة | `IMPLEMENTED` محلياً | Room عبر `AiriDatabase` وDAO للذاكرة والجلسات والـartifacts والتدقيق والاستخدام؛ `DatabaseLab` للقراءة فقط في Developer Center. | ليست قاعدة بيانات خدمة متعددة المستخدمين؛ مزامنة/خادم وأدوار backend تتطلب بنية خارجية. |
| 6 | أدوات المطور والقياس والتشخيص | `PARTIAL` | `DeveloperCenterScreen`، `ObservabilityScreen`، `RuntimeDiagnosticsPanel`، `ExecutionDiagnosticsState` وAudit logs. | لا يوجد telemetry backend منشور أو traces قابلة للتصدير ومراقبة fleet؛ يجب إبقاؤه local-first حتى يوافق المستخدم على الإرسال. |
| 7 | Git والتحكم بالإصدارات | `PARTIAL` | `GitRepositoryScreen` وGitHub skills/import وحراسة create_issue؛ طرفية AIRI تسمح بـ`git status/log/diff` في sandbox. | لا يوجد clone/commit/push عام من Android دون approval/resume؛ هذا حد أمان مقصود لا نقص تجميلي. |
| 8 | النمو وSEO | `MISSING` كطبقة منتج Android | لا يوجد Growth/SEO pipeline؛ أي تطابق مصدر وحيد ليس خدمة نمو. | SEO يخص موقعاً منشوراً لا تطبيق Android؛ الأولوية الصحيحة هي listing المتجر، analytics opt-in، crash/privacy metrics وASO بعد وجود خدمة نشر. |
| 9 | التكاملات | `PARTIAL` | `ConnectorsScreen` و`IntegrationsScreen` و`ConnectorBootstrap/AuthManager` وMCP registry؛ adapters لـOpenAI/Gemini/Anthropic/OpenRouter. | يحتاج كل مزود OAuth أو API key من المستخدم وscope/migration/smoke test حقيقي؛ لا تفعّل موصلاً بلا credential وموافقة. |
| 10 | المراقبة المنشورة | `PARTIAL` محلياً | observability وperformance/diagnostics محلية تعرض أحداث التنفيذ والحالة والعدادات. | لا توجد حركة مرور edge أو request metrics لخدمة منشورة لأن AIRI Android ليس خدمة مستضافة حالياً؛ يلزم backend opt-in منفصل. |
| 11 | المعاينة | `IMPLEMENTED` محلياً | `ArtifactPreviewScreen` و`WorkspaceScreen` يعرضان artifacts وملفات العمل ضمن حدود التطبيق. | معاينة تطبيق ويب منشور أو URL آمن ولقطات/compare تحتاج مسار Browser/Artifact إضافياً. |
| 12 | الأسرار | `IMPLEMENTED` محلياً | `SecretVault` و`SecureApiKeyStore` وAndroid Keystore وcapabilities مقيدة للموفر. | rotation/audit دائم وربط كل provider/connector بقبول capability ما زالا جزئيين. |
| 13 | الأمان والخصوصية | `IMPLEMENTED` لحدود المصدر | `PrivacyGuard`، `PermissionGovernanceLayer`، policies للمتصفح/البحث/device/Git، وتنقيح errors/retry. | جهاز حقيقي، مزود حقيقي، ومراجعة اختراق/production config تبقى `EXTERNAL_VERIFICATION_REQUIRED`. |
| 14 | واجهة سطر الأوامر | `PARTIAL` | الطرفية التفاعلية هي واجهة CLI داخل Android sandbox، مع command history وcancel وحوكمة. | لا يوجد binary AIRI CLI مستقل لمنصات Desktop/CI؛ يتطلب مشروعاً مستقلاً ومعايير مصادقة/configuration. |
| 15 | إعدادات المستخدم ومساحة العمل | `IMPLEMENTED` محلياً | Settings/Profile/Privacy/Voice/Model screens وDataStore/Room وWorkspace/Sandbox screens. | اختبار استعادة الإعدادات وترجمة وTalkBack على أجهزة مختلفة ما زال مطلوباً. |
| 16 | VNC | `MISSING`؛ يوجد تحكم مقترن مختلف | يوجد paired remote-control policies و`FirestoreRemoteControlAndroidAdapter`، لكنه ليس بث سطح مكتب VNC. | VNC حقيقي يحتاج desktop daemon، pairing/trust، video stream، input injection، bandwidth/latency وrevocation؛ لا يجوز تسميته VNC قبل ذلك. |
| 17 | لوحة التخطيط للمستخدم | `IMPLEMENTED` مع تحسين حديث | `PlanningDashboardScreen` و`TaskExecutionTracker` و`AgentPlanContent` يسقطان `ExecutionStatusBus` إلى مراحل وخطوات؛ الآن الحالة والزمن حيان ومترجمان وقابلان للقراءة. | يلزم ربط أعمق بسجل DurableTask/replay وخطة قابلة للتعديل، وتحقق TalkBack/rotation على جهاز. |
| 18 | محرر نصوص يبين ما يكتبه AIRI | `PARTIAL` | `SkillBuilderScreen` محرر محدود لتعريف المهارة، والطرفية تعرض الأوامر والمخرجات. | لا يوجد File/Code Editor عام مرتبط بـWorkspace مع diff، حفظ، preview، approval وtrace task→file→command. |
| 19 | خدمات خارجية ونماذج محلية على أنظمة أخرى | `PARTIAL` للتكاملات، `MISSING` للـlocal runtime خارج Android | adapters السحابية والتكاملات موجودة؛ `app-desktop` موجود لكن `DesktopCapabilities` يصرح بأن النموذج المحلي يتطلب Android native runtime. | إنشاء Llama/llama.cpp runtime خاص بـDesktop مع إدارة GGUF/ذاكرة وإلغاء وقياس ثم دعم Windows/Linux/macOS تدريجياً؛ لا يدّعي AIRI تشغيله قبل ذلك. |

## التحسين المنفذ في هذه الدفعة

أُغلقت فجوة مباشرة في البند 16 من طلب المستخدم: أصبحت لوحة الخطة تعيد حساب زمن الخطوة النشطة كل ثانية، وتستخدم موارد مترجمة للحالة والزمن، وتوفر وصفاً موحداً لقارئ الشاشة لكل خطوة وزر الإغلاق. لا تغير اللوحة مسار التنفيذ ولا تضيف أداة جديدة؛ مصدرها يبقى `ExecutionStatusBus`، وعقدها في [`AGENT_PLAN_DASHBOARD_CONTRACT.md`](AGENT_PLAN_DASHBOARD_CONTRACT.md).

## ترتيب التنفيذ المقترح

| الأولوية | الحزمة | سبب الترتيب | شرط عدم الادعاء بالإغلاق |
|---|---|---|---|
| P0 | storage/auth/backend boundary | يمنح الهوية ورفع artifacts حدوداً آمنة قبل أي مزامنة أو مشاركة. | اختيار مزود وcredentials وسياسة retention وتجربة حساب حقيقي. |
| P0 | Durable Plan + File Editor | يجعل ما يراه المستخدم في التخطيط وما يكتبه AIRI قابلاً للتتبع والمراجعة. | لا كتابة ملفات أو أوامر خارج sandbox/mوافقة المستخدم. |
| P1 | Desktop local model runtime | يحقق مطلب التشغيل المحلي خارج Android، لكنه native ومكلف في الاختبار. | لا دعم منصة قبل وجود runtime وsmoke tests على تلك المنصة. |
| P1 | Canvas/Prototype workspace | يضيف لوح نماذج أولية حقيقي بآثار قابلة للحفظ والمعاينة. | لا rendering/export قبل ownership وartifact policies. |
| P1 | Published observability + growth | مفيد فقط مع backend/إطلاق وموافقة privacy. | لا telemetry صامت ولا SEO لتطبيق Android من دون موقع. |
| P2 | VNC/remote desktop | منتج مستقل عالي المخاطر يحتاج trust/video/input lifecycle. | لا يخلط مع paired remote-control الحالي. |

## حالة موصلات الجلسة

قراءة إعدادات الجلسة في هذه المراجعة أظهرت أن موصل **GitHub** مفعل، بينما Google Ads وGoogle Calendar وGoogle Gemini وGoogle Maps وGoogle Workspace معطلة. هذه حالة موصلات جلسة العمل فقط؛ لا تثبت إعداد Android production أو تمنح AIRI credentials لتلك الخدمات.
