# عقد التحكم بالنماذج وفرق الوكلاء

**الحالة:** `IMPLEMENTATION_COMPLETE` للتوجيه والسياسة المحلية؛ `RUNTIME_VERIFICATION_PENDING` لاستهلاك مزودات حقيقية وتشغيل Android في الخلفية.

## الغرض

يعرف هذا العقد المسار التنفيذي الذي يختار backend للنموذج، ثم يقيّم خطة متعددة الوكلاء قبل أن ينفذها `ProductionAgentOrchestrator`. لا ينشئ العقد router أو orchestrator ثانياً؛ المساران المرجعيان هما `RuntimeRouter` و`ProductionAgentOrchestrator`.

| المجال | المصدر المرجعي | الضمان المنفذ |
|---|---|---|
| اختيار النموذج | `RoutingPolicy` ثم `RuntimeRouter` | ترتيب backend، بوابات privacy/capability/network/budget، ورمز سبب ثابت مع rationale |
| التشخيص | `RuntimeEventLog` | يسجل `reason=<DecisionReason>` مع المرشحين وشرح قابل للقراءة |
| فريق الوكلاء | `ProductionAgentOrchestrator` + `SubAgentRegistry` | تنفيذ أدوار حقيقية، تبعيات، workspace للنتائج، ومسار DurableTask موجود |
| قبول الفريق | `AgentTeamPolicy` | رسم بياني صالح، حجم أقصى 12 مهمة، حد توازٍ 1–4، مشروع موحد، وcloud reserve |

## قرار النموذج

`RoutingPolicy.DecisionReason` هو العقد المنظم لتفسير الاختيار. الأمثلة تشمل `HARD_LOCAL_GATE` للخصوصية أو local-only، و`LOCAL_CAPABILITY_MISMATCH` حين لا يدعم النموذج المحلي الطلب، و`CLOUD_BUDGET_EXHAUSTED`، و`NETWORK_UNAVAILABLE`. لا يجوز للواجهة أو سجل التدقيق استنتاج السبب من تحليل `rationale` النصية؛ يستخدمان رمز السبب، بينما تبقى rationale شرحاً مترجماً/مقروءاً.

> **قاعدة الأمان:** privacy القصوى أو عدم إذن الإنترنت أو `requiresOffline` تغلق cloud قبل أي تفضيل جودة أو أداء. ولا يعيد fallback فتح مسار حُظر بسياسة محلية.

## قبول فريق الوكلاء وعزله

قبل إنشاء مهمة دائمة أو بدء موجة، يستدعي `ProductionAgentOrchestrator.executePlan` دالة `AgentTeamPolicy.admit`. ترفض السياسة الخطة الفارغة، أو التي تتجاوز 12 دوراً، أو تحتوي معرفات مكررة، أو تبعية مجهولة/ذاتية، أو سياق مشروع يخالف المشروع الأم، أو حد توازٍ خارج 1–4.

كل خطة تملك `teamCloudTokenBudget` اختيارياً. عند غيابه، تستخدم السياسة أصغر `remainingCloudTokenBudget` وارد في سياقات المهام كسقف أم للخطة. تحجز السياسة الحد الأدنى المعلن من `SubAgentCapability.costTier` لكل دور cloud معروف، وترفض الخطة إن تجاوزت الحجوزات السقف. ثم تمرر إلى كل مهمة سقفها المخصص عبر `SubAgentContext.remainingCloudTokenBudget`.

| قاعدة العزل | السلوك |
|---|---|
| العزل الافتراضي | `isolateTaskContext = true`؛ لا يقبل سياق ابن مخرجات اعتماد محملة مسبقاً |
| مشاركة نتائج الاعتماد | يحقن المنسق فقط نتائج الاعتمادات المكتملة و`AgentWorkspace` عند وقت الإرسال |
| الأسرار | لا يحمل `SubAgentContext` secret raw؛ الوصول يظل محكوماً بـ `SecretCapability` وطبقة الصلاحيات |
| الذاكرة والمعرفة | يبقى `memoryScope` و`knowledgeScope` مملوكين للخطة؛ هذه السياسة لا توسع نطاق المورد أو تنسخ محتوى خاصاً إلى الوكلاء |
| التوازي | تقسم المهام الجاهزة إلى دفعات لا تتجاوز `maxParallelTasks` ثم ينتظر المنسق الدفعة قبل التالية |

## معالجة الفشل

رفض قبول الفريق يعيد `ExecutionResult.PartialFailure` مع مفتاح الخطأ `team_policy` قبل تسجيل run أو استدعاء وكيل. يجب أن يعرض المستهلك سبب الرفض ولا يعيد المحاولة آلياً بعد تغيير policy إلا إذا عاد المستخدم أو منشئ الخطة بقيمة ميزانية/خطة صالحة.

فشل دور بعد القبول يحتفظ بسلوك المنسق القائم: يسجل الفشل في `DurableTask` ويمنع الاعتمادات غير المحلولة من البدء. لا تعني حجوزات cloud أن المزود قاس الاستهلاك الفعلي؛ هي **حد قبول وتخصيص** فقط.

## الأدلة والاختبارات

| الدليل | التغطية |
|---|---|
| `RoutingPolicyTest` | سبب local/cloud/offline/capability mismatch منظم مع ترتيب fallback |
| `AgentTeamPolicyTest` | قبول وعزل dependency، رفض minimum reserve غير الكافي، تبعية مجهولة، وحد توازٍ غير آمن |
| `:app:compileDebugKotlin` | تكامل policy ضمن المنسق ومسار Kotlin Android |

## ما لا يدعيه هذا العقد

لا يوجد بعد عداد token فعلي من كل `CloudBackend` يخصم من `ExecModePreferences` أو حجوزات الفريق؛ ولا واجهة Android لتكوين الأدوار أو عرض سقف كل دور؛ ولا تحقق device/background أو تشغيل مزودات حقيقية. تبقى هذه فجوات إغلاق صريحة، ولا تصنف كنجاح runtime أو تجاري.
