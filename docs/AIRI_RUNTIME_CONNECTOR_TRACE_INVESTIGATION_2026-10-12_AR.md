# تقرير استطلاع AIRI: الأدوات والمهارات والموصلات وسجل التنفيذ الحي

**التاريخ:** 2026-10-12  
**النطاق:** الفرع `main` من `AbdulLatef2380/AIRI-Project`  
**الرأس المفحوص:** `9c6699a92b883c4f8c19946edca3b88130114b31` (`fix: keep execution trace translations in sync`)  
**الحالة:** استطلاع وتحليل فقط؛ لم تُجرَ أي تعديلات على كود التطبيق في هذا التحقيق.

## 1. الخلاصة التنفيذية

المشروع الحالي ليس مجرد chatbot من الناحية المعمارية: توجد حلقة AgentLoop حقيقية، وكتالوج أدوات موحد، وجسر للمهارات، وجسر للموصلات، وطبقة Trace منظمة. لكن هذا لا يعني أن التجربة الفعلية مكتملة.

النتيجة الأهم هي وجود **ثلاث طبقات مختلفة يجب عدم خلطها**:

1. **التنفيذ الداخلي:** يبث أحداثاً إلى `ExecutionStatusBus` ويسجل أدلة تشخيصية.
2. **الإتاحة للنموذج:** لا تُكشف الأداة إلا إذا اجتازت intent/readiness/permission/context/budget.
3. **العرض للمستخدم:** يعتمد على `AgentPlanViewModel` و`AgentPlanOverlay` و`ActivityFeedComposable`، وهي ليست شاشة سجل تنفيذ كاملة موحدة.

### الحكم النهائي

- **سجل التنفيذ ليس ميتاً على مستوى المصدر:** AgentLoop يرسل أحداثاً كثيرة، و`ExecutionStatusBus` يخزنها في `StateFlow`، وواجهة الخطة تجمعها.
- **لكن توجد فجوة عرض مؤكدة:** المسار المربوط في Chat يعرض `AgentPlanOverlay` مختصراً لا سجل Execution Logs كاملاً؛ ويعرض آخر 3 إشارات فقط في الوضع المطوي. أما سجل التفاصيل (`AgentPlanContent`) فهو مسار آخر، مستخدم في `PlanningDashboardScreen` وليس المسار الأساسي الظاهر في Chat.
- **هناك عطل تصميمي مرجح في تجربة السجل:** لا يوجد في مسار Chat دليل حي ظاهر للمراحل المعرفية مثل "استورد هذا، بحث هنا، انتقل هنا، فكر في..." إلا إشارات عامة يرسلها AgentLoop؛ أغلب ما يظهر هو Tool/Activity مختصر. لذلك قد يبدو للمستخدم أن الطبقة صامتة حتى عندما تكون أحداثها موجودة داخلياً.
- **مشكلة الأدوات ليست غياباً عاماً للموصلات:** التثبيت والـbridge والـdispatcher موجودة وتنجح فحوص العقود. العطل المرجح هو أن الإتاحة النهائية شديدة التقييد، خصوصاً بسبب `connectorIds` و`ConnectorAccessProfile` و`state.connected && state.healthy` وميزانية prompt.
- **المصادقة ليست مساوية للإتاحة:** token أو نجاح OAuth لا يكفي وحده؛ يجب أن يوجد grant/Access Profile، وحالة صحية، وsurface صحيح، واسم أداة داخل intent. هذا سلوك أمني مقصود، لكنه غير واضح للمستخدم وقد ينتج رسالة "لا أملك أداة".
- **رسالة Google العامة تخفي السبب الحقيقي:** عدة أخطاء مختلفة (`provider failure`, `Unavailable`, `scope mismatch`, `storage`, `callback cancelled`) تُحوّل إلى feedback واحد تقريباً، مع أن حالة الاتصال قد تكون تغيرت أو بقيت جزئية.
- **المهارات متاحة تقنياً لكنها لا تُستدعى تلقائياً دائماً:** `SkillToolBridge` يبني schemas ويربط الاستدعاء، لكن الجسر يحجب skill إذا كانت readiness أو connector health أو model/memory/permission غير مكتملة. كما أن `AiriSkillOrchestrator.CompatibilityValidator` يعلن تأجيل فحص الموصلات إلى runtime ولا ينفذ فحصاً فعلياً، ما يخلق مسارين متباينين للتخطيط.
- **الوصول إلى terminal fail-closed ومقصود:** المسار موجود، لكنه لا يُسمح به إلا مع سياق تنفيذ durable صحيح، ويمر عبر sandbox والسياسة. هذا ليس دليلاً على غياب terminal، بل قد يبدو كذلك إذا لم تُعرض حالة "محجوب لغياب سياق مهمة" بوضوح.

بالتالي: **إصلاح العرض وحده سيحل صمت السجل الظاهر جزئياً، لكنه لن يحل مشاكل الأدوات/المصادقة. وإصلاح المصادقة وحده لن يجعل السجل التفصيلي ظاهراً. يلزم فصل هذه المسارات وإظهار سبب كل بوابة للمستخدم.**

---

## 2. ما تم فحصه والتحقق منه

### الملفات والمسارات الرئيسية

- `AgentLoop.kt`
- `ExecutionStatusBus.kt`
- `ExecutionTraceBuffer.kt`
- `AgentPlanViewModel.kt`
- `AgentPlanContent.kt`
- `AgentPlanOverlay.kt`
- `TaskExecutionTracker.kt`
- `ChatScreen.kt`
- `ChatViewModel.kt`
- `ToolDispatcher.kt`
- `RuntimeToolCatalog.kt`
- `RuntimeCapabilityInventory.kt`
- `SkillToolBridge.kt`
- `SkillInvocationAccessPolicy.kt`
- `AiriSkillOrchestrator.kt`
- `ConnectorToolBridge.kt`
- `ConnectorBootstrap.kt`
- `GoogleAuthService.kt`
- `GoogleConnector.kt`
- `GitHubConnector.kt`
- `IntegrationsViewModel.kt`
- `CapabilityIntentDetector.kt`

### الفحوص المحلية

| الفحص | النتيجة | الدلالة |
|---|---:|---|
| `python3 tools/verify_core_changes.py` | **96/96 PASS** | العقود الأمنية والتشغيلية العامة تمر ساكنياً |
| `python3 tools/final_runtime_integration_audit.py` | **15/15 PASS** | وجود مسار catalog → loop → dispatcher → connector/skill مثبت ساكنياً |
| `python3 scripts/connector_lifecycle_static_scan.py` | **13/13 PASS** | دورة حياة connect/disconnect تمر عبر المسار المركزي |
| `git diff --check` | PASS | لا أخطاء تنسيق |
| حالة Git | نظيفة، `main...origin/main` | لا تغييرات محلية غير مقصودة |

> هذه الفحوص لا تشغّل شاشة Android ولا تتحقق من callback حقيقي لـ Google ولا من lifetime لمكونات Compose أثناء تشغيل فعلي.

---

## 3. مسار سجل التنفيذ: ما الذي يعمل فعلاً؟

### 3.1 المصدر الداخلي للأحداث — مؤكد

في `AgentLoop.kt`:

- ينشئ `executionId` لكل تشغيل.
- يستدعي `ExecutionStatusBus.onGraphStarted(...)` قبل inference.
- يرسل `onRuntimeSignal` عند فحص القدرات، انتظار قرار النموذج، وصول الاستجابة، اختيار الأداة، وتحديد المسار.
- يرسل `onWaveStarted` و`onToolStarted` قبل التنفيذ.
- يرسل `onToolOutput` لمخرجات terminal عندما يمر output callback.
- يرسل `onToolCompleted` أو `onToolFailed` بعد التنفيذ.
- يرسل `onNodeCompleted` و`onGraphCompleted` عند النهاية.

المواضع المهمة: `AgentLoop.kt:195-265`, `304-376`, `454-537`, `675-749`, `795-828`.

في `ExecutionStatusBus.kt`:

- الحالة الحية موجودة في `MutableStateFlow<AgentState>`.
- trace موجود في `MutableStateFlow<List<ExecutionTraceEvent>>`.
- `ExecutionTraceBuffer` bounded إلى 150 حدثاً.
- الأحداث تُرفض عمداً إذا كان `executionId` فارغاً أو لا يطابق التشغيل النشط.
- أحداث الأدوات لها `actionId` لمنع terminal مكرر أو من تشغيل آخر.

المواضع: `ExecutionStatusBus.kt:34-50`, `55-72`, `119-163`, `165-214`, `340-367`؛ `ExecutionTraceBuffer.kt:9-59`.

**الاستنتاج:** لا يصح وصف النظام بأنه لا يولّد Execution Logs إطلاقاً. المصدر يولّدها، مع حماية correlation وredaction.

### 3.2 الفجوة المؤكدة في العرض

في `ChatScreen.kt:350-362` يتم إنشاء `AgentPlanViewModel`، ثم في `ChatScreen.kt:846-850` يُعرض `AgentPlanOverlay`.

لكن `AgentPlanOverlay.kt:155-181` يعرض:

- كتلة `latest signals` فقط إذا كانت `traceEntries` غير فارغة.
- آخر **3** أحداث فقط (`takeLast(3)`).
- لا يعرض قائمة Trace كاملة ولا filter ولا تفاصيل قابلة للتوسيع.

أما واجهة السجل التفصيلية فتوجد في `AgentPlanContent.kt:135-145` ثم `AgentTraceSection` في `AgentPlanContent.kt:174-271`، وفيها filters وauto-scroll وunread count وdetails. لكنها ليست المسار الذي يركبه Chat الأساسي؛ `PlanningDashboardScreen` هو المستهلك الواضح لها.

**الحكم:** إذا كان المستخدم يراقب Chat الأساسي، فالذي يراه ليس "Execution Logs" بالمعنى الذي وصفه، بل مؤشر plan مختصر. هذا يفسر تقرير "الطبقة لا تظهر/متوقفة" حتى مع وجود trace داخلي.

### 3.3 فجوة ثانية مرجحة: الإشارات لا تصف التفكير الفعلي

الإشارات الحالية في AgentLoop عامة ومحدودة:

- `Inspecting runtime capabilities`
- `Waiting for model decision`
- `Model response received`
- `Model selected an action`
- `Routing action`

لا يوجد مسار عام يرسل أحداثاً دلالية لكل خطوة من نوع:

- مصدر/ملف تم استيراده
- صفحة تم فتحها أو التنقل إليها
- نتيجة بحث مختارة
- سبب اختيار أداة على أخرى
- مرحلة skill الداخلية
- بداية/نهاية OAuth أو connector readiness

`ToolDispatcher` يرسل `AgentActivityBus` عند dispatch، و`SkillToolBridge` يكتب logcat، و`UniversalRuntimeTraceRecorder` يسجل أدلة تشخيصية، لكن هذه ليست كلها موحدة في واجهة المستخدم. لذلك قد توجد حركة داخلية لا تصل إلى feed المرئي.

### 3.4 خطر lifetime/ownership — غير مثبت كعطل، لكنه يستحق اختبار Android

`ExecutionStatusBus` singleton، بينما `AgentPlanViewModel` تابع لعمر Compose/ViewModel scope. هذا التصميم يمكن أن يعمل، لكنه يحتاج اختباراً عند:

- التنقل خارج Chat أثناء التنفيذ ثم العودة.
- إعادة إنشاء Activity/rotation.
- إنشاء أكثر من Chat/ViewModel في الوقت نفسه.
- بدء تشغيل جديد قبل انتهاء cleanup للتشغيل السابق.

`AgentPlanViewModel` يبدّل `_executionId` عندما يرى status جديداً (`AgentPlanViewModel.kt:88-95`) ويؤجل تنظيف الخطوات 4 ثوانٍ (`:96-109`). لا يوجد في الفحص الساكن اختبار lifecycle فعلي يثبت أن overlay لا يفقد الاشتراك أو يعرض trace من تشغيل سابق.

---

## 4. الأدوات والمهارات والـterminal

### 4.1 المسار الموحد موجود — مؤكد

`ChatViewModel` يجمع:

1. built-in schemas من `BuiltinTools.ALL`.
2. skill schemas من `SkillToolBridge.asToolSchemas()`.
3. connector schemas من `ConnectorToolBridge.asToolSchemas(includeUnavailable = true)`.
4. يمررها إلى `RuntimeToolCatalog.assemble()`.
5. يطبق `AgentPermissionProfile` و`ToolSchemaPromptBudget`.
6. يمرر الناتج نفسه تقريباً إلى `AgentLoop.run()`.

المواضع: `ChatViewModel.kt:2228-2304`, `2346-2443`.

`ToolDispatcher` يوجه:

- builtin إلى handlers مباشرة.
- `skill_*` إلى `SkillToolBridge`.
- `connector_*` إلى `ConnectorToolBridge` ثم `ConnectorRuntimeManager`.

المواضع: `ToolDispatcher.kt:383-419`.

**الحكم:** الادعاء القديم بأن الأدوات غير مربوطة إطلاقاً لم يعد صحيحاً على الرأس الحالي.

### 4.2 سبب مرجح لقول النموذج "لا توجد أداة"

#### أ. Connector intent ضيق

`RuntimeToolCatalog` لا يعرض connector tools إلا إذا:

- مصدرها CONNECTOR.
- readiness = AVAILABLE.
- connector ID مطلوب في intent (`RuntimeToolCatalog.kt:59-75`).

`CapabilityIntentDetector` يدعم Gmail بالعربية في حالة مثل `لخص لي آخر رسائل Gmail` (`CapabilityIntentDetector.kt`، ويدعمه اختبار `CapabilityIntentDetectorTest.kt`). لكن أي صياغة لا تطابق target/action القاموسي قد لا تضيف `CONNECTOR_READ`، فيصبح connector موجوداً في registry لكنه غير موجود في prompt.

#### ب. Access Profile منفصل عن OAuth/token

`ConnectorToolBridge.asToolSchemas()` لا يخرج إلا actions التي:

- connector مسجل.
- connected وhealthy.
- `ConnectorAccessPolicy` يقيّم profile إلى `ALLOWED`.

المواضع: `ConnectorToolBridge.kt:20-35`, `75-90`.

في Google، نجاح OAuth يحفظ scopes/token في `GoogleAuthService`، بينما profile يُختار في UI منفصلاً (`IntegrationsViewModel.kt:179-188`). لذلك:

> **Google Authorized ≠ AIRI tool exposed**

وبالنسبة إلى GitHub، وضع PAT صحيح قد يجعل connector متصلاً، لكنه لا يعفي من access profile ولا من مسار المشروع/السياق عند عمليات project-scoped.

#### ج. readiness/health

`SkillToolBridge` يمنع schema/invoke عند connector unhealthy (`SkillInvocationAccessPolicy.kt:63-72`). و`GoogleConnector` يثبت healthy فقط عندما يوجد سطح Google صحي (`GoogleConnector.kt:127-157`). إذا تم منح Gmail فقط، فسطح Gmail ينبغي أن يعمل، لكن حالة connector الكلية قد تبدو partial أو غير صحية إذا حدثت مشكلة في restore أو surface آخر.

#### د. تقليص prompt

حتى الأداة المتاحة قد تُحذف بسبب:

- `AgentPermissionProfile.filterTools`.
- `ToolSchemaPromptBudget.select`.
- context budget للنموذج المحلي.

المواضع: `ChatViewModel.kt:2275-2287` و`AgentLoop.kt:158-182`.

`ChatViewModel` يسجل counts، لكن لا يعرض للمستخدم سبب حذف أداة بعينها في واجهة مفهومة.

#### هـ. اسم الأداة غير مطابق لافتراض المستخدم

النموذج لا يستدعي `gmail_list` مباشرة؛ المسار المكشوف اسمه مولد مثل:

`connector_google_gmail_list`

من `ConnectorToolBridge.kt:87-97`.

هذا صحيح تصميمياً، لكن prompt/inventory يجب أن يكون واضحاً جداً، وإلا قد يذكر المستخدم "gmail" بينما يتوقع النموذج اسم skill قديم أو أداة غير موجودة.

### 4.3 المهارات: موجودة، لكن readiness قد يحجبها

`SkillToolBridge.asToolSchemas()` يمرر فقط المهارات التي يوافق عليها `SkillInvocationAccessPolicy` (`SkillToolBridge.kt:57-73`). والـpolicy يتحقق من:

- enabled.
- official manifest.
- Android permissions.
- required connectors healthy.
- memory/model availability.

هذا يمنع الاستدعاء غير المصرح به، لكنه يجعل الفشل يبدو للمستخدم كأن المهارة غير موجودة ما لم يظهر سبب `DENY` في UI.

يوجد مسار ثانٍ اسمه `AiriSkillOrchestrator`، لكنه ليس المسار التنفيذي الرئيسي الذي ينفذه Chat حالياً. والأهم أن `CompatibilityValidator` فيه يفحص Android permissions فقط ثم يقول إن connector check مؤجل (`AiriSkillOrchestrator.kt:130-140`, `CompatibilityValidator.kt:275-291`). هذا **تباين معماري**: planner قد يوصي بمهارة لا يسمح بها `SkillToolBridge` لاحقاً.

### 4.4 terminal: موجود لكنه fail-closed

`terminal_execute` لا يُسمح به في AgentLoop إلا إذا كان typed execution context متاحاً، ثم يمر عبر `AgentSandbox` و`guardTool` (`AgentLoop.kt:159-169`, `549-639`). وChat يحقن `executionContextFactory` (`ChatViewModel.kt:2435-2441`).

هذا يعني:

- ليس صحيحاً أن terminal غير موجود.
- ليس صحيحاً أن كل طلب terminal سيعمل؛ يجب أن يمر بسياسة context/task.
- إذا حُجب، يحتاج المستخدم رسالة دقيقة: **"حُجب لأن هذا الطلب لا يملك سياق مهمة durable"**، لا "لا أملك terminal".

---

## 5. Google/Gmail: المصادقة، الرسالة، والإتاحة

### 5.1 ما هو صحيح حالياً

`GoogleAuthService`:

- يعزل الجلسات والـscopes حسب surface.
- لا يثبت grant إلا بعد access token وحفظ encrypted scope.
- يدعم restore بعد إعادة تشغيل process.
- يرفض scope set لا ينتمي إلى surface.

المواضع: `GoogleAuthService.kt:84-120`, `124-157`, `159-243`.

`GoogleConnector`:

- مسجل فعلاً في `ConnectorBootstrap.kt:97-100`.
- يدعم Gmail list/read، إضافة إلى Calendar/Drive وغيرها.
- يرفض التنفيذ إذا لم يوجد token خاص بـ`google_gmail` (`GoogleConnector.kt:165-179`).

### 5.2 المشكلة المؤكدة في UX

في `IntegrationsViewModel.kt:430-521` تُجمع حالات كثيرة في رسالة عامة:

- sign-in failure.
- provider failure.
- callback failure.
- scope mismatch.
- unavailable.
- authorization manager failure.

ثم تعرض تقريباً نفس `authorizationFailedFeedback()`.

هذا لا يثبت أن Google OAuth نفسه مكسور، لكنه يثبت أن المستخدم لا يستطيع التمييز بين:

1. لم يتم sign-in.
2. consent لم يكتمل.
3. token لم يصدر.
4. grant حفظ لكن Access Profile غير موجود.
5. connector healthy جزئياً فقط.
6. callback وصل إلى surface خاطئ.
7. OAuth client/configuration في Google غير صحيح.

**الحكم:** الرسالة الحالية غير تشخيصية، وتفسر لماذا يرى المستخدم "تعذر إكمال تفويض بيانات Google" حتى في حالات يكون فيها جزء من الاتصال قد تم حفظه.

### 5.3 لماذا قد يكون Gmail مصادقاً لكن لا يصل إليه Agent

حتى بعد `GoogleDataAuthorization.Authorized`:

- يجب أن ينفذ `authorizationManager.onGoogleConsent(...)` بنجاح.
- يجب أن يكون profile الخاص بـ`google_gmail` مسموحاً.
- يجب أن تكون حالة surface connected/healthy.
- يجب أن يطابق intent كلمة Gmail/البريد مع action verb.
- يجب ألا تُحذف الأدوات بميزانية prompt أو permission profile.

فإذا قال المستخدم: "لخص آخر رسائل Gmail"، الاختبار الساكن يؤكد أن intent العربي مدعوم، لكن لا يوجد اختبار end-to-end يثبت أن **Authorized + profile + healthy + catalog + model tool call + Gmail API response** يعمل على جهاز حقيقي.

---

## 6. GitHub: لماذا لا تكفي عبارة "لدي وصول كامل"

`GitHubConnector` يثبت connector باستخدام PAT عبر `/user` (`GitHubConnector.kt:94-103`). لكنه لا يرسل token للنموذج، وهذا صحيح أمنياً.

الأدوات read-only لها grants منفصلة:

- list repos: Metadata read.
- list issues: Issues read.
- get file: Contents read.
- list PRs: Pull requests read.
- code search: endpoint/token compatibility.

المواضع: `GitHubConnector.kt:50-76`.

يوجد أيضاً مسار project secret مستقل للعمليات المرتبطة بمشروع (`GitHubConnector.kt:197-258`) ولا يعتمد على global PAT fallback عندما يوجد project execution context.

**النتيجة:** قول المستخدم للوكيل "لديك وصول كامل للمستودع" لا يمنح التطبيق تلقائياً صلاحية GitHub. التطبيق لا يملك صلاحية conversational claim؛ يحتاج PAT محفوظاً في المكان الصحيح، profile مسموحاً، connector healthy، وأداة exposed، ثم parameters صحيحة (`repo`, `path`, إلخ).

إذا كانت واجهة AIRI تقول إن repo مسجل كـ"أصل متاح عبر مهارات الأمان" بينما لا تظهر أداة GitHub، فهذا يرجح **خلطاً بين catalog/skill metadata وبين runtime connector exposure**، وليس بالضرورة ثغرة صلاحيات.

---

## 7. هل هذه ثغرات أمنية أم أعطال منتج؟

| الملاحظة | النوع | الخطورة | هل سدها يحل المشكلة؟ |
|---|---|---:|---|
| Trace يُبث داخلياً لكن Chat يعرض آخر 3 إشارات فقط | عطل عرض/observability | P1 منتج | يحل صمت السجل الظاهر جزئياً |
| عدم وجود feed موحد لكل runtime/skill/connector/model stage | فجوة observability | P1 منتج | يحل غياب التسلسل المفصل، لا المصادقة |
| OAuth/token لا يساوي tool exposure | بوابة أمنية صحيحة لكن UX غامض | P1 UX، ليس ثغرة بذاته | يلزم شرح/diagnostics، لا فتح الأدوات تلقائياً |
| رسائل Google تختزل أسباباً مختلفة | عطل تشخيصي | P1 UX | يحل الالتباس، لا خطأ provider حقيقي |
| connector intent يعتمد على كلمات/أفعال | هشاشة routing | P1 منتج | يحسن Gmail/GitHub discovery، لا يلغي readiness |
| Access Profile منفصل وغير ظاهر داخل Chat | فجوة product contract | P1 | يفسر "مصادق لكن غير متاح" إذا عُرض السبب |
| `AiriSkillOrchestrator` لا يفحص connector فعلياً | تباين مسارين | P2 معماري | يمنع توصيات لا يمكن تنفيذها، لكنه ليس سبب كل فشل Chat |
| terminal محجوب بدون سياق durable | سياسة أمنية مقصودة | لا تعتبر ثغرة | يلزم رسالة حالة دقيقة لا فتح المسار |
| global singleton bus مع lifecycle UI | خطر lifetime محتمل | P2 | يحتاج اختبار/تصميم ownership، غير مثبت كسبب مباشر |
| عدم وجود end-to-end Android tests | فجوة إثبات | P0/P1 جودة | ضروري لإثبات الحل، لا إصلاحاً بحد ذاته |

### ما لا يعتبر ثغرة من الأدلة الحالية

- رفض tool event عندما لا يطابق execution ID.
- عدم إظهار connector tool غير healthy.
- عدم تمرير PAT أو OAuth token للنموذج.
- منع terminal بدون durable task context.
- منع write actions بدون approval.
- تنظيف/عزل scope لكل Google surface.

هذه حدود حماية، لكن يجب عرضها للمستخدم كحالة موصوفة لا كأن القدرة غير موجودة.

---

## 8. مراجعة الادعاءات الواردة من التحليل السابق

| الادعاء السابق | الحكم على الرأس الحالي |
|---|---|
| "AIRI مجرد chatbot ولا يملك AgentLoop حقيقياً" | لم يعد صحيحاً: `AgentLoop` حقيقي ومربوط من ChatViewModel. |
| "الأدوات والمهارات غير مكشوفة للنموذج" | صحيح جزئياً فقط: schemas تُبنى وتُمرر، لكن بوابات readiness/intent/budget قد تحذفها. |
| "Google connector غير مسجل" | غير صحيح حالياً: `GoogleConnector` مسجل في Bootstrap. |
| "Gmail لا يمكن تنفيذه إطلاقاً" | غير مثبت: التنفيذ موجود، لكن لا يوجد إثبات Android end-to-end. |
| "PAT في GitHub يكفي" | غير صحيح وفق العقد الحالي: PAT + profile + health + correct action/context مطلوبة. |
| "ExecutionStatusBus لا ينتج أحداثاً" | غير صحيح على المصدر الحالي: الأحداث كثيرة ومختبرة جزئياً. |
| "Execution Logs ظاهرة كاملة في Chat" | غير صحيح: Chat يركب Overlay مختصراً، بينما full trace في Content/Dashboard. |
| "Terminal مسجل على أنه متاح دائماً" | غير صحيح: هناك filter وسياق durable وsandbox. |
| "المهارات تُنفذ عبر مسار واحد واضح" | غير صحيح تماماً: يوجد AgentLoop/SkillToolBridge ومسار AiriSkillOrchestrator أقدم/موازٍ. |

---

## 9. ما الذي يجب إثباته قبل بدء الإصلاح؟

هذه ليست خطوات إصلاح؛ إنها مصفوفة تحقق تمنع إصلاح العرض بينما يبقى runtime محجوباً.

### تجربة A: Trace حقيقي في Chat

1. تشغيل طلب بسيط بلا أداة.
2. تشغيل طلب Gmail بعد authorization/profile.
3. تسجيل `executionId` من logcat.
4. مقارنة تسلسل:
   - `EXEC_STATUS_PLANNING`
   - `TRACE kind=RUNTIME_SIGNAL`
   - `TRACE_TOOL_START`
   - `TRACE_TOOL_TERMINAL`
   - `EXEC_STATUS ...`
5. تصوير Chat أثناء التنفيذ والتحقق هل يظهر Overlay.
6. تكرار التجربة بعد rotation وخارج/عودة Chat.

### تجربة B: Gmail

حفظ جدول runtime وقت الطلب:

- Google sign-in state.
- `google_gmail` surface state.
- scope set.
- Access Profile.
- `connector_google_gmail_list` موجود في catalog؟
- موجود في `activeTools`؟
- موجود في `ToolSchemaPromptBudget` بعد القص؟
- هل model أرسل الاسم نفسه؟
- نتيجة ConnectorRuntimeManager.

### تجربة C: GitHub

- PAT legacy مقابل project secret.
- `github` profile.
- list repos/get file/list issues كل واحد منفرداً.
- صلاحية token الفعلية لكل endpoint.
- intent بالعربية والإنجليزية.

### تجربة D: skills

- skill بلا dependencies.
- skill تحتاج model.
- skill تحتاج memory.
- skill تحتاج connector healthy.
- مقارنة `SkillToolBridge.asToolSchemas()` مع توصية `AiriSkillOrchestrator`.

### تجربة E: terminal

- طلب terminal بدون durable context.
- طلب terminal داخل task context الصحيح.
- التحقق من sandbox logs وExecutionTrace وActivityFeed في الحالتين.

---

## 10. ترتيب الأولوية المقترح للمرحلة التالية

1. **P0 إثبات end-to-end على جهاز Android** لمسار Chat → AgentLoop → catalog → tool → UI؛ لا تعتمد على static PASS.
2. **P1 توحيد واجهة Execution Logs في Chat** بحيث تعرض trace الكامل أو رابطاً واضحاً له، لا آخر ثلاث إشارات فقط.
3. **P1 إضافة حالة capability diagnostics قابلة للعرض:** `not requested`, `not ready`, `not granted`, `filtered by budget`, `dispatcher unavailable`, `provider authorization required`.
4. **P1 فصل Google sign-in وdata consent وAIRI access profile في UI ورسائل مختلفة.**
5. **P1 تسجيل كل بوابة رفض في trace المرئي** مع redaction، لا في logcat فقط.
6. **P2 توحيد planner/readiness:** جعل `AiriSkillOrchestrator` يستعمل نفس readiness contract أو جعله توصية فقط دون ادعاء خطة قابلة للتنفيذ.
7. **P2 اختبار lifecycle وmulti-execution** للـsingleton bus والـViewModel.
8. **P2 تحسين intent بالعربية والمرادفات** مع الحفاظ على fail-closed للموصلات.

---

## 11. الخلاصة

المشكلات المبلغ عنها حقيقية من منظور المستخدم، لكن ليست كلها ناتجة عن غياب capability في المصدر:

- **صمت Execution Logs** ناتج بدرجة كبيرة عن فجوة بين trace الداخلي والـChat overlay المختصر، مع غياب قناة موحدة تعرض كل مراحل التنفيذ.
- **عدم استخدام Gmail/GitHub/الموصلات** ناتج غالباً عن contract متعدد البوابات لا تشرحه الواجهة: authentication، access profile، health، intent، budget، وdispatcher.
- **رسالة Google العامة** تجعل حالات فشل مختلفة تبدو متطابقة، وقد توحي بأن المصادقة فشلت بينما قد يكون token/grant محفوظاً لكن runtime غير مكشوف.
- **الـterminal والمهارات** موجودة ضمن حدود أمنية، لكن حالة الحجب لا تظهر للمستخدم بشكل صريح.

إذن، سد الثغرات التي ظهرت في هذا التقرير **سيحل جزءاً كبيراً من تجربة "AIRI مجرد chatbot"**، لكنه يتطلب إصلاحين منفصلين: إصلاح observability/UI، وإصلاح capability diagnostics/routing/auth UX. فتح الأدوات مباشرة بمجرد وجود token سيكون حلاً خاطئاً أمنياً، كما أن جعل سجل مختصر يبدو كأنه سجل كامل لن يثبت أن runtime نفذ فعلاً.


---

## 12. نتائج الجولة الثانية: فجوات أعمق في قناة الأحداث

### 12.1 يوجد ناقلان مختلفان للسجل، وليس ناقلاً واحداً

المشروع يملك مسارين منفصلين:

- `ExecutionStatusBus.trace`: سجل منظم مرتبط بـ`executionId`، وتعرضه طبقة AgentPlan.
- `AgentActivityBus.recentEvents`: feed عام للأحداث، وتعرضه `ActivityFeedComposable`.

`ChatScreen.kt:853-858` يعرض كليهما في نفس منطقة composer: `AgentPlanOverlay` ثم `ActivityFeedComposable`. هذا يخلق احتمالاً قوياً أن المستخدم يرى feed مختصراً بينما يبحث عن trace التفصيلي، أو يرى أحدهما فارغاً لأن كل واحد يستقبل أنواعاً مختلفة من الأحداث.

### 12.2 عطل correlation محتمل ومحدد في AgentActivityBus

في `AgentActivityBus.kt:55-69`، الدالة `emit(event)` لا تنشر الحدث مباشرة؛ بل تطلق coroutine غير متزامنة. وإذا كان `event.executionId` فارغاً، تُسند الهوية داخل coroutine من:

```kotlin
ExecutionStatusBus.status.value.executionId
```

هذا يعني أن الحدث الذي نشأ أثناء execution A قد يُسند إلى execution B إذا تأخر coroutine حتى بدأ تشغيل جديد. معظم مصادر الأحداث لا تمرر `executionId` صراحة، ومنها:

- `ConnectorRuntimeManager`.
- `GitHubConnector`.
- `SkillRuntime`.
- `SandboxManager`.
- `TerminalRuntime`.
- عدة إشعارات `LlamaManager`.

**الأثر:** عند استخدام `ActivityFeedComposable(executionId = activeId)` قد تختفي أحداث صحيحة من التشغيل الحالي، أو تظهر أحداث من تشغيل سابق/لاحق في المكان الخطأ. هذا تفسير تقني قوي لحالات الصمت أو عدم تطابق السجل، وليس مجرد مشكلة نصوص UI.

### 12.3 events قد تصل متأخرة حتى دون إسقاطها

`AgentActivityBus` يستخدم:

- `MutableSharedFlow` مع `replay = 50` و`extraBufferCapacity = 500`.
- coroutine producer منفصلة لكل `emit`.
- coroutine أخرى تجمع `_events` وتحدّث `recentEvents`.

يوجد `DROP_OLDEST` عند الضغط. توجد مراقبة ضغط، لكن لا يوجد اختبار يثبت أن أحداث تنفيذ قصيرة لا تُفقد أو لا تصل بعد أن يغلق overlay. لذلك فالتصميم يحاول الحماية من الضغط لكنه ليس دليلاً على التسلسل الفوري المطلوب لواجهة Execution Logs.

### 12.4 GlobalAgentEventDispatcher يعمل، لكنه لا يحول كل trace

`AIRIApplication.kt:164` يشغل `GlobalAgentEventDispatcher.start()`، إذن غياب تشغيله ليس السبب الحالي.

لكنه يحول أساساً:

- تغيرات `ExecutionStatusBus.status` إلى رسائل عامة مثل Planning/Executing/Completed.
- `AppEvent` إلى ActivityEvent.

ولا يحول `ExecutionStatusBus.trace` نفسه إلى ActivityFeed. لذلك أحداث `RUNTIME_SIGNAL` و`TOOL_OUTPUT` التفصيلية تبقى في `ExecutionStatusBus.trace` فقط، بينما ActivityFeed يعرض ملخص الحالة أو أحداث dispatch العامة. هذا يؤكد أن هناك **فقداً مقصوداً للمعلومات بين قناتي العرض**.

### 12.5 مستوى التفصيل الحالي لا يساوي طلب المستخدم

حتى عندما يعمل المسار، أقصى ما يمكن أن يراه المستخدم عادةً هو:

- `Planning: ...`
- `Executing: ...`
- `Tool dispatched: ...`
- `Executing 'google' → gmail_list`
- `GitHub: get_file`
- `Execution completed`

أما "فكّر في هذا" فلا ينبغي عرضه كنص reasoning خام لأسباب الخصوصية والثقة، لكن يمكن عرض **أحداث قرار آمنة ومحدودة** مثل:

- فحص capability.
- اختيار `connector_google_gmail_list`.
- رفض الأداة بسبب Access Profile.
- إرسال النتيجة إلى النموذج.
- الانتقال إلى الخطوة التالية.

حالياً بعض هذه الإشارات موجود في `ExecutionStatusBus`، لكنها ليست كلها في قناة UI واحدة.

---

## 13. نتائج الجولة الثانية: inventory والـreadiness

### 13.1 inventory أفضل من السابق لكنه لا يغطي كل runtime state

`RuntimeCapabilityInventory` يميز بين:

- `registered`.
- `inRequestCatalog`.
- `exposedToAgent`.
- `admittedToDispatcher`.
- حالات AUTH/PERMISSION/CONFIGURATION/DISCONNECTED/ERROR.

وهذا عقد جيد نظرياً (`RuntimeCapabilityInventory.kt:52-98`). كما أن Chat يبني تفاصيل connector إضافية في `ChatViewModel.kt:2753-2819`.

لكن هذه المعلومات تُحقن في system prompt فقط. لا يوجد دليل أن Chat يعرض نفس snapshot للمستخدم قبل أن يقال له "لا توجد أداة". لذلك النموذج قد يعرف أن الأداة `AUTH_REQUIRED`، بينما المستخدم لا يرى ذلك، أو قد يتجاهل inventory ويقدم جواباً عاماً.

### 13.2 التباين بين tool-level وsurface-level state

الـcatalog يتعامل مع أدوات connector مفردة مثل `connector_google_gmail_list`، بينما `connectorCapabilityInventoryEntries` يبني entries على مستوى `meta.id` مثل `google_gmail` أو `github`.

هذا مفيد للعرض، لكنه يفتح احتمال تضارب:

- السطح قد يكون `READY` لأن action واحد مسموح.
- أداة أخرى على السطح نفسه قد تحتاج scope مختلفاً.
- `connectorActionStatusOverrides` يضيف status لبعض Google actions فقط.
- المستخدم يرى حالة سطح عامة لا حالة الأداة المحددة.

لذلك يجب أن يكون التشخيص النهائي **tool-specific**، خصوصاً في Google حيث لكل surface scope مستقل.

### 13.3 سبب عدم ظهور مهارة قد يكون غير مطابق للسبب الحقيقي

في `skillCapabilityInventoryEntries` يتم حساب readiness بعدة طرق (`ChatViewModel.kt:2881-2945`). لكن `SkillToolBridge.asToolSchemas()` يقرر الإظهار من `SkillInvocationAccessPolicy` وقت البناء (`SkillToolBridge.kt:57-85`).

أي اختلاف زمني في:

- connector health.
- memory availability.
- model bridge.
- permission state.

يمكن أن يجعل inventory يصف المهارة بحالة مختلفة عن schema الذي بُني قبلها. لا توجد لقطة atomic مشتركة تجمع readiness وschema وdispatch admission في عملية واحدة.

**الحكم:** هذا ليس إثباتاً لعطل دائم، لكنه خطر race قابل لإنتاج "المهارة موجودة في inventory لكن غير موجودة في tool list".

---

## 14. الاستنتاج المحدّث بعد الجولة الثانية

تم الآن تحديد سببين إضافيين أكثر دقة:

1. **Correlation غير ذري في ActivityFeed:** الأحداث غير المرتبطة صراحةً بـexecutionId تُسند إلى execution الحالي لاحقاً داخل coroutine، ما قد يسبب اختفاء/خلط الأحداث عند التداخل.
2. **تقسيم trace إلى قناتين غير متكافئتين:** التفاصيل موجودة في `ExecutionStatusBus.trace`، بينما `ActivityFeed` يتلقى ملخصات وأحداثاً عامة؛ لا يوجد projection موحد لسجل التنفيذ الذي يطلبه المستخدم.

وعليه فإن التحقيق لم يعد يصف المشكلة بأنها "واجهة مختصرة فقط"؛ بل أصبحت:

> **فجوة observability مع خطر correlation race، فوقها بوابات capability/auth متعددة لا تُعرض للمستخدم كحالة واحدة.**

هذا يرفع أولوية اختبار التنفيذين المتزامنين وتجربة Chat أثناء تبديل session/rotation، قبل اختيار تصميم الإصلاح.


---

## 15. مطابقة المرفق مع التنفيذ الحالي وإصلاح الجولة الثالثة

### ما تأكد أنه نُفذ على `main` قبل هذه الجولة

المرفق متوافق مع diff الحقيقي بين الرأس السابق والرأس `cf40e22a`:

- إصلاح Gmail received-mail flow والـmetadata.
- منع fallback غير الموثق عند طلب live data.
- جعل `AgentSandbox` إلزامياً داخل AgentLoop.
- إزالة direct-dispatch legacy من AgentLoop.
- تمرير terminal عبر `TerminalExecutionGateway` مع identity أساسية.
- إضافة connector health gate داخل skill planning.
- تقييد dynamic skill endpoints/headers/templates.
- رفض remote skill غير الموقعة.

لذلك لم تُكرر هذه الأعمال ولم تُعدّل مساراتها.

### الإصلاح المنفذ الآن

تم تعديل `AgentActivityBus` بحيث يلتقط `executionId` **لحظة استدعاء `emit`** قبل وضع الحدث في coroutine غير متزامنة. سابقاً كان التقاط الهوية يحدث داخل coroutine، مما يسمح بإسناد حدث من execution A إلى execution B إذا بدأ تشغيل جديد قبل تنفيذ coroutine.

كما أضيفت دالة ownership projection نقية واختبارات JVM تحرس:

- إسناد snapshot المتزامن للحدث غير المملوك.
- عدم الكتابة فوق owner صريح.
- عدم اختراع owner عند غياب snapshot.

هذا الإصلاح لا يفتح أدوات، ولا يغير صلاحيات، ولا يغير contract الموصلات؛ إنه يصلح correlation في قناة العرض فقط.

### التحقق بعد الإصلاح

- `verify_core_changes.py`: **96/96 PASS**.
- `final_runtime_integration_audit.py`: **PASS**.
- `connector_lifecycle_static_scan.py`: **13/13 PASS**.
- `git diff --check`: PASS.
- اختبار Gradle المستهدف لم يبدأ بسبب غياب Android SDK في بيئة Sandbox:
  `SDK location not found`.

### ما بقي ولم أعدّله بعد

- typed tool-result protocol بدلاً من حقن نتيجة الأداة في conversation history كنص user.
- unified CapabilityRequest/Decision.
- readiness resolver tool-specific موحد.
- terminal owner registry وprocess cancellation proof.
- multi-trace `UniversalRuntimeTraceRecorder`.
- synchronization/actor boundary لـ`AgentObservabilityHub`.
- إزالة كل legacy producers التي لا تمرر identity.
- Android device/emulator proof.

هذه البنود أكبر من تعديل correlation الموضعي، وقد تتطلب عقوداً واختبارات تكامل قبل دمجها حتى لا تكسر AgentLoop أو provider adapters.


---

## 16. حزمة إصلاح Execution Logs والتتبع المتعدد

تم تنفيذ إصلاحين إضافيين على `main` بعد مراجعة المرفق:

### أ. سجل كامل داخل Chat

أصبح `AgentPlanOverlay` يعرض عند التوسيع نفس `AgentTraceSection` التفصيلي الموجود في لوحة التخطيط، ويشمل:

- كل أحداث trace الخاصة بالتنفيذ الحالي بدلاً من آخر ثلاث إشارات فقط.
- فلاتر الأحداث.
- auto-scroll ومتابعة آخر حدث.
- عداد الأحداث غير المقروءة.
- تفاصيل الحدث عند توفرها.

تمت إعادة استخدام `AgentPlanViewModel` و`ExecutionStatusBus` الحاليين؛ لم تتم إضافة runtime أو قناة تنفيذ ثانية.

### ب. عزل UniversalRuntimeTraceRecorder

كان recorder يحتفظ بـ`currentTraceId` وقائمة واحدة وعداد sequence واحد، لذلك كان `begin` لتشغيل جديد يمسح trace السابق، كما أن استدعاءات لاحقة لتشغيل قديم قد تُنسب إلى trace الحالي.

أصبح recorder الآن:

- يحتفظ بtrace مستقل لكل execution.
- يربط `executionId` بـ`traceId`.
- يحافظ على sequence مستقل لكل trace.
- يفرض حدّاً أقصى ثابتاً لعدد traces وعدد الأحداث في كل trace.
- يوفر `eventsForExecution(executionId)` للاسترجاع المعزول.
- يحافظ على getter `events` للتوافق، لكنه يعرض trace الحالي فقط.

وأضيف اختبار يثبت عدم اختلاط أحداث تنفيذين متزامنين.

### القيود المتبقية

اختبار Gradle/Android الفعلي ما زال يحتاج Android SDK؛ الفحوص الساكنة وحدها لا تثبت Compose rendering أو process recreation. كما أن `AgentObservabilityHub` وlegacy producers تحتاج حزمة مستقلة لاحقة بعد تثبيت عقد execution identity، حتى لا يُفرض تغيير واسع على مئات الاستدعاءات دفعة واحدة.

### ج. منع فقدان تحديثات Observability المتزامنة

تم تعديل `AgentObservabilityHub` لاستخدام `MutableStateFlow.update` الذري بدلاً من نمط القراءة ثم assignment (`_snapshot.value = _snapshot.value.transform()`). هذا يمنع فقدان تحديثات متزامنة من voice collectors وtool counters وspan lifecycle عندما تصل في الوقت نفسه. لم يتغير شكل `ObservabilitySnapshot` ولا صلاحيات أي runtime.
