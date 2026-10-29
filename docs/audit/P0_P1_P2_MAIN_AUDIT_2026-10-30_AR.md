# تدقيق تنفيذ P0 / P1 / P2 على `main`

تاريخ التدقيق: 2026-10-30  
الفرع المراجع: `main`  
آخر commit وقت التدقيق: `7a5c705e`

## الحكم التنفيذي

الفرع الحالي يحتوي على تنفيذ فعلي واسع لـ P0 وP1، وليس مجرد خطة أو placeholders. توجد اختبارات وحدة لمسارات القبول، حالات الموصلات، ميزانية السياق، النية الموحدة، الاستدعاء الأصلي، والسياسة الجانبية. لذلك لا ينبغي إعادة بناء هذه الطبقات من الصفر.

التصنيف الحالي:

| الأولوية | الحالة | الدليل | المتبقي |
|---|---|---|---|
| P0 منع الردود المضللة | مكتمل وظيفيًا | `CapabilityAssemblyResult`، فشل المصدر المطلوب، `CapabilityDecision`، و`CONTEXT_BUDGET_BLOCKED` في `ChatViewModel` | اختبار تكاملي فعلي على مسار UI/LLM، لا يمكن تشغيله هنا دون Android SDK/جهاز |
| P0 النية الموحدة | مكتمل جزئيًا إلى كبير | `ResolvedIntent` يمرر نفس projection إلى القبول والتشخيص، واختبارات عربية/إنجليزية | ما زالت بعض درجات الترتيب تعتمد على lexical matching داخل الكتالوج |
| P1 حماية الأدوات المطلوبة | مكتمل | `requiredToolNames` و`ToolSchemaPromptBudget` يمنعان إسقاط الأداة المطلوبة بصمتًا | إضافة اختبار صريح يثبت `CONTEXT_BUDGET_BLOCKED` من نقطة ChatViewModel إن لزم |
| P1 سياسة الأثر | مكتمل من ناحية التصنيف، جزئي من ناحية التنفيذ | `ToolSchema.sideEffectPolicy` و`AgentLoopSideEffectPolicy`؛ الأدوات الجديدة لا تُعامل كقراءة تلقائيًا | مسارات التنفيذ typed لبعض الكتابات ما زالت محكومة بمصانع runtime الخاصة بها، وهذا مقصود أمنيًا وليس fallback صامتًا |
| P1 التعافي الذاتي | مكتمل | القرار يعتمد على error code، يمرر `originalInput`، ويمنع retry للكتابات ذات النتيجة غير المؤكدة دون idempotency | يلزم اختبار end-to-end لمزود حقيقي |
| P2 native tool calling | منفذ | OpenAI/Gemini/Anthropic native envelopes تُوحّد إلى `NativeToolCallWire` ثم تمر بالـvalidator والـdispatcher، مع text fallback | تغطية local provider native غير مثبتة؛ يلزم عقد provider واضح قبل ادعاء دعمها |
| P2 القراءة المتوازية | منفذ ومحسّن | `ToolCallBatchExecutor` يشغل القراءات المستقلة بالتوازي ويُبقي الكتابات تسلسلية | أضيف الآن فحص اعتماديات المعلمات واختبار يمنع التوازي عندما تشير قراءة إلى `call` سابقة |
| P2 التتبع | منفذ جزئيًا | مراحل `INTENT_*` و`CAPABILITY_*` و`TOOL_*` و`RESULT_RETURNED` و`MODEL_CONTINUATION` موجودة | بعض الحقول المطلوبة مثل connectorId وسبب القرار ليست مرفقة في كل intent-stage، ومرحلة FINAL_RESPONSE ليست موحدة في كل return path |

## التغييرات المنفذة في هذه المراجعة

1. منع `ToolCallBatchExecutor` من افتراض استقلال القراءات لمجرد أنها `READ_ONLY`.
2. رفض التوازي عند وجود IDs فارغة أو مكررة.
3. رفض التوازي عندما تحتوي معلمات استدعاء لاحق على مرجع إلى `call` سابق بصيغ `$call-id` أو `{{call-id}}` أو `call_id=call-id`.
4. إضافة اختبار يثبت أن القراءة التابعة تُنفذ تسلسليًا.
5. توثيق حدود الدعم بدل اعتبار كل مسار provider أو runtime متحققًا تلقائيًا.

## ما لم أفعله عمدًا

- لم أغيّر عقد الموصلات أو أختلق API جديدًا.
- لم أفتح صلاحيات الكتابة أو أتجاوز الموافقات.
- لم أعدّل native provider payloads دون عقد رسمي.
- لم أعتبر غياب Android SDK فشلًا في منطق P0/P1/P2.
- لم أنفذ push في هذه الخطوة قبل تشغيل بوابات التحقق.

## مصفوفة الأدلة المطلوبة قبل الإغلاق النهائي

| السيناريو | نتيجة الكود الحالية |
|---|---|
| GitHub متصل وقراءة مسجلة | يظهر schema المطلوب ويُمرر للـdispatcher |
| GitHub بلا scope | قرار `AUTH_REQUIRED` ولا يُرسل كأنه غير موجود |
| GitHub غير متصل | قرار `DISCONNECTED` مع إجراء reconnect |
| فشل بناء schema | `Failed` إذا كان المصدر مطلوبًا، و`Degraded` إذا لم يكن مطلوبًا |
| حذف الأداة بسبب السياق | `CONTEXT_OMITTED` أو `CONTEXT_BUDGET_BLOCKED` بدل الادعاء بعدم وجودها |
| طلب كتابة بلا معلمات | strict validation / clarification path |
| فشل قراءة شبكي | retry مسموح وفق error code والسياسة |
| فشل كتابة بنتيجة مجهولة | لا retry تلقائي بلا idempotency |
| ذكر GitHub داخل طلب إبداعي | اختبارات intent تمنع فتح connector عند غياب dependency حية |
| قراءتان مستقلتان | overlap مثبت باختبار |
| قراءة تعتمد على قراءة سابقة | التسلسل مثبت بالاختبار الجديد |

## الخلاصة

لا توجد فجوة P0 واضحة تبرر إعادة تصميم كاملة. العمل المتبقي الحقيقي هو رفع التغطية التكاملية، واستكمال حقول التتبع النهائية، وتحديد عقد native للـlocal runtime. التغيير البرمجي الحالي محدود بحدود التوازي الآمن ولا يوسّع صلاحيات الأدوات أو يتجاوز الموافقات.

## تدقيق مسار استدعاء النماذج المحلية والسحابية

تم التحقق من أن `AgentLoop` يبني `ExecutionRequest` موحدًا يحتوي على `toolDefinitions` و`requiresToolCalling` لكل backend. النماذج السحابية تحول هذه التعريفات إلى native wire envelopes الخاصة بالمزود، ثم تطبعها طبقة `NativeToolCallWire` إلى صيغة مشتركة قبل الـdispatcher.

أما llama.cpp المحلي فواجهة JNI الحالية (`LlamaManager.generateStream`) تستقبل نصًا فقط ولا تعرض عقد native function-calling مستقلًا. لذلك لا يصح الادعاء بأن local يستخدم OpenAI/Gemini native wire format. تم ربطه الآن بعقد `LocalToolCallPromptContract` الذي يمرر نفس تعريفات الأدوات إلى prompt المحلي، ويفرض نفس الصيغ canonical:

- `{"tool_call":{"name":"...","args":{...}}}`
- `{"tool_calls":[...]}` للاستدعاءات المتعددة المرتبة.

بعد ذلك يستخدم `AgentLoop` نفس parser والتحقق الصارم والـToolDispatcher، مع بقاء الصلاحيات والموافقات موحدة. ويُرفض المسار المحلي fail-closed إذا طُلب tool calling دون tool definitions معتمدة.

بهذا أصبح الاستدعاء موحدًا وظيفيًا وأمنيًا بين المحلي والسحابي، مع احترام الفرق الحقيقي بين native provider wire format في السحابة وtext envelope عبر JNI المحلي.
