# خطة إصلاح سجل الوكيل وتوسعة Coding Agent في AIRI

**الحالة:** خطة معتمدة للتنفيذ اللاحق — لا تغيّر هذه الوثيقة كود التطبيق.

**نطاق الخطة:** توحيد أحداث التنفيذ في بوابة Trace Gateway، بناء سجل حي هرمي شبيه بسجل Manus من حيث وضوح مراحل التنفيذ، ثم تحويل `CodingAgent` من وكيل يولّد نصاً إلى وكيل قادر على تنفيذ مهام كود حقيقية ضمن حدود `PrivacyGuard` و`AgentSandbox` و`ToolExecutionPipeline`.

## 1. النتيجة التي يثبتها التدقيق الحالي

المشكلة ليست أن AIRI لا يملك أي سجل تنفيذ. المسار الحالي يحتوي على عدة أجزاء تعمل فعلاً:

- `ExecutionTraceBuffer` يخزن أحداثاً مرتبة بـ`sequence`، مرتبطة بـ`executionId`، ومقيدة الحجم.
- `ExecutionStatusBus` يدير دورة تنفيذ الأداة، بما فيها البدء والنجاح والفشل والإلغاء والانتظار للموافقة وإعادة المحاولة والاكتمال.
- `AgentLoop` يستدعي `onToolStarted` قبل التنفيذ، ثم `onToolCompleted` أو `onToolFailed` بعده، ويستخدم `parentId` لربط الأداة بخطوة الوكيل.
- `AgentActivityBus` يعرض أحداثاً تطبيقية عامة، لكنه قناة ثانية مستقلة عن `ExecutionTraceBuffer`.
- `ChatExecutionTraceMapper` يحوّل الأحداث إلى `AgentTrace`، لكنه يحوّل الأحداث المنتهية فقط تقريباً إلى `AgentStep`؛ أحداث التخطيط والبدء والتفكير واختيار الأداة وإرجاع النتيجة والتحقق لا تظهر كعقد مستقلة.
- `CodingAgent` الحالي لا ينفذ ملفات أو طرفية. هو يكتشف نوع المهمة، يبني prompt، ثم يصدر `Delegate` إلى `llm_backend` وينهي نفسه برسالة delegation.

إذن الوصف الأدق هو: **دورة الأداة الأساسية مسجلة، لكن بوابة الأحداث غير موحّدة، والتحويل إلى سجل المستخدم يفقد أحداثاً مهمة ويجعل البنية مسطحة، وCodingAgent ما زال طبقة توجيه نصية لا منفذاً لمهام برمجية.**

## 2. القرارات المعمارية الملزمة

### 2.1 مصدر حقيقة واحد

يصبح `ExecutionStatusBus` أو مكوّن `TraceGateway` جديد فوقه مصدر الحقيقة الوحيد لأحداث التنفيذ المرتبطة بـ`executionId`. لا يجوز أن تنشئ طبقات UI أحداثاً مشتقة تنافس الأحداث الأصلية أو أن تربط حدثاً بلا مالك بالتنفيذ الحالي.

`AgentActivityBus` يبقى قناة عرض عامة للتوافق والـDeveloper Center، لكن أحداثه الخاصة بالتنفيذ يجب أن تُنتج من `TraceGateway` لا من مراقبة نصوص الحالة. أي حدث حي قابل للعرض داخل الدردشة أو خطة التنفيذ أو سجل الوكيل يجب أن يحمل على الأقل: `executionId`، `eventId`، `sequence`، `timestampMs`، `kind`، `parentId`، `actionId`، `status`، `machineTag`، وبيانات منقحة.

هذا ينسجم مع توثيق Android: `StateFlow` مناسب للحالة الحالية، و`SharedFlow` مناسب لبث الأحداث إلى عدة مستهلكين؛ لذلك تكون snapshot الحالية في `StateFlow`، وتكون الأحداث الجديدة في `SharedFlow`، مع مخزن تاريخي محدود يعيد بناء snapshot عند دخول الشاشة. [توثيق StateFlow وSharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)

### 2.2 فصل حالة التنفيذ عن سجل الأدلة

لا يُستنتج نجاح الأداة أو فشلها من نص الرسالة. الحالة التشغيلية (`RUNNING`, `WAITING`, `COMPLETED`, `FAILED`) تكون حقلاً typed، بينما النصوص (`summary`, `detail`) أدلة منقحة للعرض. لا تُستخدم عبارات مثل `Running tool` كآلة حالات.

### 2.3 عدم عرض التفكير الخاص أو الأسرار

المطلوب للمستخدم هو سجل تقدّم قابل للفهم، وليس كشف chain-of-thought الخام. `Thinking` في السجل يعني هدفاً مختصراً، سبب اختيار المسار، أو نتيجة تحقق؛ ولا يعني بث التفكير الداخلي الحرفي للنموذج. كل summary وdetail وtool input وtool output يمر عبر `PrivacyGuard` قبل التخزين أو العرض.

### 2.4 ربط هرمي ثابت

كل تنفيذ دردشة هو جذر. داخله مراحل أو خطوات. داخل كل خطوة توجد أدوات ونتائج وتحقق. تكون العلاقة عبر `parentId` ثابت، وليس عبر ترتيب العرض فقط. إذا وصل حدث بلا أب صالح، يعرض كابن للجذر مع machine tag يدل على `orphaned_parent` ويسجل ذلك تشخيصياً دون إسقاط الحدث.

## 3. عقد Trace Gateway المقترح

يُضاف عقد داخلي واضح، مع إبقاء توافق API الحالي مؤقتاً:

```kotlin
enum class TraceEventKind {
    EXECUTION_STARTED,
    PLAN_CREATED,
    PLAN_STEP_STARTED,
    PLAN_STEP_COMPLETED,
    REASONING_NOTE,
    TOOL_REQUESTED,
    TOOL_STARTED,
    TOOL_OUTPUT,
    TOOL_COMPLETED,
    TOOL_FAILED,
    TOOL_CANCELLED,
    APPROVAL_REQUESTED,
    APPROVAL_GRANTED,
    APPROVAL_REJECTED,
    RECOVERY_STARTED,
    VERIFICATION_STARTED,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,
    EXECUTION_COMPLETED,
    EXECUTION_FAILED,
    EXECUTION_CANCELLED,
    EXECUTION_TIMED_OUT,
}
```

لا يلزم إدخال كل الأنواع في `ExecutionTraceKind` دفعة واحدة. الأولوية هي `TOOL_REQUESTED` و`TOOL_OUTPUT` و`VERIFICATION_*` و`EXECUTION_STARTED`، لأن دورة البدء والنهاية موجودة جزئياً بالفعل بينما هذه الحلقات مفقودة من السجل المرئي.

العقدة المقترحة يجب أن تضيف أو تثبت الحقول الآتية:

- `eventId` ثابت وفريد داخل التنفيذ، ولا يعتمد على UUID جديد عند كل إعادة عرض.
- `executionId` و`sessionId` و`messageId` عند توفره.
- `sequence` صادر من البوابة، لا من واجهة المستخدم.
- `parentId` و`actionId` و`toolName` منفصلان؛ لا يوضع اسم الأداة داخل `actionId` فقط.
- `status` typed، و`durationMs` عند الإتمام.
- `summary` و`detail` منقحان ومحدودا الطول.
- `inputParams` منقحة ومحدودة، ولا تحفظ payload الخام أو الأسرار.
- `outputSummary` منقح ومحدود؛ أما النتائج الكبيرة فتُخزن كأثر محلي مضبوط الصلاحيات مع مرجع آمن، لا داخل السجل.
- `errorCode` و`provenance` و`machineTag` للتحليل والتوطين دون تحليل النص.

## 4. مراحل التنفيذ بالترتيب

### المرحلة A — تثبيت العقد والاختبارات قبل تغيير العرض

الملفات الرئيسية:

- `core/ExecutionTraceBuffer.kt`
- `core/ExecutionStatusBus.kt`
- `core/ExecutionTraceArchive.kt`
- `ui/activity/ActivityEvent.kt`
- `ui/activity/AgentActivityBus.kt`

الإجراءات:

1. توحيد أسماء الأنواع والحالات، مع إبقاء دوال `onToolStarted` و`onToolCompleted` الحالية كواجهات توافق تستدعي البوابة الجديدة.
2. إضافة `TOOL_REQUESTED` قبل قبول التنفيذ الفعلي، لتمييز ما طلبه النموذج عما سمح به policy وما بدأ تشغيله.
3. إضافة `TOOL_OUTPUT` أو سجل output مختصر بعد رجوع النتيجة وقبل إعادتها إلى سياق النموذج.
4. إضافة أحداث تحقق typed، وعدم اعتبار اكتمال Graph وحده دليلاً على تحقق الإجابة.
5. جعل كل append ذرياً: فحص الملكية، تنقيح البيانات، تخصيص sequence، تحديث snapshot، ثم بث الحدث.
6. منع الأحداث المتأخرة بعد terminal، مع الحفاظ على timeout/cancel كأسباب typed لا كـ`FAILED` عام.
7. تعريف سياسة overflow صريحة: حذف تفاصيل منتصف السجل أولاً، مع إبقاء الجذر والنتيجة والفشل والتحقق النهائي.

النتيجة المطلوبة: كل دورة أداة قابلة لإعادة البناء من أحداثها دون الاعتماد على `AgentState.currentAction` أو نصوص `ActivityEvent`.

### المرحلة B — بوابة موحدة بين AgentLoop والأدوات والموصلات والمهارات

الملفات الرئيسية:

- `agent/loop/AgentLoop.kt`
- `agent/loop/tool/ToolDispatcher.kt`
- `domain/tool/ToolExecutionPipeline.kt`
- `core/RuntimeToolCatalog.kt`
- جسور connector/skill/terminal الموجودة فعلياً في المشروع.

الإجراءات:

1. إصدار `TOOL_REQUESTED` عند parse/selection مع اسم الأداة وparent step.
2. إصدار `TOOL_STARTED` بعد نجاح admission وpolicy، لا بمجرد أن يكتب النموذج tool call.
3. إصدار `TOOL_OUTPUT` بملخص آمن ومصدر النتيجة وطولها، مع وسم المحتوى الخارجي غير الموثوق.
4. إصدار `VERIFICATION_STARTED/PASSED/FAILED` عند فحص schema أو نتيجة الطرفية أو قراءة الملف أو اختبار الكود.
5. ربط كل مسار: native tool call، text protocol، parallel read، skill bridge، connector runtime، terminal، وwrite file بالبوابة نفسها.
6. التخلص التدريجي من التسجيلات المكررة التي تنتج حدثاً في `AgentActivityBus` ولا تنتج حدث Trace أصلياً.
7. إبقاء حواجز `AgentLoopSideEffectPolicy` و`AgentSandbox` و`PrivacyGuard` قبل التنفيذ؛ لا يجوز لبوابة التتبع أن تصبح طريقاً لتجاوز policy.

### المرحلة C — تحويل Trace إلى نموذج عرض هرمي

الملفات الرئيسية:

- `ai/agent/trace/ChatExecutionTraceMapper.kt`
- `ai/agent/trace/AgentTrace.kt`
- `ai/agent/trace/AgentStep.kt`
- `ui/trace/UnifiedExecutionTraceProjector.kt`
- `ui/plan/AgentPlanContent.kt`
- `ui/plan/ExecutionTracePresentation.kt`

الإجراءات:

1. استبدال `mapNotNull(::stepFor)` المسطح بمحوّل يبني شجرة أو غابة مرتبة من `parentId`.
2. إبقاء أحداث lifecycle والـreasoning والـtool request والـverification مرئية كأنواع مختلفة، لا إسقاطها لأنها ليست terminal.
3. تجميع دورة الأداة في عقدة واحدة قابلة للفتح: طلب، سماح، تشغيل، output، تحقق، terminal.
4. دعم العقد غير المكتملة أثناء البث: `RUNNING` و`WAITING` و`RECOVERING`، ثم تحديث نفس العقدة عبر `eventId` بدلاً من إضافة صفوف مكررة.
5. الحفاظ على `truncated` و`droppedSteps`، لكن تطبيق cap على العقد التفصيلية مع إبقاء ملخص الجذر والنتيجة والأخطاء.
6. فصل `success` العام عن نجاح آخر tool؛ نجاح التنفيذ لا يكون true إلا إذا اكتملت النتيجة والتحقق المطلوب.

### المرحلة D — سجل حي داخل ChatScreen وAgentPlanContent

الملفات الرئيسية:

- `ui/trace/InlineExecutionTrace.kt` والمسار الفعلي للملف إن كان مختلفاً.
- `ui/screens/ChatScreen.kt`
- `ui/plan/AgentPlanContent.kt`
- `ui/activity/ActivityFeedComposable.kt`

التصميم المطلوب:

- أثناء التوليد: بطاقة مضغوطة تعرض المرحلة الحالية وآخر عقدة نشطة وعدد الأدوات.
- عند الفتح: شجرة RTL/LTR صحيحة، جذر التنفيذ، خطواته، الأدوات التابعة، ثم verification/output.
- عقدة الأداة تعرض الاسم، الحالة، الزمن، مصدر التنفيذ، وملخصاً آمناً. تعرض المدخلات والمخرجات عند طلب المستخدم فقط.
- حالات الانتظار للموافقة تظهر كحالة blocking مع سبب واضح، لا كفشل.
- الأخطاء تعرض `errorCode` ورسالة مستخدمية منفصلة عن detail التشخيصي.
- لا يعتمد الترتيب على timestamps فقط؛ يستخدم `sequence` ثم `eventId`.
- كل عنصر في LazyColumn/LazyList له key ثابت وفريد، مثل `eventId` أو `nodeId`. توصي وثائق Compose بالمفاتيح الثابتة للحفاظ على حالة العنصر عند تغير القائمة. [توثيق قوائم Compose](https://developer.android.com/develop/ui/compose/lists)
- جمع التدفقات في Compose يستخدم `collectAsStateWithLifecycle` أو `repeatOnLifecycle`، ولا يستمر collector بلا حاجة عندما تتوقف الشاشة. [توثيق Lifecycle-aware coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)

### المرحلة E — توصيل AgentEvent من SubAgent إلى Trace Gateway

الملفات الرئيسية:

- `agent/subagent/AgentEvent.kt`
- `agent/subagent/SubAgent.kt`
- `agent/subagent/SubAgentContext.kt`
- `agent/subagent/impl/CodingAgent.kt`
- dispatcher/orchestrator الذي يجمع أحداث الوكلاء.

يجب ألا يذهب `AgentEvent` إلى UI مباشرة. يمر عبر adapter موحد يترجم:

- `Progress` إلى `REASONING_NOTE` أو `PLAN_STEP_STARTED` بحسب metadata typed.
- `Delegate` إلى `TOOL_REQUESTED` أو `MODEL_REQUESTED` مع target آمن.
- `PartialResult` إلى output مرحلي محدود، وليس إلى سجل دائم لكل token.
- `Complete` إلى `PLAN_STEP_COMPLETED` أو `EXECUTION_COMPLETED` بحسب مالك التنفيذ.
- failure/cancellation إلى الحالات typed الموافقة.

يجب أن يحمل `AgentEvent` أو سياق الحدث `executionId` و`parentId` و`actionId` اختيارياً، وألا يحاول الـadapter استنتاجها من التنفيذ النشط الحالي.

### المرحلة F — تحويل CodingAgent إلى وكيل تنفيذ كود حقيقي

هذه مرحلة لاحقة، ولا تبدأ قبل اكتمال A–E.

#### نموذج التشغيل

1. يفهم الطلب ويحدد نوع المهمة.
2. يطلب capabilities المسموح بها من catalog: قراءة ملف، بحث داخل workspace، كتابة ملف، تنفيذ اختبار/بناء، وterminal محدود.
3. يبني خطة قصيرة قابلة للتتبع.
4. يطلب الأدوات عبر `ToolExecutionPipeline`، ولا يستدعي Android `ProcessBuilder` أو filesystem مباشرة من `CodingAgent`.
5. ينفذ كل أداة داخل `AgentSandbox` مع workspace محدد وallowlist.
6. يعيد النتيجة إلى النموذج كسياق أداة منظم، ثم يقرر المتابعة أو الإصلاح أو التوقف.
7. يشغل verification: diff، syntax/build، unit tests أو فحص الملفات بحسب المهمة.
8. ينشئ final answer يذكر ما تغير، ما تم التحقق منه، وما بقي غير متحقق.

#### حدود الأدوات

- `read_file`: مسارات داخل workspace فقط، مع رفض traversal والروابط غير المسموحة.
- `list_files/search_files`: allowlist للامتدادات والمجلد، وحدود للعدد والحجم.
- `write_file/apply_patch`: لا يعمل خارج workspace، ويسجل diff، ويطلب موافقة قبل side effect إذا تطلبت السياسة ذلك.
- `terminal_execute`: قائمة أوامر مسموحة ومحددة، working directory مثبت، timeout، حد output، منع shell interpolation غير الآمن، ومنع الوصول إلى الأسرار والـcredentials.
- `run_tests/build`: policy منفصلة عن terminal العام، مع artifact للنتيجة وexit code ومدة التنفيذ.

تظل Android sandbox طبقة نظام وليست بديلاً عن policy التطبيق. Android يعزل كل تطبيق بعملية وUID وصلاحيات ملفات، لكن الوكيل يحتاج دفاعاً إضافياً داخل التطبيق لأن الأداة قد تملك صلاحيات التطبيق نفسه. [Android Application Sandbox](https://source.android.com/docs/security/app-sandbox) كما أن الملفات الحساسة الخاصة بالتطبيق مكانها التخزين الداخلي، بينما الملفات التي يريد المستخدم مشاركتها تحتاج مسار مشاركة مضبوط مثل Content Provider أو Storage Access Framework. [App-specific storage](https://developer.android.com/training/data-storage/app-specific)

يجب تطبيق التحقق والتنقيح على مدخلات ومخرجات الأدوات، لأن مخرجات الملفات والطرفية قد تحتوي على payload غير موثوق يمكن أن يعاد تفسيره كتعليمة. هذا متسق مع توجيه OWASP للتحقق من المدخلات والمخرجات لمنع injection والتنفيذ غير المقصود. [OWASP Mobile Application Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html)

## 5. ما لا ينبغي فعله

- لا نضيف `println` أو مراقبة نصية كبديل عن typed trace.
- لا نعرض chain-of-thought الخام أو كامل محتوى الملف والطرفية في سجل دائم.
- لا نجعل `AgentActivityBus` مصدراً ثانياً للحقيقة.
- لا نسمح لـ`CodingAgent` باستدعاء filesystem أو terminal مباشرة.
- لا نعطي النموذج أداة `terminal_execute` لمجرد أن المهمة برمجية؛ availability يحددها catalog وpolicy وsandbox وسياق المستخدم.
- لا نعتبر `COMPLETED` دليلاً كافياً على صحة الكود دون verification مناسب.
- لا نحتفظ بمخرجات كبيرة أو secrets داخل `ExecutionTraceBuffer` أو `AgentTrace`.

## 6. خطة الاختبارات والقبول

يجب توسيع الاختبارات الحالية، لا إنشاء نظام اختبار منفصل، وخاصة:

- `ExecutionStatusBusTest` و`ExecutionToolTraceLifecycleTest`: قبول/رفض الأحداث، التكرار، late events، cancellation، timeout، approval.
- `ExecutionTraceBufferTest`: sequence، overflow، redaction، parent links، bounded fields.
- `ChatExecutionTracePrivacyTest`: عدم تسرب الأسرار أو payload الخام عبر كل أنواع الأحداث الجديدة.
- `RuntimeTraceActivityMapperTest` و`UnifiedExecutionTraceProjectorTest`: تطابق المصدر الواحد وعدم تكرار الأحداث.
- `AgentLoopToolAdmissionTest` و`ToolExecutionPipelineTest`: أن كل tool path يصدر دورة request/start/output/terminal متسقة.
- اختبارات جديدة لـ`ChatExecutionTraceMapper`: شجرة متعددة المستويات، أدوات متوازية، event update لنفس العقدة، orphan parent، partial trace، verification failure.
- اختبارات `CodingAgent`: خطة بلا تنفيذ، قراءة، patch، اختبار فاشل ثم إصلاح، رفض المسار خارج workspace، رفض command غير مسموح، timeout، cancel، وموافقة side effect.
- اختبارات Compose/أو projector حتمية للتأكد من أن إعادة الترتيب لا تفقد حالة expanded node؛ المفاتيح الثابتة مطلوبة وفق توثيق Compose.

لا يُعتبر الإصدار ناجحاً إذا كان التطبيق يبني فقط. النجاح يعني أن سجل التنفيذ الحي والتاريخي يعرض نفس الأحداث الأساسية، وأن كل side effect قابل للتعقب إلى policy وtool ونتيجة تحقق، وأن اختبارات الخصوصية والحواجز لا تتراجع.

## 7. ترتيب الفروع والتسليم لاحقاً

يُنفذ العمل على مراحل صغيرة متسلسلة لأن المرحلة اللاحقة تعتمد على عقد المرحلة السابقة:

1. `trace-contract-gateway`
2. `trace-agent-loop-adapter`
3. `hierarchical-trace-projector`
4. `chat-live-trace-ui`
5. `coding-agent-execution`
6. `coding-agent-verification-and-hardening`

بعد كل مرحلة: تشغيل الاختبارات المرتبطة، بناء debug/release حسب إعداد المشروع، ثم انتظار CI. لا يُدمج الفرع في `main` قبل نجاح CI. وبعد نجاح المرحلة الأخيرة فقط، تُراجع سلسلة الاعتماد كاملة، ثم يُحدّث `main` بالترتيب المسموح في سير عمل المستودع.

## 8. مراجع الكود الحالية

- `app/src/main/java/com/airi/assistant/core/ExecutionStatusBus.kt`
- `app/src/main/java/com/airi/assistant/core/ExecutionTraceBuffer.kt`
- `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`
- `app/src/main/java/com/airi/assistant/agent/subagent/impl/CodingAgent.kt`
- `app/src/main/java/com/airi/assistant/ai/agent/trace/ChatExecutionTraceMapper.kt`
- `app/src/main/java/com/airi/assistant/ai/agent/trace/AgentTrace.kt`
- `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt`
- `app/src/main/java/com/airi/assistant/ui/plan/AgentPlanContent.kt`
- `app/src/main/java/com/airi/assistant/ui/activity/ActivityEvent.kt`
- `app/src/main/java/com/airi/assistant/ui/activity/AgentActivityBus.kt`

## 9. قرار البدء

لا يبدأ تنفيذ `CodingAgent` قبل إغلاق مراحل Trace Gateway والمحوّل الهرمي والواجهة الحية. السبب أن تشغيل أدوات حقيقية قبل وجود سجل موحد سيجعل الأخطاء والنتائج غير قابلة للتفسير، ويزيد خطر تسرب البيانات أو فقدان ملكية التنفيذ. البداية الصحيحة هي تثبيت عقد الحدث والاختبارات، ثم توصيل كل مسارات الأدوات، ثم بناء العرض، ثم فتح قدرات Coding Agent تدريجياً.
