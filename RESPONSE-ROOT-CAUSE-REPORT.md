# تحليل مشكلة عدم استجابة AIRI وخطة الإصلاح

**نطاق الفحص:** فرع `main` الحالي في المستودع المحلي، مع مقارنة موثقة بمراجع Android/Compose وllama.cpp ومشاريع Open WebUI وLibreChat وAIRI العام. **لم يتم تعديل كود التطبيق أثناء هذا التحقيق.**

## 1. النتيجة التنفيذية

المشكلة ليست عطلًا واحدًا يمكن إصلاحه بتغيير زر أو تبديل نموذج. مسار الرد يمر بهذه الطبقات:

1. قبول الإرسال وتثبيت الجلسة والنموذج والمزوّد.
2. تجهيز الذاكرة والسياق والمهارات والمعرفة والأدوات.
3. اختيار المسار المحلي أو السحابي عبر `RuntimeRouter`.
4. تنفيذ `AgentLoop` ثم `HybridOrchestrator` ثم backend محلي/سحابي.
5. وصول المقاطع النصية إلى `ChatViewModel` وتراكمها.
6. إنهاء الرد وتخزينه في Room ثم عرضه في Compose.

وجدت **خللين مؤكدين في طبقة العرض/الحالة** يمكن أن يظهرا للمستخدم كأن النموذج لم يجب:

- إذا انتهى `AgentLoop` برد نهائي فارغ، ينتهي التوليد ويُمسح المؤشر دون إنشاء رسالة مساعد أو تحويل الحالة إلى فشل مرئي. هذا يجعل الفشل يبدو كصمت.
- عرض التفكير يعتمد على مقارنة نصية حرفية لقيم إنجليزية (`Thinking...`, `Analyzing...` ...). أي مقطع يختلف قليلًا أو يحتوي على فاصلة/مسافة/محدد تفكير قادم من النموذج قد يتحول إلى نص إجابة عادي، كما أن مكوّن التفكير الحالي مصمم فعليًا كسطر حالة + سطر نقاط داخل `Column`، وليس كحالة تفكير مستقلة قابلة للتحديث.

ويوجد **خطر مؤكد في اتجاه واجهة المستخدم**:

- `UserBubble` يستخدم `Arrangement.Start`. هذا صحيح فقط إذا كان المقصود هو الجانب المنطقي الذي يتبدل مع RTL/LTR. لكنه لا يضمن أن رسالة المستخدم دائمًا على الجانب المرئي المقصود. لذلك لا يجوز قلبه عشوائيًا؛ يجب تحديد سياسة صريحة: هل دور المستخدم يمين دائمًا أم `Start` في لغة RTL؟ ثم اختبارها على اللغتين.

أما فشل النموذج المحلي أو السحابي نفسه فما زال يحتاج سجلات تشغيل/اختبارات فعلية؛ لا يوجد دليل مسؤول يسمح بنسبته إلى الذاكرة أو المهارات أو الموصلات وحدها.

## 2. أدلة مؤكدة من الكود الحالي

### 2.1 مسار الحالة والتخزين

في `ChatViewModel`:

- يتم إضافة رسالة المستخدم قبل بدء الاستدعاء.
- يتم تفعيل `_isGenerating` و`AgentState.currentAction` قبل `agentLoop.run`.
- التراكم النصي يتم عبر `StringBuilder` ثم نشره في `_streamingText` لكل token.
- بعد انتهاء `agentLoop` لا تُخزّن رسالة المساعد إلا داخل:

```kotlin
if (loopResult.finalAnswer.isNotBlank()) {
    memoryManager.recordChatMessage(...)
    _messages.update { it + ChatMessage(...) }
}
```

- في `finally` يتم استدعاء `finishGeneration`, الذي يمسح `_streamingText` ويعيد `isGenerating=false`.

**النتيجة:** `finalAnswer == ""` ينتج دورة حياة تبدو مكتملة من ناحية المؤشر، لكنها لا تترك رسالة خطأ أو حالة `empty_response`. هذه فجوة حالة مؤكدة وليست مجرد احتمال.

### 2.2 التوجيه والتنفيذ

`HybridOrchestrator` لديه نقاط جيدة:

- `Mutex` لمنع التنفيذ المتوازي.
- generation gate لمنع المقاطع القديمة من تعديل الواجهة.
- cancellation يصل إلى backend النشط.
- fallback بين backends مع تسجيل تشخيصي.
- إعادة ربط طلب fallback المحلي بالنموذج المحلي المحمّل.

لكن هذه الضمانات لا تكفي وحدها؛ فالعقد الحالي يعتمد على callbacks (`onToken`, `onComplete`, `onError`) ويتطلب أن يضمن كل backend terminal event صحيحًا. أي backend يرجع بلا نص وبلا خطأ قد يصل إلى فجوة `finalAnswer` الفارغة في ViewModel.

### 2.3 مؤشر التفكير

هناك مساران مختلفان:

- عند `isGenerating && streamingText.isEmpty()` يظهر `AiriThinkingRow`.
- عند وجود `streamingText` يظهر `AiStreamingBubble`.

داخل `AiStreamingBubble` يتم تعريف التفكير بهذه الطريقة تقريبًا:

```kotlin
val isThinkingStage = text in setOf(
    "Thinking...", "Analyzing...", "Planning...",
    "Generating...", "Preparing...", "Reasoning..."
)
```

هذا **تمييز نصي هش** وليس state machine. لا يغطي:

- العربية أو النص المترجم.
- اختلاف Unicode مثل `…` بدل `...`.
- النص الذي يصل على دفعتين.
- `<think>` أو `</think>` المقسومة بين مقطعين.
- reasoning delta منفصلًا عن answer delta.
- structured reasoning من بعض مزودي النماذج.

أما `ThinkingAnimation` فهو `Column` يحتوي نص الحالة ثم صف النقاط. لذلك ظهور سطرين ليس بالضرورة قصًا؛ هو السلوك المقصود في المكوّن الحالي، لكن التصميم لا يميز بين **حالة التفكير** و**محتوى التفكير** ولا يملك عقدًا ثابتًا للارتفاع والاتجاه.

### 2.4 اتجاه العربية

`LanguageRuntimeManager` يحدد اتجاهًا لكل نص عبر `LocalLayoutDirection`، و`UserBubble` يرسم الصف باستخدام `Arrangement.Start`. هذا يجعل الاتجاه تابعًا للـ CompositionLocal، لا لسياسة الدور.

`BidiAwareMarkdownRenderer` يعالج اتجاه النص داخل الفقاعة، لكن اتجاه النص الداخلي ليس هو نفسه موضع الفقاعة على الشاشة. توثيق Compose يميز صراحة بين `LayoutDirection` واتجاه النص، ويوصي بـ `TextAlign.Start/End` بدل `Left/Right` للنص.

كما أن البحث الحالي مربوط بالواجهة فعليًا (`messages = filteredChatMessages`)، لكنه يجب أن يبقى منفصلًا عن مسار الإرسال والتخزين حتى لا يؤدي الفلتر إلى خلط عدد الرسائل أو مفاتيح القائمة.

## 3. الأسباب الجذرية المحتملة مرتبة حسب الطبقة

### أ. مؤكد/عالي الأولوية: عقد الاستجابة الفارغة

**العرض:** المستخدم يرى الرسالة، ثم ينتهي التفكير بلا رد.

**السبب:** لا يوجد terminal state صريح لحالة `EMPTY_RESPONSE`. المسار الفارغ يدخل `finally` ويمسح الحالة.

**الإصلاح المطلوب:** توحيد terminal result إلى:

- `SUCCESS` مع نص غير فارغ.
- `EMPTY_RESPONSE` مع سبب وrequest/execution id.
- `FAILED` مع provider/backend/code.
- `CANCELLED`.

لا يجوز اعتبار `onComplete("")` نجاحًا.

### ب. مؤكد/عالي الأولوية: التفكير ممزوج بنص العرض

**العرض:** التفكير مقصوص، يظهر كسطرين غير متناسقين، أو يتحول إلى إجابة.

**السبب:** الاعتماد على exact string بدل parser/state مستقل.

**الإصلاح المطلوب:** `ReasoningStreamParser` يحتفظ بحالة عبر المقاطع، ويفصل:

- `reasoningText`
- `answerText`
- `phase`
- `finishReason`

ويتعامل مع delimiter split across chunks وpartial UTF-8 وعدم إنشاء كتلة تفكير فارغة.

### ج. مؤكد كخطر تصميمي: عدم فصل lifecycle عن message content

المشاريع النظيرة الأقوى لا تستخدم `isLoading` وحده. Open WebUI وLibreChat يفصلان message identity/role عن deltas وcompletion/error/task/reasoning.

في AIRI يجب أن يكون لكل generation:

- `requestId` و`executionId` و`sessionId`.
- assistant draft/placeholder مرتبط بالطلب.
- حالة نهائية واحدة لا يمكن تجاوزها.
- منع callback قديم من تعديل generation أحدث.

### د. يحتاج إثبات تشغيل: النموذج المحلي/JNI

المواضع التي يجب إثباتها بسجل وليس بالتخمين:

- نجاح تحميل model/context.
- tokenization result وعدم قبول قيمة سالبة.
- كل نتيجة `llama_decode`.
- EOG/stop reason.
- token-to-piece والتحويل إلى UTF-8.
- `JNIEnv` على thread callback.
- `maxTokens` وcontext/KV capacity.
- هل انتهى native generation بنص أم بلا نص.

توثيق llama.cpp الرسمي يذكر صراحة فشل tokenization و`llama_decode`، وعدم وجود KV slot، وحالات abort/fatal، ويفصل بين EOG وlimit وstop-word. لذلك لا يكفي تسجيل `model loaded=true`.

### هـ. يحتاج إثبات تشغيل: cloud/connector

جاهزية المزوّد أو `/models` لا تثبت أن generation stream يعمل. يجب التحقق من:

- base URL الصحيح.
- protocol الصحيح (Chat Completions مقابل Responses).
- model id المملوك للمزوّد.
- API key/quota/rate limit.
- SSE chunks وusage-only chunk ذي `choices=[]`.
- EOF غير المكتمل وHTTP error body.
- إلغاء الطلب فعليًا عبر abort signal.

في مراجعة AIRI العام، التحقق التلقائي قد يتجاوز chat ping، وبعض مسارات التحقق تتعامل مع HTTP 400 بشكل متسامح؛ لذلك يجب عدم استخدام readiness كدليل نجاح inference.

## 4. خطة إصلاح آمنة بلا تغييرات عشوائية

### المرحلة 0 — تثبيت خط الأساس

1. عدم تغيير routing أو prompts أو memory قبل وجود trace موحد.
2. إضافة correlation fields فقط إلى السجل: `requestId`, `executionId`, `sessionId`, `provider`, `model`.
3. تسجيل الأحداث دون تسجيل النصوص الخاصة أو المفاتيح أو محتوى الذاكرة الخام.
4. تشغيل فشل متعمد لكل طبقة للتأكد أن القياس نفسه يعمل.

### المرحلة 1 — إصلاح عقد الحالة

1. إنشاء `GenerationTerminalState` موحد.
2. تحويل كل backend completion الفارغ إلى `EMPTY_RESPONSE`.
3. عند الفراغ، إبقاء رسالة المستخدم، وإضافة حالة مساعد قابلة لإعادة المحاولة أو رسالة خطأ محلية واضحة.
4. ضمان أن `finishGeneration` لا يمسح الدليل قبل أن تسجل الحالة النهائية.
5. منع الإرسال الثاني أثناء وجود generation حقيقي، والسماح به فور `FAILED/CANCELLED/EMPTY_RESPONSE` بعد تنظيف ownership.
6. اختبار race: إرسال سريع، إلغاء ثم إرسال، تبديل جلسة، تبديل نموذج.

### المرحلة 2 — فصل reasoning عن answer

1. إضافة parser incremental مستقل عن Compose.
2. اختبار delimiters في نفس chunk ومقسمة بين chunks.
3. دعم reasoning field المنفصل إن كان adapter يقدمه.
4. جعل `ThinkingAnimation` يعرض phase واحدة مع قيود واضحة، وعدم استخدام نص stream لتحديد الحالة.
5. جعل النص العربي مترجمًا من resources، والاتجاه الداخلي `Start` منطقيًا.
6. تخزين reasoning اختياريًا منفصلًا عن answer، وعدم حفظه كجزء من النص النهائي ما لم يطلب المستخدم ذلك.

### المرحلة 3 — جعل RTL سياسة صريحة

1. تحديد قرار منتج مكتوب: رسالة المستخدم يمين دائمًا، أم `Start` في RTL و`End` في LTR.
2. تطبيق القرار على container الفقاعة، لا على `Text` فقط.
3. إبقاء markdown direction منفصلًا عن bubble placement.
4. إضافة UI tests للغة العربية، الإنجليزية، المختلطة، والأرقام/علامات الترقيم.
5. التحقق من موضع avatar/actions/thinking/search/composer في الاتجاهين.

### المرحلة 4 — تدقيق النموذج المحلي

1. trace من `loadModel` إلى native context.
2. تسجيل نتائج tokenization/decode وسبب التوقف.
3. اختبار output cap = 0/1، context صغير، prompt طويل، Unicode، EOS مبكر.
4. تفعيل CheckJNI في debug.
5. اختبار ABI/path/thread attachment وpending JNI exception.
6. مقارنة: native generated text مقابل emitted text مقابل `_streamingText` مقابل `loopResult.finalAnswer` مقابل Room.

### المرحلة 5 — تدقيق السحابة والموصلات

1. اختبار كل provider بعينة deterministic صغيرة.
2. فصل نتائج config validation وconnectivity وmodel list وchat probe وactual generation.
3. محاكاة 200/400/401/404/429/500/timeout/invalid JSON/empty models.
4. اختبار SSE: role-only delta، content deltas، tool deltas، finish_reason، usage-only chunk، DONE، premature EOF، malformed JSON.
5. التأكد أن cancellation يصل إلى HTTP/native backend ولا يترك طلبًا يعمل في الخلفية.

### المرحلة 6 — الذاكرة والمهارات والمعرفة

1. لا تُدخل أي ذاكرة أو knowledge أو skill إلى التشخيص قبل إثبات أن prompt الأساسي يولد ردًا.
2. تشغيل نفس الطلب مع:
   - memory off / on
   - RAG off / on
   - skills off / one skill
   - tools off / on
3. مقارنة prompt token budget وcontext usage.
4. التحقق من أن tool loop ينتهي بـ final answer أو failure، لا بفراغ صامت.
5. التحقق من عدم حفظ tool output أو secrets في النص الظاهر للمستخدم.

## 5. مصفوفة التحقق المطلوبة قبل إعلان الإصلاح

| المسار | الاختبار | النتيجة المقبولة |
|---|---|---|
| محلي | رد نصي قصير متدفق | نص كامل، رسالة واحدة، `SUCCESS` |
| محلي | model غير محمل | خطأ مرئي، لا spinner دائم |
| محلي | decode/tokenization failure | سبب مصنف، لا empty success |
| محلي | cancel أثناء prefill/stream | `CANCELLED`، لا tokens متأخرة |
| سحابي | provider/model صحيح | chunks مجمعة ثم terminal success |
| سحابي | 401/429/500/timeout | خطأ provider واضح، لا صمت |
| سحابي | usage-only chunk | لا crash ولا فقدان للنص |
| مشترك | empty completion | `EMPTY_RESPONSE` وإعادة محاولة آمنة |
| مشترك | retry بعد failure | لا رسالة مساعد مكررة |
| UI | عربي RTL | موضع الرسائل ثابت حسب السياسة |
| UI | English LTR | موضع الرسائل ثابت حسب السياسة |
| UI | reasoning split chunks | thinking منفصل ولا تسرب/فقدان |
| UI | تغيير font scale/عرض ضيق | لا قص غير مقصود ولا overflow |
| UI | تبديل جلسة أثناء stream | generation القديم لا يكتب في الجلسة الجديدة |
| ذاكرة | RAG/summary | لا يمنع الرد الأساسي ولا يغير ownership |
| مهارات | tool success/failure | final answer أو failure terminal واضح |

## 6. المصادر الرسمية والمقارنة

- [llama.cpp Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)
- [llama.cpp simple example](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/examples/simple/simple.cpp)
- [llama.cpp public API header](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/include/llama.h)
- [llama.cpp server completion/streaming API](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [Android Kotlin Flow](https://developer.android.com/kotlin/flow)
- [Android StateFlow and SharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [Android JNI tips](https://developer.android.com/training/articles/perf-jni)
- [Compose paragraph/text direction](https://developer.android.com/develop/ui/compose/text/style-paragraph)
- [Compose LayoutDirection](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/LayoutDirection)
- [Compose lazy-list keys](https://developer.android.com/develop/ui/compose/lists)
- [Open WebUI reasoning models](https://docs.openwebui.com/features/chat-conversations/chat-features/reasoning-models/)
- [Open WebUI Chat event/message implementation](https://github.com/open-webui/open-webui/blob/main/src/lib/components/chat/Chat.svelte)
- [LibreChat resumable streams](https://www.librechat.ai/docs/features/resumable_streams)
- [LibreChat agent cancellation](https://www.librechat.ai/docs/features/agents)
- [Google Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai)
- [OpenAI Chat Completions streaming reference](https://developers.openai.com/api/docs/api-reference/chat/create)

## 7. قرار التنفيذ المقترح

لا أوصي بتعديل النموذج أو حذف الذاكرة أو تعطيل المهارات كحل أول. المسار الأقل مخاطرة هو:

1. إصلاح terminal-state/empty-response contract.
2. فصل reasoning parser/state عن النص المعروض.
3. تثبيت سياسة RTL باختبارات واجهة.
4. إضافة traces آمنة ثم اختبار المحلي والسحابي كلٌ على حدة.
5. بعد ذلك فقط معالجة أي سبب مثبت في JNI أو provider أو context budget.

بهذا الترتيب يمكن معرفة هل المشكلة في الاستدلال، النقل، التجميع، التخزين، أم العرض، بدل إصلاح طبقة وإخفاء العطل في طبقة أخرى.
