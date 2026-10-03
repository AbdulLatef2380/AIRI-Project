# تدقيق جذري لمشكلة وصول النموذج إلى قدرات AIRI

**المشروع:** `AIRI-Project`  
**الفروع المدققة:** `main` و`cp-foundation`  
**تاريخ التدقيق:** 2026-10-03  
**نطاق التدقيق:** مسار المحادثة، تصنيف النية، AgentLoop، الأدوات، المهارات، الموصلات، الذاكرة، البحث، الوقت، المهام والجدولة، التنفيذ المحلي والسحابي.

## 1. الخلاصة التنفيذية

المشكلة ليست أن المشروع يفتقد كل الميزات؛ بل إن المعمارية الحالية **تسجل وتنفذ أجزاء كبيرة من الميزات، لكنها لا تقدمها للنموذج بصورة موحدة وموثوقة في كل طلب**.

المسار الحالي يشبه الآتي:

```text
رسالة المستخدم
  -> QueryClassifier
  -> ChatViewModel يقرر activeTools
  -> AgentLoop يطبق PermissionProfile مرة ثانية
  -> system prompt نصي فقط يصف الأدوات
  -> نموذج محلي/سحابي
  -> parser خاص لـ tool_call
  -> ToolDispatcher
  -> SkillToolBridge / ConnectorToolBridge / خدمات النظام
```

يوجد انقطاع حاسم في الخطوة الثانية:

- في `ChatViewModel.kt:2238-2241` يتم تعيين `activeTools = emptyList()` لكل طلب يصنف `SIMPLE` أو `CREATIVE`.
- عندها يدخل `AgentLoop.kt:190-220` في مسار **single-pass** ولا يرسل مخطط الأدوات ولا يشغل حلقة الأدوات.
- لذلك لا يستطيع النموذج استدعاء `current_time` أو `web_search` أو الذاكرة أو الموصلات، حتى لو كانت الأدوات مسجلة وقابلة للتنفيذ.

وهذا يفسر مباشرة ردوداً من نوع:

> لا أستطيع معرفة التاريخ/الوقت الحالي لعدم توفر أداة.

كما يفسر ردوداً مثل:

> لا أستطيع قراءة Gmail لعدم توفر أداة أو صلاحية.

في حالة Gmail قد تكون المشكلة **قبل** الصلاحية الفعلية: الطلب لا يمر أصلاً إلى أداة الموصل إذا لم يتعرف المصنف على أنه طلب حي/موصل.

## 2. حالة الفروع

تم التحقق من أن:

- `origin/main` و`origin/cp-foundation` يشيران حالياً إلى نفس الالتزام:
  - `1c714ba1189aa92fd74d2ae51f6f118fced11cd3`
- لا يوجد فرق إحصائي بين الفرعين في الحالة الحالية.
- مساحة العمل نظيفة ومثبتة على `main` المطابق لـ `origin/main`.

**النتيجة:** الإصلاحات يجب أن تبنى على مسار واحد؛ لا يوجد حالياً اختلاف فرعي يمكن الاعتماد عليه كحل بديل.

## 3. الأسباب الجذرية المؤكدة

### P0-1 — قطع الأدوات عن الطلبات المصنفة SIMPLE

**المواضع:**

- `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:2223-2243`
- `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt:175-220`

الكود يبني فعلاً مجموعة غنية:

```text
BuiltinTools.ALL + skillToolBridge.asToolSchemas() + connectorToolBridge.asToolSchemas()
```

لكن بعد ذلك يمسحها تماماً للطلبات `SIMPLE` و`CREATIVE`.

هذا قرار تحسين أداء غير صحيح وظيفياً؛ لأن **بساطة صياغة السؤال لا تعني أن الإجابة لا تحتاج حالة حية**. الأمثلة:

- ما التاريخ الميلادي اليوم؟
- كم الساعة؟
- ما درجة البطارية؟
- ما آخر رسالة في Gmail؟
- ماذا تتذكر عن تفضيلاتي؟
- ابحث عن سعر/خبر حالي.

كلها قصيرة، لكنها ليست plain chat.

**الأثر:**

- لا `current_time`.
- لا `web_search`.
- لا `memory_recall`.
- لا `connector_*`.
- لا حلقة tool-call.

### P0-2 — اكتشاف النية الحية محدود بصيغ حرفية وغير شامل للعربية

**الموضع:** `app/src/main/java/com/airi/assistant/ai/QueryClassifier.kt:58-99`

مصنف الوقت يعتمد على عبارات حرفية قليلة، مثل:

```text
"كم الساعة", "الساعة الآن", "الوقت الآن", "التاريخ اليوم"
```

لذلك صياغات طبيعية مثل:

- «كم اليوم بالتاريخ الميلادي؟»
- «ما تاريخ اليوم ميلادي؟»
- «أعطني التاريخ الحالي»
- «اليوم كم؟»

قد لا تطابق `LIVE_DEVICE_PATTERNS`، ثم تسقط إلى `SIMPLE`.

وبالمثل، أفعال الموصلات العربية لا تغطي أفعالاً مثل:

- لخّص
- اعرض لي آخر
- اقرأ لي
- استخرج
- راجع
- أرسل لي ملخصاً

فطلب «لخّص لي آخر رسائل Gmail» يحتوي على موصل وبيانات حية، لكنه قد لا يطابق `CONNECTOR_ACTIONS` الحالية.

**الأثر:** المصنف يرسل الطلب إلى fast/plain path قبل أن يعرف أن هناك حالة خارجية يجب قراءتها.

### P0-3 — لا يوجد مفهوم موحد لـ Capability Request / Tool Intent

حالياً القرار مبني على `QueryType` فقط تقريباً. هذا خلط بين مفهومين مختلفين:

- **شكل الإجابة:** بسيطة، تحليلية، إبداعية، تنفيذية.
- **مصادر البيانات المطلوبة:** وقت، إنترنت، ذاكرة، Gmail، تقويم، مهام، ملفات، جهاز.

يجب ألا تكون `SIMPLE` مانعاً للأدوات. المطلوب هو كائن مستقل مثل:

```text
CapabilityRequest(
  needsLiveTime = true,
  needsWeb = false,
  needsMemoryRead = false,
  needsConnector = false,
  needsDeviceState = false,
  needsSideEffect = false,
  requiredTools = [current_time]
)
```

ثم يُستخدم هذا الكائن في العرض، والصلاحيات، والتوجيه، والـ prompt، والتشخيص.

### P0-4 — الأدوات تعرض للنموذج كنص فقط، وليس كـ native tool calling موحد

**المواضع:**

- `AgentLoop.kt:175` يبني `AVAILABLE TOOLS` داخل system prompt.
- `AgentLoop.kt:722-755` يلتقط نصاً يبدأ بـ `{"tool_call"`.
- `ExecutionRequest` يحتوي `requiresToolCalling`، لكن `AgentLoop.callLLM()` لا يضبطه إلى `true` عند وجود أدوات.
- محولات السحابة (`GeminiAdapter`, `OpenAIAdapter`, `AnthropicAdapter`) تبني رسائل نصية؛ لا يوجد عقد موحد يرسل `tools` في payload الأصلي لكل مزود.

هذا يجعل نجاح استدعاء الأداة معتمداً على التزام النموذج بصيغة نصية مخصصة، خصوصاً مع النماذج المحلية أو النماذج التي تدعم function calling أصلاً.

**الأثر:** حتى بعد إصلاح activeTools، قد يرى النموذج الأدوات لكنه يجيب نصياً بأنه لا يملكها، أو ينتج JSON غير مطابق، أو يستخدم اسم أداة خاطئاً.

### P0-5 — الأدوات المسجلة ليست كلها مدعومة كعقد تنفيذي واحد

يوجد تكرار/تداخل بين:

- `BuiltinTools`.
- `AiriSkill.toolDefinitions`.
- `Connector.agentActions()`.
- `ToolCapabilitySchema` القديم.
- خرائط `ToolPermissionPolicy`.

مثال واضح: الذاكرة لها:

- `BuiltinTools.MEMORY_RECALL`.
- `MemoryManagerSkill` الذي يقدم `memory_recall` و`memory_save`.
- `ToolDispatcher` الذي يعالج `memory_recall` مباشرة.

لكن لا يوجد عقد واحد يضمن أن الاسم، schema، permission، readiness، executor، ونتيجة الفشل متطابقة دائماً.

**الأثر المحتمل:**

- أداة تظهر باسم وتنفذ باسم آخر.
- الأداة موجودة في catalog لكنها غير متاحة في `ToolDispatcher`.
- الأداة تعرض للنموذج رغم أن الخدمة غير جاهزة.
- الفشل يظهر للنموذج كرسالة عامة بدل سبب قابل للإصلاح.

### P0-6 — readiness والـ exposure لا يُداران كمرحلة واحدة

`ChatViewModel` يقرأ حالات الموصلات ويحسب `registered/executable/exposed` في مسار تشخيصي، بينما `ConnectorToolBridge` و`SkillInvocationAccessPolicy` و`ToolDispatcher` تطبق حواجزها في مراحل أخرى.

هذا يعني أن هناك عدة مصادر قرار:

1. هل الأداة أضيفت إلى `activeTools`؟
2. هل `AgentPermissionProfile` أبقاها؟
3. هل skill مفعلة؟
4. هل manifest صحيح؟
5. هل memory manager موجود؟
6. هل connector healthy؟
7. هل OAuth token موجود؟
8. هل executor يدعم الاسم؟

لكن لا يوجد `CapabilityResolver` واحد يعيد النتيجة الكاملة:

```text
AVAILABLE / BLOCKED / NOT_READY / REQUIRES_AUTH / REQUIRES_CONFIRMATION
```

### P1-1 — الإنترنت ليس قدرة عامة للنموذج، بل أدوات متفرقة مشروطة

المشروع يحتوي `SearchTool` مع Brave وDuckDuckGo وJina، لكن ذلك لا يعني أن النموذج يملك الإنترنت تلقائياً. لا بد من:

- إظهار `web_search` للطلب المناسب.
- التحقق من key/configuration.
- تنفيذ network permission وconnectivity checks.
- إعادة نتيجة منظمة مع source URLs.
- منع الادعاء بالبحث إذا لم تنفذ الأداة.

كما أن fallback الأخير `searchViaIntent` لا يعيد محتوى للوكيل؛ هو مجرد hand-off يحتاج user takeover. لذلك لا يجوز اعتباره وصولاً آلياً للإنترنت.

### P1-2 — الوقت موجود كأداة، لكن ليس كـ system fact موثوق

`BuiltinTools.CURRENT_TIME` موجود في `ToolSchema.kt:96-100`، و`ToolDispatcher` يعالجه، لكن الاعتماد على أن النموذج سيختار الأداة خطأ تصميمي لسؤال زمني مباشر.

الوقت والتاريخ والمنطقة الزمنية يجب أن تدخل في مسار deterministic:

```text
إذا CapabilityRequest.needsLiveTime:
  نفذ current_time أولاً
  أعط النتيجة للنموذج أو أجب مباشرة
```

بهذا لا يعتمد النظام على معرفة النموذج الداخلية ولا على قدرة مزود السحابة.

### P1-3 — الذاكرة موجودة كخدمة وSkill، لكن الاستدعاء ليس مضموناً

`MemoryManager` موجود، و`MemoryManagerSkill` يقدم `memory_recall` و`memory_save`، لكن:

- الطلبات البسيطة لا تصل للأداة بسبب P0-1.
- `MemoryManagerSkill` يرجع `Memory service is not available in this session` إذا لم يحقن `SkillContext.memoryManager`.
- الذاكرة الدلالية تعتمد على readiness للـ embedding، بينما الذاكرة الزمنية/النصية يمكن أن تعمل دون embedding.

يجب فصل القدرات إلى:

- `memory_read_lexical` — متاح دائماً مع قاعدة البيانات.
- `memory_read_semantic` — يتطلب embedding readiness.
- `memory_write` — يتطلب consent/policy.

ولا يجوز إسقاط كل الذاكرة لأن embedding model غير جاهز.

### P1-4 — المهام والجدولة موجودة كخدمات خلفية، لكنها ليست سطحاً واضحاً للنموذج

`ScheduledJobOrchestrator` و`ScheduledAgentWorker` موجودان ويستخدمان WorkManager، لكن `BuiltinTools` لا يحتوي أدوات واضحة مثل:

```text
schedule_task
list_scheduled_tasks
cancel_scheduled_task
run_scheduled_task
```

يوجد `TaskPlannerSkill` و`ReminderPlanningSkill`، لكن المسار يعتمد على scoring/skill routing، وليس على contract موحد يضمن أن النموذج يستطيع:

1. تحليل الطلب.
2. إنشاء preview.
3. طلب تأكيد صريح.
4. حفظ job durable.
5. عرض job id وحالته.
6. متابعة التنفيذ في الخلفية.

**الأثر:** المستخدم يرى أن “الجدولة موجودة في التطبيق”، لكن النموذج لا يملك واجهة مستقرة لاستخدامها.

### P1-5 — نموذج الصلاحيات يخلط بين نوع الاستفسار ونوع الخطر

`AgentPermissionProfile` يعتمد على `QueryType` لتحديد interactive/automation/approved proposals. هذا يجعل صلاحيات tool access مرتبطة بتصنيف لغوي قابل للخطأ.

الأصح:

```text
intent = ما الذي يريده المستخدم؟
capabilities = ما الذي يجب قراءته/استدعاؤه؟
sideEffects = هل يوجد تغيير خارجي؟
approval = هل يلزم تأكيد؟
```

سؤال بسيط قد يحتاج قراءة Gmail، لكنه لا يحتاج صلاحية كتابة. وسؤال تحليلي قد لا يحتاج أي أداة. يجب أن تكون هذه الأبعاد مستقلة.

## 4. ما الذي يعمل بالفعل

من المهم عدم إعادة بناء ما هو موجود:

- `BuiltinTools` يحتوي أدوات الوقت، البحث، قراءة التقويم، الذاكرة، الملاحظات وغيرها.
- `ToolDispatcher` يملك مساراً فعلياً لتنفيذ الأدوات.
- `SkillToolBridge` يربط skills المسجلة بالـ AgentLoop.
- `ConnectorToolBridge` يربط الموصلات الحية بالـ AgentLoop.
- `GoogleConnector` يسجل Gmail/Calendar/Drive actions ويشترط OAuth data token.
- `SearchTool` يملك Brave/DDG/Jina/direct fetch مع سياسات مصدر.
- `MemoryManager` وRAG وembedding موجودة.
- `ScheduledJobOrchestrator` يحفظ jobs ويشغل WorkManager.
- `HybridOrchestrator` يملك routing محلي/سحابي وfallback وprivacy gate.
- يوجد سجل runtime وdiagnostics وtool ledger واختبارات كثيرة نسبياً.

**المشكلة الأساسية هي composition وcontract، لا غياب كل implementation.**

## 5. الخطة المقترحة للإصلاح من الجذور

### المرحلة A — إصلاح فوري P0: منع فقدان الأدوات

1. استبدال شرط:

   ```kotlin
   if (queryType == SIMPLE || queryType == CREATIVE) emptyList()
   ```

   بمحلل قدرات مستقل.

2. إضافة `CapabilityRequest` و`CapabilityDecision` في core/domain، دون Android dependencies.

3. جعل `QueryClassifier` يعيد نتيجة غنية، أو إضافة `CapabilityIntentDetector` منفصل:

   - live time/date
   - web/current information
   - memory read/write
   - connector read/write
   - task/schedule
   - device state
   - UI automation
   - side effect

4. تطبيق fallback آمن: إذا كان الطلب سؤالاً عن حالة حية ولا يمكن تحديد الأداة بدقة، لا تستخدم plain single-pass؛ مرر أدوات القراءة الآمنة فقط.

5. إضافة patterns عربية مع normalization:

   - إزالة التشكيل.
   - توحيد الهمزات.
   - توحيد «ة/ه» عند الحاجة بحذر.
   - دعم ترتيب الكلمات والمرادفات، لا `contains` الحرفي فقط.

### المرحلة B — إصلاح عقد الأدوات P0

1. إنشاء نموذج موحد، مثلاً:

   ```kotlin
   data class RuntimeToolContract(
       val id: String,
       val schema: ToolSchema,
       val source: ToolSource,
       val requiredCapabilities: Set<Capability>,
       val readiness: Readiness,
       val permission: PermissionRequirement,
       val sideEffect: SideEffectLevel,
       val executor: suspend (ToolArguments) -> ToolResult
   )
   ```

2. جعل `BuiltinTools`, skills, connectors كلها تتحول إلى هذا العقد.

3. إزالة التكرار تدريجياً بين `ToolSchema`, `SkillToolDefinition`, و`ConnectorAgentAction` عبر adapters مؤقتة ثم deprecate.

4. بناء `CapabilityRegistry.snapshot()` يعيد الأدوات المتاحة مع السبب:

   ```text
   current_time: AVAILABLE
   gmail_read: REQUIRES_GOOGLE_DATA_AUTH
   web_search: REQUIRES_BRAVE_KEY_OR_DDG_FALLBACK
   memory_semantic: EMBEDDING_NOT_READY
   schedule_task: AVAILABLE_BUT_CONFIRMATION_REQUIRED
   ```

5. لا تعرض أداة للنموذج إلا إذا كانت قابلة للتنفيذ فعلياً، أو اعرضها مع حالة واضحة إذا كان الهدف أن يطلب النموذج authorization/confirmation.

### المرحلة C — مسار deterministic للقدرات المضمونة

لا ينبغي ترك هذه الطلبات لاختيار النموذج:

- الوقت والتاريخ والمنطقة الزمنية.
- حالة البطارية والشبكة.
- حالة الاتصال/المصادقة للموصلات.
- قراءة ذاكرة مباشرة إذا كان intent واضحاً.

التنفيذ:

```text
detect capability
 -> resolve tool
 -> execute read-only tool
 -> inject ToolResult
 -> model summarizes, or direct answer for trivial facts
```

هذا يحل مشكلة «لا أستطيع معرفة الوقت» حتى لو كان النموذج محلياً صغيراً أو cloud model لا يدعم tool calling.

### المرحلة D — Native tool calling للسحابة + fallback نصي للمحلي

1. تعريف واجهة `ToolCallingTransport`.
2. في OpenAI/Anthropic/Gemini، إرسال الأدوات في payload native عندما يدعمها endpoint/model.
3. توحيد تحويل native tool call إلى `ToolCall` الداخلي.
4. إبقاء parser النصي للمحلي والنماذج القديمة، لكن مع:

   - JSON schema صارم.
   - parser tolerant للتنسيق.
   - منع تسريب tool JSON إلى واجهة المستخدم.
   - retry واحد بإرشاد تصحيح schema عند output غير صالح.
5. ضبط `ExecutionRequest.requiresToolCalling = effectiveTools.isNotEmpty()`.
6. تسجيل transport المستخدم في runtime trace:

   ```text
   native / text-protocol / no-tools
   ```

### المرحلة E — إصلاح الذاكرة

1. فصل `memory_recall` إلى lexical وsemantic fallback.
2. جعل غياب embedding لا يمنع البحث النصي أو الذاكرة الصريحة.
3. تحديد scopes بوضوح: session / user / project.
4. إضافة tool result موحد يوضح:

   ```text
   source, count, scope, semantic=false, degraded=false
   ```

5. جعل `memory_save` دائماً side effect يحتاج سياسة واضحة، مع منع الحفظ الضمني من إجابة عادية.
6. اختبار دورة كاملة: save → new session → recall → explain → forget.

### المرحلة F — إصلاح الموصلات والإنترنت

1. إضافة `ConnectorCapabilityResolver` يميز:

   - registered
   - signed in
   - data authorized
   - healthy
   - action allowed
   - confirmation required

2. عدم إرجاع رسالة عامة مثل «لا أملك أداة» عندما السبب الحقيقي OAuth أو network؛ يجب أن يرى النموذج سبباً قابلاً للتصرف.
3. Google:

   - Gmail list/read تعمل فقط بعد data authorization.
   - Calendar read مستقل عن Gmail read قدر الإمكان.
   - Drive read مستقل.
4. Web:

   - `web_search` يعلن backend المستخدم.
   - عند غياب Brave key يجرب DDG بوضوح.
   - عند فشل كل backends يمنع النموذج من الادعاء بأنه بحث.
   - `fetch_url` يحافظ على source policy وSSRF protections.
5. إضافة أدوات `connector_status` و`list_available_connectors` للقراءة فقط، أو حقن snapshot في system context.

### المرحلة G — إصلاح المهام والجدولة

إضافة أدوات صريحة مستقرة:

- `task_create`
- `task_list`
- `task_get`
- `task_cancel`
- `schedule_once`
- `schedule_periodic`
- `schedule_run_now`

كل write/schedule يمر عبر:

```text
parse -> validate -> preview -> explicit confirmation -> persist -> enqueue -> return durable id
```

ويجب أن يشمل `ToolResult`:

- job id
- label
- next run
- network requirement
- selected model/provider
- privacy level
- approval state
- last outcome

### المرحلة H — توحيد execution context

يجب أن تحمل كل جلسة تنفيذ snapshot واحداً immutable من:

```text
ExecutionContext {
  sessionId
  userId/scope
  model/provider
  privacy level
  network state
  capability snapshot
  permission profile
  connector states
  memory readiness
  current time snapshot
}
```

ويجب تمريره إلى local/cloud/worker بدلاً من إعادة قراءة أجزاء مختلفة في كل طبقة.

## 6. الاختبارات المطلوبة قبل اعتبار الإصلاح ناجحاً

### اختبارات المصنف

- «كم الساعة؟» → `needsLiveTime=true`.
- «كم اليوم بالتاريخ الميلادي؟» → `needsLiveTime=true`.
- «ما تاريخ اليوم ميلادي؟» → `needsLiveTime=true`.
- «لخّص لي آخر رسائل Gmail» → `needsConnector=true`, connector=`google`, action=`gmail_list/read`.
- «ماذا تتذكر عني؟» → `needsMemoryRead=true`.
- «ابحث عن آخر أخبار السودان» → `needsWeb=true`.
- «ذكرني غداً...» → `needsSchedule=true`, `requiresConfirmation=true`.
- «اكتب قصة» → لا tools افتراضياً.
- «اشرح كيف يعمل...» → قد لا يحتاج tools إلا إذا طلب معلومات حالية.

### اختبارات العقد

- كل tool ظاهر للنموذج له executor فعلي.
- كل executor له schema مطابق.
- الأداة غير الجاهزة لا تظهر كمتاحة.
- OAuth failure يظهر `REQUIRES_AUTH` وليس `TOOL_NOT_FOUND`.
- missing network يظهر `NETWORK_UNAVAILABLE`.
- missing memory manager يظهر `MEMORY_UNAVAILABLE`.

### اختبارات end-to-end

1. local model + current time.
2. cloud model + current time.
3. local model + web search fallback.
4. cloud native tool calling + Gmail read.
5. local text tool protocol + Gmail read.
6. memory save/recall across session.
7. schedule preview/confirm/persist/worker.
8. connector disconnect أثناء tool call.
9. network loss بعد اختيار cloud وقبل التنفيذ.
10. model لا يدعم tools: deterministic tools تستمر، وباقي الطلبات تعرض limitation دقيقة.

### اختبارات observability

لكل رسالة يجب تسجيل:

```text
queryType
capabilityIntent
candidateTools
exposedTools
filteredTools + reasons
toolTransport
selectedBackend
connector readiness
tool calls/results
final answer provenance
```

يجب أن يستطيع Debug panel الإجابة عن السؤال: **لماذا لم ير النموذج هذه الأداة؟**

## 7. ترتيب التنفيذ المقترح

| الأولوية | العمل | النتيجة |
|---|---|---|
| P0 | إزالة إسقاط أدوات SIMPLE/CREATIVE للطلبات الحية | إصلاح الوقت والبحث والذاكرة والموصلات فوراً |
| P0 | CapabilityIntentDetector عربي/متعدد اللغات | منع السقوط الخاطئ إلى plain chat |
| P0 | RuntimeToolContract + CapabilityRegistry | مصدر قرار واحد |
| P0 | تفعيل `requiresToolCalling` وتوحيد tool loop | تقليل فشل النموذج في الاستدعاء |
| P1 | deterministic current_time/device facts | إجابات حية موثوقة حتى مع نموذج صغير |
| P1 | native cloud tool calling | رفع الاعتمادية في Gemini/OpenAI/Anthropic |
| P1 | memory lexical/semantic separation | الذاكرة تعمل دون embedding دائماً |
| P1 | connector readiness/auth diagnostics | رسائل إصلاحية دقيقة بدل “لا أداة” |
| P1 | explicit task/schedule tools | جعل الجدولة قابلة للاستدعاء من النموذج |
| P2 | unified ExecutionContext وworker parity | توحيد foreground/background |
| P2 | capability contract tests وdebug traces | منع عودة المشكلة مستقبلاً |

## 8. تعريف الانتهاء (Definition of Done)

لا يعتبر الإصلاح ناجحاً حتى تتحقق الشروط التالية:

1. سؤال الوقت القصير لا يدخل single-pass بدون `current_time`.
2. سؤال Gmail القصير يعرض أداة Gmail أو يعرض سبب authorization واضحاً.
3. كل أداة في prompt لها executor قابل للتنفيذ أو حالة readiness معلنة.
4. local وcloud يملكان نفس capability snapshot ونفس أسماء الأدوات.
5. tool calls لا تعتمد على صياغة نصية فقط عند توفر native transport.
6. الذاكرة الصريحة تعمل مع وبدون embedding model.
7. الجدولة تنتج durable job id بعد confirmation.
8. background worker يعيد استخدام نفس permission/privacy/capability contract.
9. Debug trace يثبت لماذا تم عرض/حجب كل أداة.
10. اختبارات عربية وإنجليزية تغطي synonyms وترتيب الكلمات، لا exact phrases فقط.

## 9. القرار المعماري النهائي المقترح

**لا تجعل `QueryType` هو بوابة الأدوات.**

الصيغة الصحيحة:

```text
QueryType = كيف نجيب؟
CapabilityIntent = ما الذي نحتاجه كي نجيب؟
CapabilityRegistry = ما المتاح الآن؟
PermissionPolicy = ما المسموح؟
ToolTransport = كيف نرسل الأداة للنموذج؟
ToolExecutor = كيف ننفذها؟
Provenance = من أين جاءت الإجابة؟
```

بهذا يصبح AIRI وكيلاً فعلياً يستطيع استخدام مهاراته وموصلاته وذاكرته والإنترنت والوقت والمهام، بدلاً من كونه نموذج محادثة يملك هذه الخدمات في الكود لكن لا يراها في مسار الطلب.
