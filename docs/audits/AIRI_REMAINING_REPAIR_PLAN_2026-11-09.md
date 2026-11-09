# AIRI — تقرير خطة الإصلاح المتبقية فقط

**تاريخ المراجعة:** 2026-11-09  
**النطاق:** البنود التي ما زالت مفتوحة في خطة Trace Gateway وCoding Agent وتدقيق Skills / Connectors / Tools / Terminal.  
**قاعدة هذا التقرير:** البنود المنفذة أو التي أغلقت وظيفياً حُذفت من القائمة. إذا كانت المرحلة تحتوي جزءاً منفذاً وجزءاً مفتوحاً، فالمذكور هنا هو الجزء المفتوح فقط.

## 1. الحالة التنفيذية الحالية

ما زال الإصلاح في منتصف السلسلة المعمارية، وليس عند مرحلة تشغيل Coding Agent الكامل. نقطة الإغلاق التالية ليست إضافة واجهة جديدة، بل إثبات أن كل مسارات الأدوات تنتج السجل الموحد نفسه، ثم تحويل هذا السجل إلى شجرة حقيقية، ثم توصيله بالعرض الحي، وبعد ذلك فقط فتح تنفيذ مهام الكود.

بوابة التحقق الخارجية للرأس الأخير ما زالت مفتوحة حتى اكتمال **AIRI Android CI** و **AIRI Deep Audit**. نجاح Architecture وOracle وحده لا يغلق الخطة؛ الإغلاق يتطلب نجاح البناء، اختبارات الوحدة، الاختبارات الآلية، وتدقيق المسارات.

## 2. المرحلة A — ما تبقى من عقد Trace Gateway والاختبارات

### A-1 — إغلاق العقد الموحد لاختلافات الأسماء والحالات

ما زالت هناك حاجة إلى تثبيت عقد واحد نهائي بدلاً من استمرار التعايش بين `ExecutionTraceKind` و`AgentEvent.Type` و`ActivityEvent` كعقود متوازية. يجب توثيق mapping رسمي للحالات التالية:

- طلب التنفيذ، السماح، البدء، المخرج، التحقق، والنتيجة النهائية.
- approval granted/rejected، recovery started، وplan step started/completed.
- الفرق بين `EXECUTION_COMPLETED` ونجاح آخر أداة.

يجب أن يكون لكل mapping اختبار عقدي يمنع عودة التسجيل النصي أو إنشاء event مشتق ينافس الحدث الأصلي.

### A-2 — اختبار atomicity والملكية تحت التزامن

المطلوب استكمال اختبارات متزامنة تثبت أن العملية التالية ذرية لكل حدث:

1. فحص ملكية `executionId`.
2. رفض التنفيذ بعد terminal أو cancellation.
3. تنقيح الحقول وحدودها.
4. تخصيص `sequence` و`eventId`.
5. تحديث snapshot والتاريخ.
6. بث الحدث بعد تثبيته.

يجب تغطية parallel tool calls، تكرار actionId، وصول completion متأخر، timeout أثناء output، وإلغاء بين request وstart.

### A-3 — إكمال تغطية الأرشيف والترحيل

يجب إثبات أن الأرشيف القديم والجديد يحافظان على:

- الحقول typed الجديدة عند الكتابة والقراءة.
- التوافق مع traces قديمة لا تحتوي status أو output evidence.
- عدم فقدان `parentId`, `actionId`, `inputParams`, `outputSummary`, `provenance`.
- عدم استعادة payload خام أو سر بعد round-trip.

### A-4 — تغطية ActivityBus كواجهة توافق

ما زال يلزم إثبات أن `AgentActivityBus` لا ينشئ مصدر حقيقة ثانياً. يجب اختبار أن كل activity تنفيذية يمكن ربطها بحدث trace أصلي، وأن الأحداث غير القابلة للربط تُوسم تشخيصياً كـ orphaned ولا تُستخدم لإعادة بناء حالة التنفيذ.

## 3. المرحلة B — توصيل كل مسارات الأدوات بالبوابة نفسها

### B-1 — إكمال دورة الأحداث لجميع المسارات

الدورة `request → start → output → verification → terminal` يجب إثباتها لكل مسار، لا لمسار AgentLoop الرئيسي فقط:

- native tool call.
- text-protocol tool call وإصلاح JSON.
- parallel read/tool batch.
- skill bridge وcustom skill.
- connector runtime وOAuth-backed actions.
- terminal execution.
- read/write/apply-patch.
- run-tests/build.

لكل مسار يجب أن يكون `toolName` و`actionId` و`parentId` منفصلة، وأن يحتوي output على مصدر النتيجة وطولها وحالة كونها خارجية/غير موثوقة عند الحاجة.

### B-2 — verification بعد التنفيذ الفعلي

لا يكفي تحقق الإجابة النهائية. ما زال يجب إصدار verification typed عند:

- فحص schema والـrequired arguments.
- رفض أو قبول policy.
- نتيجة terminal وexit code.
- قراءة/كتابة الملف ونتيجة diff.
- run-tests/build.
- skill/connector result قبل إعادته إلى model context.

يجب منع `COMPLETED` من التحول إلى نجاح عام إذا فشل verification المطلوب.

### B-3 — إزالة التسجيلات المكررة

يلزم إجراء inventory لكل `AgentActivityBus.emit` و`ExecutionStatusBus.appendTrace` في مسارات الأدوات، ثم إزالة النسخ التي تنتج صفين لنفس action. معيار القبول: نفس action يظهر كعقدة واحدة، ويُحدّث status/output عبر event identity بدلاً من إضافة صف جديد.

### B-4 — تثبيت حماية policy قبل Trace Gateway

يجب إضافة اختبارات تثبت أن Trace Gateway لا يتجاوز:

- `AgentLoopSideEffectPolicy`.
- `AgentSandbox`.
- `PrivacyGuard`.
- connector authorization/profile.
- terminal governance.

ويجب أن يظهر الرفض في trace كحالة typed قابلة للتفسير، لا كاختفاء للأداة أو إجابة عامة باردة.

## 4. المرحلة C — بناء نموذج Trace هرمي حقيقي

هذه المرحلة ما زالت مفتوحة لأن التحويل الحالي لا يزال يعتمد بدرجة كبيرة على terminal steps، ولا يحافظ على كل أحداث lifecycle كعقد مستقلة قابلة للتحديث.

### C-1 — تحويل mapper من قائمة مسطحة إلى شجرة

المطلوب بناء شجرة/غابة مرتبة من `parentId` مع:

- جذر execution.
- plan/step nodes.
- tool request/start/output/verification/terminal داخل parent الصحيح.
- orphan parent مع وسم تشخيصي بدلاً من إسقاط الحدث.
- ترتيب حتمي باستخدام `sequence` ثم `eventId`.

### C-2 — تحديث العقدة نفسها أثناء streaming

يجب ألا ينتج start وoutput وcompletion صفوفاً مستقلة لنفس الأداة. يجب تحديث العقدة نفسها عبر `eventId` أو action identity، مع إبقاء الحالات `RUNNING`, `WAITING`, `RECOVERING` مرئية أثناء البث.

### C-3 — إظهار lifecycle والقرارات الآمنة

يجب إبقاء `EXECUTION_STARTED`, `PLAN_CREATED`, `REASONING_NOTE`, `TOOL_REQUESTED`, و`VERIFICATION_*` مرئية كأنواع مستقلة، مع منع chain-of-thought الخام. المعروض هو الهدف المختصر، سبب المسار، والدليل المنقح فقط.

### C-4 — تصحيح success والتقليص

يجب فصل نجاح التنفيذ العام عن نجاح آخر أداة، وربط success بنتيجة التحقق المطلوبة. كما يجب تطبيق cap على العقد التفصيلية مع إبقاء root/result/error/verification، وتغطية `truncated`, `droppedSteps`, و`droppedAtIndex` في شجرة متعددة المستويات.

## 5. المرحلة D — السجل الحي داخل ChatScreen وAgentPlanContent

### D-1 — بطاقة التنفيذ الحية

أثناء التوليد يجب عرض بطاقة مضغوطة تعرض المرحلة الحالية، آخر عقدة نشطة، وعدد الأدوات/الخطوات، دون الاعتماد على نص `currentAction` كمصدر حالة.

### D-2 — واجهة الشجرة والتفاصيل عند الفتح

عند فتح البطاقة يجب عرض:

- root execution.
- plan/steps.
- الأدوات التابعة.
- output وverification.
- status typed، الزمن، المصدر، وerrorCode.

المدخلات والمخرجات التفصيلية لا تظهر إلا بطلب المستخدم، وتبقى منقحة.

### D-3 — الموافقة والأخطاء

حالة waiting approval يجب أن تكون blocking واضحة وليست failed. يجب فصل رسالة المستخدم عن detail التشخيصي، وإظهار `errorCode` بطريقة قابلة للتوطين.

### D-4 — استقرار Compose

يجب التحقق من:

- key ثابت وفريد لكل node/event.
- عدم فقدان expanded state عند وصول event جديد.
- `collectAsStateWithLifecycle` أو `repeatOnLifecycle` لكل collector.
- سلوك RTL/LTR، القوائم الطويلة، وإعادة الدخول للشاشة أثناء التنفيذ.

## 6. المرحلة E — توصيل SubAgent وAgentEvent بالبوابة الموحدة

ما زال `AgentEvent` الخاص بـSubAgent يحتاج مسار adapter رسمي إلى Trace Gateway، ولا ينبغي أن يذهب مباشرة إلى UI.

المطلوب:

- `Progress` إلى `REASONING_NOTE` أو `PLAN_STEP_STARTED` عبر metadata typed.
- `Delegate` إلى request typed مع target آمن وparent/action identity.
- `PartialResult` إلى output مرحلي محدود، لا سجل دائم لكل token.
- `Complete` إلى plan-step أو execution-completed بحسب مالك التنفيذ.
- failure/cancellation إلى حالات typed.
- حمل `executionId`, `parentId`, و`actionId` من سياق الحدث، دون استنتاجها من التنفيذ النشط الحالي.

## 7. المرحلة F — CodingAgent تنفيذ كود حقيقي

هذه المرحلة لا تبدأ قبل إغلاق A–E وبوابات CI الخاصة بها.

### F-1 — orchestration التنفيذية

يجب تحويل CodingAgent من delegation نصية إلى دورة تنفيذ حقيقية:

1. فهم الطلب وتحديد نوع المهمة.
2. طلب capabilities من catalog.
3. بناء خطة قصيرة قابلة للتتبع.
4. استدعاء `ToolExecutionPipeline` فقط.
5. تنفيذ داخل `AgentSandbox` وworkspace محدد.
6. إعادة نتيجة منظمة إلى model context.
7. تقرير المتابعة أو الإصلاح أو التوقف.
8. verification ثم final answer يوضح المنفذ والمتحقق والمتبقي.

لا يجوز لـCodingAgent استدعاء Android `ProcessBuilder` أو filesystem مباشرة.

### F-2 — حدود الأدوات

يجب تطبيق واختبار:

- `read_file`, `list_files`, `search_files` مع workspace scope وحدود الحجم/العدد ورفض traversal.
- `write_file`, `apply_patch` مع diff وموافقة side effect عند الحاجة.
- `terminal_execute` مع allowlist، working directory ثابت، timeout، output bound، ومنع interpolation والوصول إلى credentials.
- `run_tests/build` كسياسة منفصلة مع exit code وartifact ومدة.

### F-3 — حلقة الإصلاح والتحقق

يجب دعم test/build الفاشل ثم إعادة التخطيط والإصلاح ضمن عدد محاولات محدود، مع حفظ سبب الفشل والفرق الناتج والتحقق النهائي في trace.

## 8. فجوات Skills / Connectors / Tools / Terminal التي ما زالت مفتوحة

### G-1 — projection موحدة لحالة القدرة

يجب أن تحمل projection واحدة لكل skill/connector/tool/terminal الحالات التالية:

`registered → ready → selected → exposed → admitted → dispatcherRoute → executable`.

يجب أن يعرض inventory سبب الانتقال أو الرفض بدلاً من «غير موجود» أو «لا أستطيع» بشكل عام.

### G-2 — custom skill permissions وlifecycle

ما زال يلزم ربط permissions وconnectors/model/memory الموجودة في manifest بالـadapter والمنفذ بعد الحفظ، مع invalidation/version event يعيد بناء catalog والطلب التالي فوراً وبشكل واضح.

### G-3 — توحيد connector authorization

يجب دمج identity وOAuth scopes وsurface access profile في حالة واحدة قابلة للعرض للنموذج والمستخدم. لا يكفي نجاح تسجيل الدخول؛ يجب أن يظهر السطح المصرح به والسبب الدقيق لأي رفض.

### G-4 — typed arguments

يلزم محول typed موحد بين `SkillParamDef`, `ToolSchema`, connector arguments وdispatcher، يغطي int/boolean/object/list، required/default/nullability، ورسائل رفض قابلة للإصلاح.

### G-5 — دورة حياة registry

يجب التخلص من اختلاف lifecycle الناتج عن إنشاء `SkillRegistry` و`CustomSkillRepository` متعددة في ChatViewModel وPromptService وSkillService وواجهات الإدارة. المطلوب single source أو snapshot version واضحة لكل execution.

### G-6 — تقليل تكرار prompt

يجب قياس وإزالة تكرار skill descriptions وschemas وtool instructions بين PromptService وChatViewModel وAgentLoop، خصوصاً للموديلات الصغيرة، مع اختبار context budget وعدم حذف capability مطلوبة بصمت.

### G-7 — إصلاح model-facing repair

ما زال يلزم مسار إصلاح دلالي عندما:

- يرفض النموذج استخدام أداة موجودة.
- يجيب بإجابة قصيرة/باردة رغم أن الطلب يتطلب capability.
- يرسل JSON ناقصاً أو markdown حول tool call.
- يطلب capability غير exposed بسبب readiness أو permission.

الإصلاح يجب أن يعيد صياغة capability المطلوبة والأدوات المسموحة إلى النموذج دون اختراع أداة أو تجاوز firewall، مع حد محاولات وسبب نهائي واضح.

### G-8 — terminal capability negotiation

عند غياب task-owned context أو عدم جاهزية policy، يجب ألا تختفي الطرفية بصمت. ينبغي أن يعرض inventory حالة `registered/blocked/not-ready` وسبب إنشاء السياق المطلوب، مع إبقاء التنفيذ fail-closed.

### G-9 — vertical slice integration tests

يجب إضافة اختبارات fake كاملة تثبت المسار:

`OAuth/profile → catalog → readiness → permission profile → prompt → parser/repair → AgentLoop → dispatcher → result → trace → UI projection`.

يجب تنفيذها منفصلة للـskill والconnector والterminal، مع حالات unavailable، approval، malformed call، timeout، cancellation، وrestart.

## 9. ترتيب التسليم المتبقي

1. إغلاق عقد Trace Gateway والاختبارات الذرية والتوافق الخلفي.
2. إكمال توصيل كل tool paths والـverification وإزالة duplicate activity events.
3. بناء hierarchical trace mapper مع live node updates.
4. إكمال ChatScreen/AgentPlanContent live trace والـCompose lifecycle.
5. تمرير SubAgent events عبر adapter الموحد.
6. تنفيذ CodingAgent داخل pipeline/sandbox.
7. إضافة verification-and-hardening loop واختبارات vertical slice.
8. تشغيل Android CI وDeep Audit وArchitecture وOracle على كل مرحلة، ثم مراجعة السلسلة كاملة قبل اعتبار main جاهزاً للإصدار.

## 10. معيار الإغلاق النهائي

لا تُغلق الخطة عند نجاح build فقط. الإغلاق يتطلب أن:

- يرى المستخدم نفس الأحداث الأساسية في السجل الحي والتاريخي.
- كل side effect مرتبط بـpolicy وtool وresult وverification.
- لا توجد فجوة بين registered وexecutable في inventory.
- لا تظهر أسرار أو chain-of-thought أو payloadات خام.
- تعمل مهام CodingAgent الحقيقية داخل workspace محدود وبـallowlist.
- تنجح اختبارات الوحدة، vertical integration، privacy، sandbox، Compose، وCI الكامل.
