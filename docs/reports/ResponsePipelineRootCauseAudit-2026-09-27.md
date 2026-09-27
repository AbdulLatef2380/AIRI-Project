# تدقيق جذري لمشكلة عدم ظهور ردود AIRI

**التاريخ:** 2026-09-27
**النطاق:** النموذج المحلي، المزودون السحابيون، التوجيه، AgentLoop، الأدوات، الذاكرة/RAG، الموصلات، persistence، وواجهة Compose.
**حالة التعديلات:** لم تُعدّل ملفات الإنتاج أثناء هذا التحقيق.

## النتيجة الحاسمة

أقوى سبب مثبت من الشيفرة هو وجود مسار يتعامل مع **الاستجابة الفارغة كنجاح** ثم يمنع طبقة المحادثة إنشاء رسالة مساعد لها:

1. `LlamaManager` يستطيع إرسال `onComplete("")` عند الإلغاء قبل بدء JNI، كما يسمح باكتمال أصلي بلا رموز بعد تسجيل `GENERATION_EMPTY`.
2. `LocalLlamaBackend` يحوّل ذلك إلى حدث إكمال ناجح.
3. `HybridOrchestrator` يعتبر أي `onComplete` مقبول نجاحًا، من دون التحقق من أن النص غير فارغ.
4. `AgentLoop` يعيد buffer فارغًا.
5. `ChatViewModel` لا يضيف رسالة المساعد إلا إذا كان `finalAnswer.isNotBlank()`.
6. `finishGeneration()` يمسح حالة البث.

النتيجة المرئية تطابق تمامًا ظهور رسالة المستخدم مع اختفاء أي رد مساعد دائم. هذه السلسلة **مثبتة ساكنًا** من الكود، لكن لا يمكن الجزم من دون سجل تشغيل أي محفّز وقع في الحالة الحالية: إلغاء مبكر، EOS بلا رموز، استثناء JNI، stale generation، خطأ SSE، أو ضغط سياق.

الصورة وحدها ليست دليلًا كافيًا على غياب كل ردود المساعد؛ تصميم AIRI يعرض رد المساعد بلا مستطيل بنفسجي، كما أن الصورة تتضمن عناصر نشاط/تنفيذ قد تحجب جزءًا من المحادثة. لذلك يجب الجمع بين سجل دورة الطلب وبيانات Room وStateFlow قبل إعلان السبب النهائي.

## خريطة المسار الكامل

### 1. تفعيل النموذج

- المحلي: `ChatViewModel.selectModel()` → `ModelController.loadModel()` → `ModelManager` → `ModelLoader`/`LlamaManager` → JNI.
- لا يصبح `ModelManager.currentModel` صالحًا إلا بعد callback نجاح التحميل.
- JNI يحرر السياق السابق، يحمّل GGUF، وينشئ سياقًا ثابتًا `n_ctx=1536`.
- السحابي: `CloudModelStore` و`ChatViewModel` يكتبان المزود والنموذج والمفتاح ووضع التنفيذ في أكثر من مخزن، ثم ينشطان `RemoteModelRegistry`.

المراجع: `ModelController.kt:86-195`، `ModelManager.kt:12-41`، `LlamaManager.kt:470-566`، `LlamaBridge.cpp:1035-1230`، `ChatViewModel.kt:2215-2296`.

### 2. قبول الرسالة وبناء السياق

بوابة الإرسال تسمح بالطلب عندما يكون النموذج المحلي جاهزًا أو يوجد remote نشط. تحفظ رسالة المستخدم أولًا في Room ثم تضيفها إلى StateFlow. بعد ذلك يُبنى سياق RAG والذاكرة والأدوات.

المشكلة هنا أن بعض الأعطال تتحول إلى قيمة فارغة بلا تمييز بين «لا توجد نتائج» و«الخدمة غير جاهزة»؛ مثل فشل RAG أو عدم تحميل embedding model.

المراجع: `ChatViewModel.kt:1465-1517,1587-1642`، `RagRetriever.kt:131-174`، `MemoryManager.kt:226-249`.

### 3. AgentLoop والأدوات

المسار هو:

```text
ChatViewModel
  → AgentLoop
  → HybridOrchestrator
  → RuntimeRouter
  → LocalLlamaBackend أو CloudBackend
  → callbacks
  → AgentLoop
  → ChatViewModel
  → Room + StateFlow + Compose
```

`AgentLoop` لديه مسار single-pass بلا أدوات، أو حلقة تصل إلى 8 خطوات. بروتوكول الأدوات الحالي نصي ومخصص: يبحث عن البادئة الحرفية `{"tool_call"`، يحصي الأقواس، يحوّل الوسائط إلى Strings، ولا يحفظ call ID. هذا لا يطابق أدوات OpenAI/Gemini/MCP المهيكلة.

المراجع: `AgentLoop.kt:119-206,221-275,279-360,372-438`، `ToolDispatcher.kt:266-275`.

### 4. التوجيه المحلي والسحابي

`RuntimeRouter` و`RoutingPolicy` يقرران backend. قد تُفضّل السياسة السحابة للطلبات الطويلة أو التحليلية أو المرئية، بينما الوضع المحلي الصرف لا يملك fallback سحابيًا. `HybridOrchestrator` يجرّب البدائل قبل أول token، لكنه لا يملك عقدًا واضحًا يفرق بين نجاح فارغ، فشل، إلغاء، وpartial output.

المراجع: `RuntimeRouter.kt:36-116`، `RoutingPolicy.kt:58-141`، `HybridOrchestrator.kt:131-286`.

### 5. المسار المحلي

`LocalLlamaBackend` يجسر callbacks الأصلية عبر `Channel<LlamaEvent>`. إلغاء Job المستدعي يستدعي `llamaManager.cancelStream()`، لكن `LlamaManager` يعمل في `SupervisorJob` مستقل عن عمر المستدعي. لذلك يمكن أن يتوقف المستهلك بينما يستمر native worker إلى أن يلاحظ علم الإلغاء.

الإلغاء في Kotlin تعاوني؛ لا يوقف عمل JNI الحاجب تلقائيًا. يجب على الكود الأصلي فحص علم الإلغاء وانتظار نهاية worker صراحة.

المراجع: `LocalLlamaBackend.kt:86-261`، `LlamaManager.kt:36-125,851-937,975-1205`، `LlamaBridge.cpp:623-720,900-921`.

### 6. المسار السحابي

- Gemini يستخدم `streamGenerateContent?alt=sse`.
- OpenAI/Kimi/custom يستخدمون Chat Completions SSE.
- OpenRouter يرث محلل OpenAI.
- `ExecutionRequest.requiresStreaming` موجود، لكن `CloudProviderAdapter` يعرض `streamGenerate` فقط، و`CloudBackend.generate()` يستعمله؛ OpenAI يرسل `stream:true` دائمًا.
- المحللات يدوية ولا تمثل بصورة typed أخطاء SSE داخل HTTP 200 أو partial output أو finish reason.

المراجع: `ExecutionRequest.kt:15-43`، `CloudProviderAdapter.kt:28-74`، `CloudBackend.kt:72-249`، `GeminiAdapter.kt:39-205`، `OpenAIAdapter.kt:26-301`.

### 7. البث والإنهاء والواجهة

`ChatViewModel` يحدث `_streamingText` أثناء وصول tokens. لكن `ChatScreen` يعرض صف البث فقط عندما يكون `streamingText` غير فارغ و`isGenerating=true`. إذا انتهى البث فارغًا أو أُسقطت callbacks القديمة، يختفي الدليل الوحيد على وجود طلب جارٍ.

الرسالة الدائمة للمساعد لا تُحفظ إلا في `ChatViewModel.kt:1733-1743` عندما يكون النص غير فارغ. في المقابل، الاستثناء العادي يحفظ رسالة خطأ عند `1803-1807`، لكن مسارات الإلغاء وstale/blank لا تترك دائمًا أثرًا مرئيًا.

المراجع: `ChatScreen.kt:240-242,2111-2165,2252-2312`، `ChatViewModel.kt:1652-1670,1733-1743,1803-1807`.

## الأسباب مرتبة حسب الثقة

### P0 — مثبت ساكنًا: blank-success ثم إسقاط الرد

هذا هو السبب المباشر الأقوى. يجب أن يكون `Complete("")` فشلًا أو fallback، لا نجاحًا.

### P0 — فجوة terminal contract

لا يوجد نوع موحد مثل:

```text
Success(nonBlankText)
PartialFailed(partial, error)
Cancelled(reason)
Failed(code, message)
```

ولا يوجد invariant يفرض terminal event واحدًا لكل طلب. إذا لم يرسل backend `onComplete` ولا `onError` فلا توجد مهلة واضحة على حد orchestration.

### P1 — إلغاء أو generation/session stale

إلغاء teardown، تبديل النموذج أو الجلسة، watchdog، أو إرسال ثانٍ قد يسقط tokens وcommit. generation gate يحمي من فساد الحالة، لكنه قد يسقط كل callbacks القديمة بلا placeholder أو سبب مرئي.

### P1 — استثناء JNI callback

`LlamaBridge.cpp:952-963` يمسح استثناء Java ويخرج من decode loop دون ضمان واضح أن status سيصبح خطأ. قد يصل Kotlin إلى مسار Complete فارغ.

### P1 — عقد السحابة وSSE

`a7f8a46` هو أول commit يجب عزله واختباره لأنه غيّر JNI/local/cloud/UI streaming. لكنه ليس root cause مثبتًا حتى تمر fixtures قبل/بعد. أخطاء HTTP الطبيعية غالبًا تعرض رسالة خطأ؛ الصمت يحتاج تراكمها مع blank/stale/cancel.

### P1 — ضغط السياق

`n_ctx=1536` صغير مقارنة بتوسع system prompt والأدوات وRAG والتاريخ. overflow يجب أن يصبح خطأ واضحًا أو route للسحابة قبل JNI، لا reset صامتًا.

### P1 — readiness غير موحد

`ModelManager.currentModel` و`_modelState.isModelReady` وremote registry وSecureApiKeyStore ليست snapshot واحدة. قد يمر UI بوابة الجاهزية بينما لا يوجد backend فعلي قابل للتنفيذ.

### P1 — بروتوكول الأدوات

الـliteral parser قد يخطئ مع typed args، عدة tool calls، JSON مجزأ، أو أحداث OpenAI/Gemini الأصلية. هذا مهم خصوصًا للطلبات التي تحتاج مهارات أو موصلات.

### أسباب منخفضة الاحتمال

ترتيب Compose/Room أو pruning ليسا التفسير الأول؛ لا يوجد filter يحذف صفوف المساعد، وpruning لا ينتقيها. توجد مخاطر ثانوية: ترتيب timestamp بدون id tie-break، وعكس القائمة مع `reverseLayout=true` يحتاج invariant tests.

## خطة الإصلاح الآمنة

### المرحلة 0: حفظ وعزل الأدلة

1. احفظ `git status` و`git diff` و`git diff --binary` والملفات untracked.
2. لا تستخدم `git reset` في checkout المشترك.
3. أنشئ worktrees مستقلة لـ `a7f8a46^`، `a7f8a46`، `e00176d8`، `da06a1f7`، وHEAD الحالي.
4. أصلح Gradle dependency verification في branch منفصل فقط؛ لا تخلطه بإصلاح runtime.

### المرحلة 1: observability قبل السلوك

أضف trace غير حساس يحتوي فقط على:

- request ID وgeneration ID وsession ID.
- backend/provider/model identifier غير سري.
- load generation وfirst-token latency.
- token count، partial length، final length.
- terminal reason: success/empty/error/cancelled/stale/timeout.
- Room message ID ونتيجة UI commit.

لا تسجل prompt أو tokens أو مفاتيح API.

Invariant المطلوب:

```text
send_started
→ backend_started
→ zero-or-more events
→ exactly-one terminal
→ durable UI outcome
→ generation_finished
```

### المرحلة 2: إصلاح العقد P0

- استخدم `GenerationOutcome` typed بدل callbacks الضمنية.
- ارفض blank success في كل backend/orchestrator.
- اسمح بالـfallback قبل أول content فقط.
- ميّز الإلغاء المقصود عن الخطأ.
- أضف placeholder مساعدًا دائمًا مرتبطًا بـrequest/session/generation ID.
- لا يمسح `finishGeneration()` كل الأثر المرئي إذا لم يوجد terminal outcome.
- أضف per-backend timeout وfirst-token/idle/total watchdog.

### المرحلة 3: المحلي وJNI

- استبدل `cancelStream()` العالمي بـrequest-scoped handle.
- عند إلغاء caller: native cancel، إغلاق Channel، انتظار worker، ثم تحرير الملكية.
- عند JNI callback exception: اضبط status صريحًا إلى error، تحقق من JNI exceptions وUTF-8، وأرسل terminal failure واحدًا.
- وحّد readiness بين model manager وnative loaded/health/session/load generation.
- افحص budget `n_ctx=1536` قبل JNI، وقلّص history/RAG/tools بوحدات سليمة أو route للسحابة.

### المرحلة 4: السحابة

- نفذ STREAM وBATCH فعليين.
- استخدم SSE parser buffered يدعم byte splits وmultiline data وcomments وUnicode و`[DONE]` وEOF.
- فك JSON بشكل structured بدل البحث اليدوي عن أول `text`.
- مثّل OpenRouter in-band errors داخل HTTP 200.
- احفظ partial output وfinish reason.
- اجعل تفعيل المزود والمفتاح والنموذج والendpoint ووضع التنفيذ عملية ذرية.

### المرحلة 5: الأدوات والذاكرة والموصلات

- استخدم event model محايدًا: `TextDelta`, `ToolCallStarted`, `ArgumentsDelta`, `ToolCallDone`, `Usage`, `Finish`, `Error`.
- احتفظ call IDs وtyped args وschema validation.
- أرسل نتائج الأدوات كرسائل مهيكلة مع `isError`، لا كنص user-role فقط.
- ابنِ tool prompt entry-by-entry ولا تقطع schema بـ`raw.take(maxChars)`.
- ميّز RAG unavailable عن empty عن exception.
- مرر `CancellationException` ولا تحوله إلى skill failure.
- اربط confirmation بالجيل بدل CompletableDeferred واحدة عامة.
- لا تسمح bypass صامتًا لـAgentSandbox عندما تكون permissions مطلوبة.

### المرحلة 6: persistence وCompose

- اربط placeholder والـterminal وRoom commit بـrequest ID.
- عدّل query إلى `ORDER BY timestamp ASC, id ASC`.
- وثّق نموذج ترتيب القائمة وأضف tests بدل الاعتماد غير الموثق على reversal مع `reverseLayout`.
- أضف assertion على uniqueness لـ`msg.uid`.
- اختبر RTL/contrast وطبقات النشاط التي قد تحجب الرد.

### المرحلة 7: rollout تدريجي

فعّل كل إصلاح خلف flag مستقل:

- `strict_blank_failure`
- `typed_terminal`
- `request_scoped_local`
- `spec_sse`
- `typed_tools`
- `durable_placeholder`

لا تحذف المسار القديم حتى تتحقق المقاييس التالية:

- blank-success = صفر.
- terminal cardinality = 1 لكل request.
- stuck generation = صفر.
- كل non-cancelled request ينتهي برسالة مساعد أو خطأ مرئي.
- تطابق Room وStateFlow وواجهة Compose.

## مصفوفة الاختبارات المطلوبة

| الحالة | الاختبار | النجاح المطلوب |
|---|---|---|
| Complete فارغ | Fake backend يرسل `Complete("")` | Failure أو fallback، وليس نجاحًا صامتًا |
| EOS بلا tokens | نموذج/fixture حتمي | خطأ مرئي وterminal واحد |
| إلغاء أثناء prefill | local fake/JNI | Cancelled واضح، native cancel مرة، لا callback متأخر |
| callback exception | fault injection | status error، لا Complete فارغ |
| overflow | prompt أكبر من 1536 | ContextTooLarge أو route cloud قبل JNI |
| SSE طبيعي | chunks مقطعة + usage + DONE | نص وusage صحيحان |
| SSE HTTP 200 error | OpenRouter in-band error | Failure، لا onComplete |
| EOF مبكر | قبل وبعد أول token | retry قبل المحتوى فقط، partial failed بعده |
| batch mode | `requiresStreaming=false` | لا `stream:true` على السلك |
| fallback | primary فارغ/فاشل | fallback قبل أول token فقط |
| tool calls | OpenAI/Gemini/local formats | IDs وtyped args ونتائج مهيكلة |
| RAG down | exception/not-ready/no hits | تشخيصات منفصلة واستمرار آمن |
| persistence | Room + recreation | user/assistant بنفس request ID |
| Compose | أربع رسائل + streaming | ترتيب ثابت وكل الصفوف ظاهرة |
| attachment race | staging متأخر وإرسالان | reservation واحد وملكية حتمية |
| regression | worktrees قبل/بعد `a7f8a46` | تحديد أول فرق سلوكي بالـfixtures |

## استراتيجية التراجع

لا يُنصح بإرجاع commit كامل. إذا فشلت fixtures بعد `a7f8a46` ونجحت قبله، أعد مؤقتًا hunks الخاصة بالـstream/lifecycle فقط في branch معزول. إذا نجح HEAD النظيف وفشل worktree الحالي، اعزل تغييرات attachment/UI فقط. لا تُرجع `4ee23565` لعَرَض runtime؛ هذا commit metadata بناء، وليس سببًا مرجحًا إلا إذا ثبت أنه يمنع البناء.

## المقارنة بالمشاريع المشابهة

- **llama.cpp Android:** يفصل inference engine ويجعل عمر التدفق واضحًا؛ AIRI يضيف JNI callback وglobal state وgeneration gates، لذلك يجب أن يضمن بنفسه exactly-once terminal والإلغاء المرتبط بالطلب.
- **Llamatik:** يقدم Kotlin API موحدًا ويدعم streaming/non-streaming وجلسات مستقلة؛ AIRI يحتاج عقدًا typed مشابهًا حتى لا تتسرب تفاصيل JNI/provider إلى ChatViewModel.
- **Gemini Android:** يفصل UI/data عبر repository ومصدر حقيقة واحد؛ AIRI يضع routing/persistence/execution في ChatViewModel بشكل أكبر، ما يزيد خطر أن تغييرات UI تغيّر transport semantics.
- **AIChatAssistant:** يستخدم RemoteChatDataSource وMockStreamingDataSource وRoom؛ هذا يوضح أهمية fake streaming contract قبل اختبار جهاز حقيقي.
- **OpenAI/Gemini/MCP:** كلها تميز tool call، arguments، result، call ID، وerror عن text delta؛ بروتوكول AIRI النصي الحالي أضعف من هذه العقود.

## مصادر رسمية

[1]: https://kotlinlang.org/docs/cancellation-and-timeouts.html "Kotlin Cancellation and Timeouts"
[2]: https://developer.android.com/kotlin/coroutines "Android Kotlin coroutines"
[3]: https://kotlinlang.org/docs/coroutines-flow.html "Kotlin Flow"
[4]: https://kotlinlang.org/docs/exception-handling.html "Kotlin Coroutine Exception Handling"
[5]: https://github.com/ggml-org/llama.cpp "llama.cpp official repository"
[6]: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md "llama.cpp Android documentation"
[7]: https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android "llama.cpp Android example"
[8]: https://ai.google.dev/api/generate-content "Gemini Generate Content API"
[9]: https://ai.google.dev/gemini-api/docs/text-generation "Gemini text generation and streaming"
[10]: https://developers.openai.com/api/reference/chat-completions/overview/ "OpenAI Chat Completions API"
[11]: https://developers.openai.com/api/docs/guides/streaming-responses "OpenAI streaming responses"
[12]: https://openrouter.ai/docs/api_reference/streaming "OpenRouter streaming API"
[13]: https://modelcontextprotocol.io/specification/2025-06-18/server/tools "MCP Tools specification"
[14]: https://developers.openai.com/api/docs/guides/function-calling "OpenAI function calling"
[15]: https://ai.google.dev/gemini-api/docs/function-calling "Gemini function calling"
[16]: https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md "llama.cpp function calling"

## مشاريع المقارنة

[17]: https://github.com/ferranpons/llamatik "Llamatik"
[18]: https://github.com/GetStream/gemini-android "Gemini Android"
[19]: https://github.com/Kaustubha-09/AIChatAssistant "AIChatAssistant"
[20]: https://github.com/run-llama/llama_index "LlamaIndex"
[21]: https://github.com/AbdulLatef2380/AIRI-Project/commits/architecture-refactor "AIRI Project commit history"

## قيود التحقق الحالية

تمت مراجعة الكود والتاريخ والمصادر، لكن لم يُنفذ اختبار runtime أو device في هذه البيئة. محاولات Gradle توقفت قبل compilation بسبب dependency verification لمكوّن Kotlin 1.9.22، ثم بسبب عدم توفر Android SDK. لذلك التقرير يميز عمدًا بين السبب المثبت من المصدر والـtrigger الذي يحتاج instrumentation واختبارًا حتميًا.
