# تدقيق جذور مشكلة الوكيل والقدرات — 2026-10-10

## النطاق

تم تتبع المسار الفعلي من رسالة المستخدم إلى `CanonicalIntentClassifier` ثم `CapabilityIntentDetector` ثم `RuntimeToolCatalog` و`AgentPermissionProfile` و`AgentLoop` و`ToolDispatcher`، مع مراجعة جسور المهارات والموصلات وTerminal/ Sandbox واختبارات المشروع.

**الخلاصة:** المشكلة ليست عيباً واحداً. هناك طبقة فهم ناقصة، ثم طبقة اختيار أدوات، ثم فصل غير واضح بين المصادقة ومنح صلاحية الوكيل، ثم بروتوكول tool-calling نصي يضع عبئاً كبيراً على النماذج المحلية.

## العيوب المؤكدة أو عالية الثقة

| # | الأولوية | العيب والجذر | الدليل | الحالة |
|---:|---|---|---|---|
| 1 | P0 | سؤال «ما هي الأدوات/المهارات/الموصلات؟» لم يكن capability intent، ولذلك دخل fast path بلا أدوات. السبب أن الكاشف كان يعتمد على أفعال مثل «اعرض» ولا يملك نمط جرد عام. | `CapabilityIntentDetector.kt` قبل الإصلاح، و`ChatViewModel.kt:2269-2273` | **أُصلح** بإضافة `capabilityDiscoveryPatterns` واختبار عربي.
| 2 | P0 | wildcard الخاص بـ«الموصلات» كان يُكتشف ثم يُحذف: الكاشف يضيف connector ID فقط إذا لم تكن قيمته `*`. النتيجة أن الكتالوج يرى مجموعة موصلات فارغة ويفلتر كل actions كـ`CONNECTOR_NOT_REQUESTED`. | `CapabilityIntentDetector.kt:110-117` و`RuntimeToolCatalog.kt:59-73` | **أُصلح** بتمرير `*` صراحة.
| 3 | P0 | سؤال اكتشاف القدرات كان يتحول إلى `QueryType.ACTION` لأن `connectorRead` يدخل `possibleAction`. هذا يفعّل تعليمات الرد البارد `ACTION: ... No commentary`. | `CanonicalIntentClassifier.kt:36-64` و`DynamicPromptEngine.kt:169-175` | **أُصلح** بفصل `isCapabilityDiscovery` وتصنيفه `ANALYTICAL`.
| 4 | P1 | فهم الطلبات يعتمد أساساً على regex/substring، لا على طبقة semantic fallback. لذلك الصيغ العربية المرادفة، الأخطاء الإملائية، التشكيل، والسياق الحواري لا تُلتقط إلا إذا أضيفت يدوياً. | `CapabilityIntentDetector.kt:32-132` و`CanonicalIntentClassifier.kt:16-20` | إصلاح مرحلي: توسيع النمط؛ الإصلاح الكامل يحتاج semantic intent pass محدوداً قبل اختيار الأدوات.
| 5 | P1 | توجد خريطتا تصنيف متوازيتان: `QueryClassifier` يحتوي قواعد قديمة لكنه يفوض إلى `CanonicalIntentClassifier`، بينما بعض المستهلكين ما زالوا يقرأون أسماء/قواعد الطبقة القديمة. هذا يخلق drift ويجعل إصلاح قاعدة في مكان لا يؤثر على caller آخر. | `QueryClassifier.kt:5-80` و`CanonicalIntentClassifier.kt:7-84` | يحتاج توحيد API وإزالة القواعد الميتة.
| 6 | P0 | نجاح OAuth/المصادقة لا يمنح Agent access profile تلقائياً. `ConnectorToolBridge` لا يعرض إلا actions الممنوحة، و`invoke` يرفض `NOT_GRANTED`. لذلك عبارة «الخدمة مصادق عليها» لا تعني «أداة الوكيل متاحة». | `ConnectorToolBridge.kt:7-8,27-35,45-57,75-85` | فجوة UX/contract؛ يجب عرضها للمستخدم كخطوتين واضحتين أو إنشاء read profile صريح بعد موافقة المستخدم.
| 7 | P1 | `asToolSchemas(includeUnavailable=true)` لا يعرض actions غير الممنوحة، بل يعرض فقط `onlyGranted=true`. لذلك لا يستطيع النموذج رؤية schema أو سبب المنع لكل action، ولا يستطيع توجيه المستخدم إلى grant محدد. | `ConnectorToolBridge.kt:22-29` | يحتاج catalog منفصلاً لـdiscovery مع readiness، دون جعله executable.
| 8 | P1 | runtime inventory يلخص connector readiness، لكن قائمة الأدوات التي تصل فعلياً إلى النموذج تمر عبر `RuntimeToolCatalog` ولا تشمل connector إلا عند `AVAILABLE` وطلب ID مطابق. هذا يخلق فرقاً بين «64 موصلاً في التشخيص» و«صفر tool schemas قابلة للاستدعاء». | `RuntimeToolCatalog.kt:63-75` و`ChatViewModel.kt:2289-2304` | يحتاج عرض صريح: registered/authenticated/granted/healthy/exposed لكل سطح.
| 9 | P1 | skill schema لا يُبنى إلا للمهارات التي تمر `SkillInvocationAccessPolicy.Allow`. المهارات المحجوبة تختفي بالكامل من prompt، فلا يعرف النموذج اسمها أو سبب عدم جاهزيتها، فيجيب «لا أملك المهارة» بدلاً من «المهارة موجودة وتحتاج X». | `SkillToolBridge.kt:57-70` | يحتاج discovery schema غير تنفيذي للحالات غير الجاهزة.
| 10 | P1 | جسر المهارات يعتمد على readiness وقت إنشاء `ChatViewModel`/وقت الطلب، بينما `activeSkillCount` محسوب مرة واحدة في property initialization. تغيّر المصادقة أو الأذونات بعد إنشاء ViewModel لا ينعكس في العداد إلا بإعادة إنشاء VM. | `ChatViewModel.kt:584-588` و`SkillToolBridge.kt:57-84` | يحتاج StateFlow/refresh عند تغير registry أو connector state.
| 11 | P0 | بروتوكول tool-calling الأساسي نصي خاص بـAIRI (`{"tool_call":...}`)، وليس native function/tool calling. كل نموذج، خصوصاً المحلي الصغير، مطالب بإنتاج JSON دقيقاً بلا prose؛ أي انحراف يدخل retry أو يتحول إلى جواب عادي. | `AgentLoop.kt:94-105,342-420` و`TextToolCallProtocol.kt` | يحتاج adapter capability: native tools عند دعم المزود، وgrammar/JSON constrained decoding للمحلي، مع fallback النصي.
| 12 | P1 | `AgentLoop.callLLM` يطلب `requiresToolCalling=true` لكنه لا يثبت أن backend المختار يدعمها؛ التوجيه يعتمد على request flags، بينما بعض adapters قد تتعامل معها كنص عادي. | `AgentLoop.kt:862-884` ومسار `HybridOrchestrator`/backends | يحتاج contract capability check قبل routing ورفض/إعادة توجيه واضح.
| 13 | P1 | حد `maxTokens=1024` ثابت لكل agent loop. schema طويل لمهارات وموصلات مع JSON arguments قد يصطدم بالحد أو يقطع reasoning/tool call، خصوصاً عند النماذج الصغيرة. | `AgentLoop.kt:862-869` | يحتاج budget حسب schema/step مع حد أدنى مضمون لنداء الأداة.
| 14 | P1 | اختيار المهارات يطابق الاسم والوصف ببحث token substring فقط (`schemaMatchesRequest`)، ولا يستخدم intent slots أو aliases العربية. لذلك مهارة صحيحة قد تكون exposed لكنها لا تُرتب أمام النموذج. | `RuntimeToolCatalog.kt:91-118` | يحتاج aliases/metadata multilingual وترتيب دلالي حتمي قبل النموذج.
| 15 | P1 | terminal لا يدخل الأدوات إلا إذا احتوى الطلب target محدداً من قائمة صغيرة (`terminal`, `shell`, `sandbox`...) وفعل تنفيذ من قائمة صغيرة. صيغ مثل «نفّذ هذا في بيئة الأوامر»، «استخدم الطرفية لقراءة...» أو الإحالة السياقية قد تفشل. | `CapabilityIntentDetector.kt:90-99,131-132` و`RuntimeToolCatalog.kt:88` | يحتاج terminal intent slots وcontext carry-over، مع إبقاء التنفيذ fail-closed.
| 16 | P1 | terminal قد يُعرض في catalog ثم يُحجب ثانية داخل AgentLoop بسبب `executionContextFactory` أو `TerminalExecutionPolicy.evaluate("echo")`. لا يوجد في prompt عقد موحد يميز `exposed`, `context-ready`, `policy-ready`, `executable`. | `AgentLoop.kt:158-169` و`AgentLoopTaskRuntime.kt` | يحتاج readiness state موحداً وإظهار سبب الحجب بدلاً من «غير موجود».
| 17 | P1 | `SkillInvocationAccessPolicy` يحتاج connector health لكل dependency، لكن الصحة لا تعني بالضرورة scope الصحيح أو access profile للعملية المطلوبة. نتيجة ذلك skill قد تختفي عند وجود connector مصادق لكنه غير health/permission-synchronized. | `SkillInvocationAccessPolicy.kt:63-73` و`SkillToolBridge.kt:75-84` | يحتاج readiness facts موحّدة: auth, scopes, profile, health, action.
| 18 | P2 | لا يوجد اختبار تكامل واحد يمرر رسالة عربية من classifier إلى schema إلى dispatcher الحقيقي لconnector/skill/terminal. الاختبارات الحالية تمنع regressions صغيرة لكنها لا تثبت السلسلة الكاملة. | `app/src/test` ونتيجة `verify_core_changes.py` | يحتاج contract/instrumentation tests مع fakes حقيقية للمسارات الأربعة.

## الإصلاحات المنفذة في هذه الجولة

1. أضيف wildcard `*` إلى `connectorIds` بدلاً من إسقاطه.
2. أضيفت صيغ capability discovery العربية والإنجليزية، بما فيها سؤال الأدوات والمهارات والموصلات.
3. أضيف `CapabilityIntentDetector.isCapabilityDiscovery`.
4. صُنّفت أسئلة جرد القدرات كـ`ANALYTICAL/QUESTION` لا `ACTION`، لمنع تعليمات الرد المختصر.
5. أضيفت اختبارات تمنع عودة عيب wildcard وعيب التصنيف.

## التحقق

- `:app:testDebugUnitTest` مع الاختبارات المستهدفة: **نجح**.
- `python3 tools/verify_core_changes.py`: **96/96**.
- أول تشغيل للجذر فشل فقط لأنه مرر `--tests` إلى وحدة `core-domain` التي لا تحتوي هذه الاختبارات؛ إعادة التشغيل الصحيحة على `:app` نجحت.
- لم أعتبر تدقيق الوكلاء المتوازي نتيجة؛ توقف بسبب حد الاعتمادات، لذلك الأدلة أعلاه مبنية على قراءة المستودع ومسارات caller الفعلية.

## ترتيب العمل التالي

**P0:** توحيد عقد discovery/executable، فصل auth عن access grant بواجهة واضحة، وإضافة native/grammar tool calling.

**P1:** توحيد المصنف، readiness facts، dynamic refresh للمهارات/الموصلات، terminal intent slots، وbudget تكيفي.

**P2:** اختبارات التكامل ومسار التشخيص UI الذي يعرض لكل capability: مسجلة، مصادق عليها، منحها المستخدم، صحية، مكشوفة، قابلة للتنفيذ.
