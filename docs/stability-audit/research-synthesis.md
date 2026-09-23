# تقرير تنفيذي لتدقيق استقرار AIRI

**تاريخ التدقيق:** 23 سبتمبر 2026  
**نطاق التدقيق:** مسارات الاستدلال المحلي والسحابي، التوجيه وموصلات النماذج، المرفقات واستخراج النص، البث والإلغاء والمهل، Unicode وRTL وMarkdown وMIME، ودورة حياة Android/Compose.  
**حالة التنفيذ:** مراجعة ساكنة للمصدر وقراءة الاختبارات المتاحة. لم يُعدّل كود المشروع، ولم تُشغّل ثنائيات JNI أو محاكي Android أو طلبات API حقيقية.

## 1. الخلاصة التنفيذية

الاستقرار الحالي **غير جاهز لإطلاق إنتاجي واسع**. أخطر نمط مشترك هو أن الإلغاء والمهلة لا يملكان مالكاً واحداً للاتصال أو لحالة النهاية. بعض الطبقات تلتقط `CancellationException` وتحوّلها إلى فشل قابل لإعادة المحاولة، بينما تبقى القراءة الشبكية أو فكّ التوليد الأصلي محجوبة. لذلك قد يستمر العمل بعد أن يراه المستخدم متوقفاً، وقد يبدأ fallback أو retry، وقد تُمسح مرجعية اتصال أحدث بواسطة `finally` لطلب أقدم.

تتبع ذلك أربعة مخاطر إطلاق من الدرجة الأولى: **تجاوز الإلغاء في طبقات الموصلات، عدم إيقاف I/O الشبكي المحجوب عند timeout، خلط ناتج backend فاشل جزئياً مع fallback، وعدم وجود admission حقيقي لميزانية السياق**. كما أن تحرير النموذج المحلي تحت ضغط الذاكرة لا يثبت تحرير `llama_model` وموارده الأصلية فعلياً، وهو خطر مباشر على الأجهزة ذات الذاكرة المحدودة.

توجد مخاطر دلالية لا تظهر كتعطل فوري: رسائل OpenAI/Anthropic لا تحمل النظام والسياق دائماً، Gemini يستخدم default معرضاً للتقاعد ولا يتحقق من نهاية stream أو سبب الحجب، أخطاء HTTP تُعاد غالباً دون تصنيف، وملفات الصور قد تُرسل بـ`image/jpeg` رغم أن bytes الفعلية PNG أو WebP. وفي مسار المرفقات، يمكن إعلان قبول الصورة ومسح المسودة قبل اكتمال الإرسال، كما يمكن فقد حدث URI أو تمرير ملفات PDF/Office كعلامات بلا استخراج مضمون.

**قرار الإطلاق المقترح:** إيقاف الإطلاق العام إلى أن تُغلق بوابة P0/P1، أو تقييد الإصدار إلى قناة داخلية بميزات cloud fallback والمرفقات والـ vision معطلة افتراضياً. لا يكفي نجاح HTTP أو وصول أول token؛ يجب إثبات إغلاق الاتصال، وحسم terminal event مرة واحدة، وإعادة بناء prompt ضمن ميزانية النموذج، وحفظ نتيجة فشل صريحة بدلاً من نجاح مبتور.

> **عدد الملاحظات الحرجة:** 4 ملاحظات على مستوى التدقيق (P0/حرج). اثنتان منها تقعان في عائلة إلغاء الموصلات نفسها لكن عند نقطتي إنفاذ مختلفتين، ولذلك أبقيتا منفصلتين في العد. العدد لا يشمل ملاحظات HIGH/P1.

## 2. تعريف درجات الخطورة وحدود الإثبات

| الدرجة | معنى الدرجة في هذا التقرير | قرار الإطلاق |
|---|---|---|
| **P0 / حرج** | يمكن أن يستمر عمل أو طلب بعد الإلغاء، أو أن يسبب timeout/fallback سلوكاً متعارضاً مع حالة المستخدم. | لا إطلاق عام قبل الإصلاح والاختبار الحتمي. |
| **P1 / عالٍ** | قد ينتج إجابة خاطئة أو مبتورة، تجاوز سياق، استهلاكاً زائداً، أو فقداً صامتاً للبيانات. | إصلاح قبل تمكين الميزة في الإنتاج. |
| **P2 / متوسط** | عقد هش أو تشخيص ضعيف أو تكلفة تشغيلية، دون crash مثبت في المسار الحالي. | إصلاح ضمن hardening قبل التوسع. |
| **P3 / منخفض** | فجوة typed semantics أو حماية مستقبلية لا يظهر منها عطل مؤكد حالياً. | اختبار ومتابعة، وليس حاجز إطلاق منفرداً. |

**ما يثبت من الكود:** تسلسل الاستدعاءات، مواضع `runCatching` و`withTimeout`، استخدام I/O المحجوب، صيغ JSON وMIME، حدود الحروف، scopes، وغياب دوال أو callbacks محددة يمكن إثباتها من المصدر دون مفاتيح أو جهاز.

**ما يحتاج Android SDK أو ثنائيات أصلية:** سلوك `onTrimMemory` الفعلي، تحرير `mmap` داخل JNI، أداء Compose و`collectAsStateWithLifecycle`، منح URI القابل للاستمرار، وحجم الذاكرة الفعلي على أجهزة مختلفة. هذه النقاط مؤكدة كفجوات في العقد أو التصميم، لكن نتيجتها التشغيلية النهائية تحتاج اختباراً على emulator/device.

**ما يحتاج مفاتيح API أو شبكة مزود:** صلاحية model IDs، status codes الفعلية، `Retry-After`، safety/finish reasons، حدود الصور والتكلفة، وcontract النماذج الحديثة. يمكن اختبار parser والتصنيف بـMockWebServer، لكن صحة endpoint/model مع المزود نفسه تحتاج health probe أو مفاتيح اختبار.

## 3. الأولويات التنفيذية قبل الإطلاق

### P0 / حرج

1. **إيقاف ابتلاع الإلغاء في ConnectorRuntimeManager وRemoteLlmConnector.** يجب إعادة رمي `CancellationException` قبل أي catch عام أو `runCatching`. الإلغاء ليس `provider_error` ولا سبباً لبدء provider تالٍ أو retry.
2. **امتلاك اتصال مستقل لكل محاولة وربط الإلغاء به.** استبدال القراءة المحجوبة غير القابلة للإيقاف بجسر cancellable، أو تسجيل `invokeOnCompletion { call.cancel()/connection.disconnect() }`. يجب أن يحمي `finally` مرجعية الاتصال بمعرف generation/request حتى لا يمسح اتصال طلب أحدث.
3. **توحيد deadline وretry.** لا يجوز أن يضع `AgentRouter` مهلة 45 ثانية، و`ConnectorRuntimeManager` 20 ثانية، ثم ينتظر adapter حتى 90 ثانية. يجب تمرير total deadline واحد، وإلغاء المحاولة قبل backoff أو fallback.
4. **حسم terminal state مرة واحدة.** أي إلغاء أو timeout أو native completion يجب أن يمر عبر generation ID وatomic terminal CAS. لا يُنشر timeout قبل grace period أو نتيجة native حاسمة، ولا يُحفظ stream مبتور كنجاح.

### P1 / عالٍ

5. **منع خلط partial output عند fallback.** كل backend محاولة تكتب buffer معزولاً. لا يصل buffer إلى UI أو Room إلا بعد نجاح المحاولة، أو يُنشر صراحةً كـ`attemptId` مع reset/continuation. بعد ظهور output جزئي، لا يُعاد إرسال الطلب تلقائياً إلى backend آخر إلا بسياسة معلنة.
6. **إضافة admission لميزانية السياق قبل dispatch.** الشرط الأدنى هو `prompt + history + system + tools + markers + image cost + max output + reserve <= model context`. يجب استخدام native token count حيث يتوفر، وتجنب تحويل `estimatedPromptTokens` إلى مجرد flag.
7. **إصلاح الإلغاء المحلي في `generate()`.** `generateStream()` يربط إلغاء coroutine بـ`cancelStream`، لكن `generate()` ينتظر `CompletableDeferred` دون `invokeOnCompletion`. يجب إلغاء native مرة واحدة، وعدم وصول `Success` بعد الإلغاء.
8. **إضافة unload أصلي حقيقي.** `onTrimMemory(CRITICAL)` لا ينبغي أن يعلن release إذا كان `model_mmap_held=true`. يلزم `unloadModel/unloadContext` تحت القفل مع تأكيد completion، أو runtime process-owned له `close` فعلي.
9. **إصلاح MIME وملكية المرفق.** تطبيع MIME مع allow-list وفحص magic bytes، ورفض mismatch بدلاً من وسم bytes PNG كـJPEG. لا يُمسح draft ولا يُستدعى `onAccepted` قبل نجاح staging وsend admission.
10. **إصلاح Unicode/JSON/SSE.** القراءة يجب أن تكون UTF-8 صريحة، مع parser JSON/SSE typed يدعم `\\uXXXX`، surrogate pairs، data متعددة الأسطر، `[DONE]`، finish reason، safety، وEOF المبتور.

### P2/P3 / hardening

11. تحديث model defaults إلى IDs نشطة ومثبتة مع health probe، وإيقاف default Anthropic المتقاعد وGemini default غير المضمون.
12. توحيد `ProviderRequest` ليحمل system prompt وhistory، وإضافة `max_completion_tokens` أو equivalent بحسب capability.
13. ربط scope المحرك بعمر ViewModel وإضافة `Closeable.close()` يلغي watchdog وnative work؛ استخدام `collectAsStateWithLifecycle`.
14. توحيد AgentRouter وConnectorRuntimeManager بدلاً من عقدي تنفيذ بمهلات وretry متباينة.
15. جعل ModelManager/ModelController يستخدمان `LoadRequest(id)` ونتائج `SUPERSEDED/FAILED/SUCCESS`، لا `isLoading` وcallback عامين.
16. استبدال extraction البدائي لمحركات PDF/Office بخدمة persisted file، decoding BOM-aware، حدود bytes/chars، ومحرك PDF/OCR مناسب عند الحاجة.

## 4. مصفوفة السبب الجذري ← الدليل ← الإصلاح ← الاختبار

### 4.1 P0 / حرج

| السبب الجذري | الدليل المثبت من الكود | الإصلاح المطلوب | اختبار القبول |
|---|---|---|---|
| ابتلاع إلغاء coroutine في طبقة runtime | `ConnectorRuntimeManager.kt:42–47` يحول timeout إلى Failure ويمسك Exception عاماً؛ `:63–79` يستخدم `runCatching` و`delay`. الإلغاء يمكن أن يصبح retryable failure. | `catch (CancellationException) { throw e }` قبل أي catch عام. اجعل cancellation حالة مستقلة لا تدخل retry أو fallback. | FakeConnector معلق بـ`CompletableDeferred`: `cancelAndJoin` يرمي cancellation، `executeCalls==1`، لا retry ولا backoff، وinflight فارغة. |
| ابتلاع الإلغاء داخل RemoteLlmConnector/provider | `RemoteLlmConnector.kt:98–113` يلف `provider.complete` بـ`runCatching` ويصنف failure كـretryable. الموفرون يستخدمون blocking `execute()`. | استثناء الإلغاء، وتمرير deadline إلى transport قابل للإلغاء. لا يُستدعى provider التالي بعد إلغاء المستخدم. | FakeProvider ينتظر الإلغاء؛ تحقق من عدم استدعاء provider التالي وعدم إنتاج `provider_error`. |
| timeout coroutine لا يوقف I/O المحجوب، ومرجع الاتصال عرضة للسباق | `RemoteModelExecutor.kt:65–97` يستخدم `withTimeoutOrNull` حول قراءة محجوبة؛ `activeConnection` مشترك، و`finally` القديمة قد تضعه `null` لطلب أحدث. OpenAI/Gemini يقرآن `readLine`/`execute` محجوباً. | اتصال/Call لكل محاولة مع request ID. استخدم OkHttp cancellable أو `runInterruptible` مع close مؤكد. اجعل `finally` يمسح handle إذا كان ID مطابقاً فقط. | Server محلي يعلق: إلغاء يغلق socket بزمن ثابت؛ يبدأ طلب ثانٍ؛ لا يمسح cleanup القديم مرجع الطلب الثاني؛ لا retry قبل إغلاق الأول. |
| تعدد مالكي المهلة والـretry | `AgentRouter.kt:64–71` يضع 45s، و`ConnectorRuntimeManager.kt:31–41` يضع 20s، وread timeout يصل إلى 90s؛ المساران لا يطبقان السياسة نفسها. | مالك واحد لـtotal deadline؛ per-attempt timeout وbackoff داخل budget مشترك، مع cancel قبل retry. | Fake clock: 429 و503 وbackoff لا تتجاوز total budget، والإلغاء أثناء delay يوقف فوراً. |

### 4.2 P1 / عالٍ: runtime المحلي والسحابي

| السبب الجذري | الدليل المثبت من الكود | الإصلاح المطلوب | اختبار القبول |
|---|---|---|---|
| `generate()` المحلي لا يربط إلغاء coroutine بالـnative | `LocalLlamaBackend.kt:217–297` ينتظر `CompletableDeferred.await()` دون `invokeOnCompletion`؛ الربط موجود في مسار streaming فقط (`:138–143`). | سجّل completion handler قبل `generateStream`، استدعِ `cancelStream` عند cause غير null، وأكمل deferred مرة واحدة. | إلغاء قبل callback وبعد أول token: `cancelStream` مرة واحدة، لا deferred معلّق، ولا Success متأخر. |
| fallback يخلط partial output | `HybridOrchestrator.kt:209–328` يمرر `onToken` إلى UI ثم يبدأ backend التالي بعد `onError`؛ local قد يرسل tokens ثم overflow/timeout. | `AttemptResult` وbuffer لكل backend؛ publish عند success فقط، أو event reset/attempt ID صريح. لا تحفظ partial failed output كرسالة نهائية. | local يرسل `partial-` ثم يفشل، cloud يرسل `final`: الناتج ليس `partial-final`، ولا تُحفظ المحاولة الفاشلة كنجاح. |
| لا يوجد admission حقيقي للسياق المحلي أو السحابي | `ExecutionRequest` يحمل `estimatedPromptTokens/estimatedTotalTokens`، لكن `CapabilityProfile.satisfies` و`RoutingPolicy` لا يقارنانها بالسعة. الصور مستثناة من التقدير، و`PrivacyGuard` يستخدم caps حرفية. | `canFit` يحسب system/history/tools/attachments/image cost/output reserve. استخدم exact native count أو سياسة provider، وارفض أو قص قبل dispatch. | local `n_ctx=1024` مع prompt/max output أكبر: reroute/reject قبل `generateNextTokens`. اختبر صورة كبيرة وسياق CJK/emoji. |
| watchdog قد ينشر timeout قبل native completion | `LlamaManager.kt:913–960` يضع `finished=true` ويرسل error؛ completion اللاحق يرى finished ولا يرسل success. | watchdog يطلب cancel وينتظر terminal native bounded grace؛ atomic CAS واحد للنتيجة النهائية. | fake clock عند deadline-1/0/+1ms: نتيجة واحدة فقط، وnative OK المثبت يفوز على timeout. |
| unload الذاكرة لا يثبت تحرير model mmap | `ChatViewModel.kt:1181–1197` يستجيب لـCRITICAL، لكن `LlamaManager.kt:604–638` ينفذ reset ويصرح `model_mmap_held=true`؛ لا JNI unload مستقل. | unload model/context/resources تحت `LLAMA_LOCK` مع completion؛ لا تعلن release قبل native free. | يحتاج JNI/emulator/device: حمّل نموذجاً، أرسل trim CRITICAL، تحقق من native handles وRSS/heap ومن عدم وصول callback بعد close. |
| cloud stream قد ينجح بعد EOF مبتور | Gemini يقرأ حتى EOF ولا يثبت `sawDone` أو `finishReason`، ثم `CloudBackend` يحفظ fullText. OpenAI لديه تحقق `[DONE]` بصورة مختلفة. | terminal metadata إلزامي؛ EOF بلا سبب نهائي = `CONNECTION_LOST`، ولا fallback تلقائي بعد output جزئي. | chunks ثم EOF بلا terminal تعيد failure؛ stream مع STOP تعيد Success؛ callback النهائي واحد. |
| retry لا يميز HTTP semantics | الموفرون يحولون non-2xx إلى IOException نصية، و`RemoteLlmConnector` يجعلها retryable؛ لا يقرأ `Retry-After`. | `ProviderFailure(status, kind, retryAfterMs, partial)`؛ retry فقط لـIO/408/429/5xx/529/504، لا لـ400/401/403/404/413/context. | MockWebServer لكل status وRetry-After؛ تحقق من العدد والتأخير وعدم إعادة أخطاء الإدخال/الاعتماديات. |
| عقود request لا تحمل system/history كاملة | `Provider.complete(prompt, params)` لا يملك systemPrompt/conversation؛ OpenAI وAnthropic يرسلان user واحداً، وGemini لا يرسل systemInstruction. | `ProviderRequest` موحد مع system/history، وcapability mapping لـ`max_completion_tokens` وtemperature. | افحص bodies عبر MockWebServer؛ system/history موجودان، والنموذج reasoning لا يتلقى `max_tokens` غير صالح. |
| model defaults معرضة للتقاعد أو غير مثبتة | Anthropic default هو `claude-3-5-haiku-latest`، وGemini default `gemini-1.5-flash` وفق التدقيق؛ صلاحيتها الزمنية لا يثبتها source وحده. | config model IDs نشطة ومثبتة، health/deprecation probe، وعدم اعتبار وجود المفتاح دليلاً على صلاحية النموذج. | يحتاج API key/network: connect probe وrequest حقيقي محدود، وتسجيل deprecation دون تسريب المفتاح. |
| parsing JSON/SSE يدوي وغير مكتمل | `InputStreamReader` بلا charset صريح؛ parsers تفك حالات قليلة فقط ولا تضمن `\\uXXXX` وcontrol escapes وsurrogate pairs وmultiline SSE. | UTF-8 صريح وJSON library/typed parser، مع event/error/usage/finish handling. | Arabic/emoji وescaped Unicode وdata متعددة الأسطر وkeep-alive و`[DONE]`؛ تحقق من نص مطابق للمدخل. |
| OpenAI/Anthropic response الحديثة غير ممثلة | OpenAI يقرأ `choices[0].message.content` فقط؛ content قد يكون null مع tool calls/refusal. Anthropic يتجاهل blocks غير text. | typed result لـtool calls/refusal/tool use، أو رفض capability مبكراً كـnon-retryable. | response content null مع tool_calls/refusal وtool_use-only: نتيجة typed لا fallback عام ولا نجاح فارغ. |
| AgentRouter يتجاوز RuntimeManager | `AgentRouter.kt:36–101` ينفذ connector مباشرة، بينما RuntimeManager يملك health/inflight/retry؛ ServiceLocator ينشئ AgentRouter. | دمج المسارين أو تحديد مالك واحد للعقود والمهل والسياسات. | نفس fake connector عبر كل entry points يعطي deadline/retry/cancel متطابقاً. |

### 4.3 P1 / عالٍ: المرفقات والنص والواجهة

| السبب الجذري | الدليل المثبت من الكود | الإصلاح المطلوب | اختبار القبول |
|---|---|---|---|
| قبول/مسح draft قبل نجاح الإرسال | `ChatScreen.kt:852–877` يمسح attachments؛ `sendMessageWithAttachments` يستدعي `onAccepted` في فرع الصورة قبل اكتمال runtime. | حالات `READY/SENDING/SENT/FAILED` ونتيجة typed؛ المسح بعد staging وadmission/commit فقط. | fake sender يفشل في imagePart/backend: لا `onAccepted`، وتبقى المسودة قابلة لإعادة الإرسال. |
| event URI غير durable | `stageAttachmentUri` يرسل عبر `MutableSharedFlow(replay=0)`، والجامع داخل `LaunchedEffect(Unit)`؛ قد يضيع عند recreation/navigation. | pending state لكل session مع event ID وack أو replay مع dedup؛ نسخ فوري إلى app-private storage. | أطلق الحدث قبل collector ثم أعد collector؛ يصل مرة واحدة بعد الإصلاح. |
| نسخ غير ذري يقبل partial file | `openInputStream().copyTo()` يكتب إلى destination مباشرة، بلا temp/atomic rename/timeout/hash؛ `exists()` قد يتجاوز إعادة النسخ. | temp في الدليل نفسه، byte cap، `ensureActive` وtimeout، hash/size، ثم atomic rename وحذف temp عند الخطأ. | stream يكتب bytes ثم يرمي IOException: لا يُقبل partial، يُحذف temp، وتنجح المحاولة التالية. |
| MIME يطابق الحقل لا bytes | `ChatAttachment.Kind.IMAGE` لا يتحقق من MIME؛ `visionImagePart` يستخدم raw `startsWith("image/")` وإلا يفرض JPEG؛ adapters تمرر MIME إلى data URI/inline data. | trim/lowercase/`substringBefore(';')`، allow-list، magic-byte sniffing، رفض mismatch أو إعادة ترميز فعلية. | `IMAGE/PNG`، `image/png; charset=binary`، MIME فارغ مع PNG، WebP مع JPEG: canonical/reject، ولا `data:image/jpeg` لbytes PNG. |
| PDF/Office لا يمر عبر extraction موحد | build context يبني المحتوى فقط لـ`isTextual`؛ DocumentProcessorAgent منفصل يعتمد URI regex، والmetadata لا يحفظ source URI bridge مضموناً. | persisted file abstraction وخدمة extraction واحدة، وعدم تمرير URI خام إلى LLM. | fixtures PDF/DOCX/XLSX: إما content مستخرج ومحدود أو خطأ typed، لا marker يوحي بنجاح القراءة. |
| extraction PDF/text هش وكبير | `DocumentProcessorAgent` يقرأ PDF بـ`readBytes` وISO-8859-1/regex، ويستخدم UTF-8 حتى النهاية؛ لا BOM/ToUnicode/OCR أو cap bytes كافٍ. | محرك PDF حقيقي، BOM-aware strict decoder، byte/char caps، OCR اختياري، وفشل واضح عند binary/image-only. | UTF-8 BOM، UTF-16، عربي، PDF escaped/compressed/Unicode، image-only؛ لا OOM ولا نص مشوه. |
| long text يتجاوز budget أو يُقطع بصمت | auto path يحول كل نص ≥3000 UTF-16 إلى file؛ `buildTextAttachmentContext` يصل 512000 حرف وTextContextPolicy غير موصول، وDocumentProcessor cap مختلف 8000. | PromptBudgetLedger يحسب system/history/tools/markers/output/images؛ trim/retrieve/chunk/reject مع إشارة truncation. | budget صغير: `REJECTED_NO_BUDGET` أو chunks معلنة؛ لا suffix مفقود بصمت، ولا dispatch يتجاوز nCtx. |
| offsets قد تكسر surrogate pairs | `StructuredTextChunker.kt:20–39` يقطع مؤشرات UTF-16 وقد يفصل non-BMP، وoffsets تخص النص trimmed لا المصدر. | code-point/grapheme-safe chunking مع source spans exact. | emoji + Arabic عند حدود chunk: لا unpaired surrogate وإعادة reconstruction مطابقة. |
| draft/process death وURI grant غير مستقرين | drafts في StateFlow داخل ViewModel؛ `GetContent` بلا persistable grant، وAndroid lifecycle لا يضمن grant بعد restart. | `OpenDocument`/persistable permission أو نسخ فوري؛ حفظ pending metadata durable. | يحتاج Android SDK/device: process death ثم قراءة المرفق؛ grant يبقى أو تكون النسخة الخاصة مكتملة. |
| media import غير متزامن مع النتيجة | `mediaLibrary.importFile` يطلق `viewModelScope.launch` بلا انتظار، وفشله لا يغير dispatch result. | اجعله child job بtimeout أو جزءاً من typed result مع retry. | import متأخر ثم fail: لا success قبل الإتمام، وتظهر حالة retry. |

### 4.4 P1/P2: RTL، Markdown، lifecycle والرسائل

| السبب الجذري | الدليل المثبت من الكود | الإصلاح المطلوب | اختبار القبول |
|---|---|---|---|
| code blocks ليست LTR فعلياً | `BidiAwareMarkdownRenderer.kt:35–60` يطبق outer layout direction فقط؛ `MarkdownText.kt:74–88` يضبط monospace دون `TextDirection.Ltr`. | `TextDirection.ContentOrRtl` للنثر وLTR subtree صريح للكود/المعرفات، مع عزل bidi runs. | Arabic-first fenced code وURLs: ترتيب identifiers/punctuation منطقي، وcopy round-trip صحيح. |
| direction detector يخطئ في القصير والمحايد | `LanguageRuntimeManager.kt:19–43` يتطلب ثلاث حروف ASCII قبل ENGLISH، وإلا يميل إلى RTL؛ `OK` والأرقام وé تتأثر. | first-strong/BidiFormatter أو Compose Content/ContentOrRtl، لا regex محدود. | `OK`، أرقام، punctuation، é، Persian/Hebrew، Arabic-English URL. |
| Markdown يعاد تحليله بالكامل أثناء streaming | `MarkdownText.kt:118–203` يستخدم split وColumn child لكل block في كل snapshot؛ يغيب links/quotes/tables/escaping. | AST CommonMark/renderer موثوق، parse خارج composition، الاحتفاظ بالحالة السابقة وLazyColumn للنص الطويل. | Markdown 100000 سطر، tables/links/nesting/fenced code داخل RTL؛ لا frame stall ولا literal parsing خاطئ. |
| collect غير lifecycle-aware | `ChatScreen` يستخدم `collectAsState` وstream collector بـ`LaunchedEffect(Unit)`؛ قد يستمر بعد توقف الشاشة. | `collectAsStateWithLifecycle`/`repeatOnLifecycle`؛ إبقاء generation في ViewModel وتنظيف UI collection فقط. | fake LifecycleOwner: عند STOP لا collectors عالية التردد، وعند START تعود دون duplicate. |
| user message تُحفظ قبل نجاح inference | `ChatViewModel` يسجل message وquota قبل agent/provider؛ فشل pre-inference يترك user bubble orphan. | pending execution ID ثم commit/fail terminal state، أو توثيق سياسة quota وassistant error. | provider يفشل قبل token أو cancel بعد record: حالة واضحة ولا assistant success وهمي. |

### 4.5 P2/P3: الإدارة والتحميل والتشخيص

| السبب الجذري | الدليل المثبت من الكود | الإصلاح المطلوب | اختبار القبول |
|---|---|---|---|
| scope المحرك مستقل عن ViewModel | `LlamaManager.kt:50–56` ينشئ SupervisorJob وwatchdogScope؛ `ChatViewModel.onCleared` يلغي generation فقط. | `Closeable.close()` يلغي jobs، ينتظر cleanup في NonCancellable، ويُستدعى في onCleared/قبل استبدال model. | TestScope: close أثناء generation يلغي watchdog/native ولا callback بعد close. |
| ModelManager callback عام بلا request ID | `ModelController` يحمي StateFlow جزئياً، لكن `ModelManager.isLoading/currentModel` global؛ طلب A قد يعود بعد B. | `LoadRequest(id,path)` وtyped `SUPERSEDED/FAILED/SUCCESS` مع guard عند registry/currentModel. | callbacks A/B خارج الترتيب: A لا يعيد currentModel ولا يحول B إلى failed زائف. |
| remote connector يسجل رغم قائمة providers فارغة | `ConnectorBootstrap` default `llmProviders=emptyList()`؛ قد يظهر tile متصل ظاهرياً دون provider. | اجعل providers required أو امنع التسجيل عند empty، وحالة UI صريحة غير متصل. | bootstrap empty/non-empty مع fake DI؛ لا tile قابل للاستخدام بلا provider. |
| isConfigured غير ذري مع تغير secret | `isConfigured()` يتحقق من key، ثم `complete()` يقرأ provider مرة أخرى؛ قد يرسل blank أو key مختلفة. | snapshot واحد للسر بعد trim/blank validation عند connect؛ لا تسجيل السر أو URL. | fake key provider يغير القيمة بين check وexecute؛ النتيجة متسقة ومصنفة credentials. |

## 5. ما يمكن إثباته الآن وما يتطلب بيئة إضافية

### 5.1 مثبت بالمراجعة الساكنة وJVM/MockWebServer

يمكن إثبات معظم عيوب العقود دون Android أو مفاتيح: ابتلاع `CancellationException`، عدد retries، عزل partial output، `canFit` وPromptBudgetLedger، MIME normalization وmagic-byte policy، JSON/SSE parsing، surrogate-safe chunking، request bodies، HTTP status classification، `Retry-After`، generation IDs، وload supersession. الاختبارات المقترحة لهذه المجموعة يجب أن تكون deterministic باستخدام fake clock وfake transport و`runTest`.

كما يمكن اختبار `LocalLlamaBackend.generate()` باستخدام Fake LlamaManager، لكن إثبات أن `nativeCancel` أوقف decode فعلياً يتطلب JNI binary أو adapter fake يغطي contract فقط. لا ينبغي تفسير نجاح fake على أنه إثبات لتحرير native resources.

### 5.2 يحتاج Android SDK أو emulator/device

يحتاج `onTrimMemory` وقياس تحرير mmap، lifecycle collection، Compose direction/rendering، persistable URI grants، process death، media import، وقياس jank/ذاكرة Markdown إلى Android SDK مع emulator أو أجهزة ممثلة. يلزم اختبار جهاز منخفض الذاكرة لتأكيد أن unload يقلل RSS وأن LMK لا يقتل العملية قبل cleanup.

### 5.3 يحتاج شبكة أو مفاتيح API

يحتاج صلاحية model IDs الحالية، health probe، limits الفعلية للصور والنماذج، safety/finish metadata، ومطابقة headers مع المزود إلى شبكة ومفاتيح اختبار. يمكن عزل معظم السلوك باستخدام MockWebServer، لكن لا يصح إعلان model default صالحاً إنتاجياً دون probe فعلي أو config محدث من المصدر الرسمي. لا تُستخدم مفاتيح حقيقية في الاختبارات أو السجلات، ويجب منع key في URL حيث يدعم المزود بديلاً آمناً.

### 5.4 عائق البناء الحالي

محاولات Gradle المذكورة فشلت قبل compilation لأن البيئة تحتوي JDK 21 يفتقد `JAVA_COMPILER`، بينما بعض وحدات المشروع تتطلب Java 17 toolchain ولا يوجد repository مهيأ لتنزيله. لذلك لا يوجد دليل على نجاح اختبارات Android الحالية. يجب إصلاح JDK/toolchain في CI أولاً، ثم تشغيل الاختبارات المحددة مع `--no-daemon --console=plain`.

## 6. خطة بوابات الإصدار

| البوابة | شروط المرور |
|---|---|
| **بوابة الإلغاء** | cancellation يمر كإلغاء، Call/native يتوقف، لا retry بعد الإلغاء، terminal event واحد، ولا handle قديم يمسح طلباً جديداً. |
| **بوابة السياق والمخرجات** | admission قبل dispatch، الصور داخلة في التكلفة، partial fallback معزول، والـEOF المبتور ليس نجاحاً. |
| **بوابة البيانات** | MIME canonical ومطابق للbytes، staging ذري، draft لا يمسح قبل commit، extraction bounded، وUnicode round-trip سليم. |
| **بوابة lifecycle** | close حقيقي للمحرك، watchdog ملغى، native unload مؤكد عند CRITICAL، وUI collectors مرتبطة بـSTARTED. |
| **بوابة المزود** | request contract يحمل system/history، status وRetry-After مصنفان، model IDs نشطة، parser typed، ولا secrets في logs/URL. |
| **بوابة الاختبار** | JDK كامل في CI، اختبارات JVM/MockWebServer حتمية تمر، واختبارات emulator/device وhealth probe موثقة أو الميزات غير مفعلة. |

## 7. خلاصة القرار

المشكلة ليست عيباً منفرداً في parser أو provider، بل **غياب عقد موحد للملكية والإلغاء والميزانية والنهاية** عبر UI وorchestrator وbackend وtransport وnative runtime. الإصلاح الآمن يبدأ بتوحيد execution ID وdeadline وterminal result، ثم يضيف admission للسياق وعزل المحاولات، وبعد ذلك يعالج MIME/extraction/rendering. أي إطلاق قبل ذلك قد يبدو ناجحاً في المسار السعيد لكنه يترك sockets وnative decode وwatchdogs بعد الإلغاء، أو يحفظ إجابات مبتورة ويخفي سبب الخطأ عبر fallback.

## المراجع

[1]: https://kotlinlang.org/docs/coroutines-cancellation.html "Kotlin cancellation and timeouts"
[2]: https://developer.android.com/kotlin/coroutines/coroutines-best-practices "Android coroutines best practices"
[3]: https://developer.android.com/topic/libraries/architecture/coroutines "Android coroutines and lifecycle"
[4]: https://developer.android.com/topic/performance/memory/manage-app-memory "Manage app memory on Android"
[5]: https://developer.android.com/training/data-storage/shared/documents-files "Store and access documents and files"
[6]: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument "OpenDocument activity result contract"
[7]: https://developer.android.com/reference/android/text/BidiFormatter "Android BidiFormatter"
[8]: https://developer.android.com/reference/kotlin/androidx/compose/ui/text/style/TextDirection "Compose TextDirection"
[9]: https://www.unicode.org/reports/tr9/ "Unicode Bidirectional Algorithm"
[10]: https://www.rfc-editor.org/rfc/rfc8259 "The JavaScript Object Notation (JSON) Data Interchange Format"
[11]: https://ai.google.dev/api/generate-content "Gemini Generate Content API"
[12]: https://ai.google.dev/gemini-api/docs/models "Gemini models"
[13]: https://ai.google.dev/gemini-api/docs/deprecations "Gemini deprecations"
[14]: https://developers.openai.com/api/docs/guides/images-vision "OpenAI images and vision guide"
[15]: https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create/ "OpenAI chat completions create reference"
[16]: https://developers.openai.com/api/docs/guides/rate-limits "OpenAI rate limits"
[17]: https://docs.anthropic.com/en/api/messages "Anthropic Messages API"
[18]: https://platform.claude.com/docs/en/api/errors "Anthropic API errors"
[19]: https://docs.anthropic.com/en/docs/about-claude/model-deprecations "Anthropic model deprecations"
[20]: https://github.com/square/okhttp/blob/master/okhttp-coroutines/README.md "OkHttp coroutine integration"
[21]: https://github.com/ggml-org/llama.cpp "llama.cpp upstream repository"
[22]: https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android "llama.cpp Android example"
[23]: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md "llama.cpp server README"
[24]: https://github.com/launchdarkly/okhttp-eventsource "OkHttp EventSource reference implementation"
[25]: https://github.com/aallam/openai-kotlin "Coroutine-based OpenAI Kotlin client comparison"
[26]: https://html.spec.whatwg.org/multipage/server-sent-events.html "HTML Server-sent events specification"
[27]: https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/io/InputStreamReader.html "Java InputStreamReader"
[28]: https://github.com/mikepenz/multiplatform-markdown-renderer "Multiplatform Markdown renderer"
[29]: https://github.com/JetBrains/markdown "JetBrains Markdown parser"
[30]: https://github.com/GrapheneOS/PdfViewer "GrapheneOS PDF viewer reference"
[31]: https://github.com/dankito/TextExtraction "Text extraction reference"
[32]: https://github.com/ggml-org/llama.cpp/issues/12038 "llama.cpp context shift issue"

---

**حدود هذا التقرير:** جميع أرقام الأسطر والمسارات الواردة أعلاه مأخوذة من نتائج التدقيق المستقلة المقدمة لهذه المهمة. لم يُجرَ تعديل على `/home/ubuntu/AIRI-Project` خارج إنشاء هذا الملف، ولم تُقدَّم نتيجة اختبار تشغيلية على أنها ناجحة عندما كانت بيئة JDK أو Android/JNI أو مفاتيح API غير متاحة.


## 10. تنفيذ الإصلاحات والتحقق التنفيذي

بعد التدقيق الساكن، نُفذت الإصلاحات الحرجة في طبقات الاستجابة فقط دون إعادة تصميم شاشة الدردشة. في طبقة الموصلات، أصبح إلغاء coroutine يمرر كـ`CancellationException` ولا يتحول إلى `provider_error` أو retry، كما يمنع `RemoteLlmConnector` التنفيذ بعد `disconnect`. وفي طبقة adapters، أُضيف إغلاق اتصال `HttpURLConnection` الخاص بالمحاولة عند إلغاء الـJob، وأصبحت قراءة SSE بترميز UTF-8 صريحًا، مع اشتراط terminal event في Gemini وAnthropic قبل اعتبار stream ناجحًا.

في مسار التوجيه، أضيفت بوابة `CapabilityProfile.canFit` تحسب prompt/history estimate، و`maxTokens`، وreserve، وتكلفة محافظة لأجزاء الصور قبل dispatch؛ كما ترفض backends المحلية والسحابية الطلبات المتجاوزة للميزانية بدل انتظار فشل متأخر. وفي `HybridOrchestrator` أصبحت tokens كل محاولة معزولة في buffer ولا تُرسل إلى UI أو التخزين إلا بعد terminal success، وبذلك لا يمكن تركيب partial output من backend فاشل مع fallback ناجح. وربط `LocalLlamaBackend.generate` إلغاء coroutine بـ`llamaManager.cancelStream` مع terminal guard يمنع callback متأخرًا من إعلان نجاح.

في مسار المرفقات، أصبح MIME يطبع case وparameters، ويُفحص payload الصوري عبر magic bytes قبل تكوين `ImagePart`؛ لا يعود PNG أو WebP أو HEIC يُوسم تلقائيًا على أنه JPEG. كما أصبح staging ذريًا عبر ملف مؤقت ثم rename، مع حذف الملفات الجزئية وفرض حد الحجم. عولجت أخطاء lint الأربعة التي ظهرت في التحقق النهائي دون تغيير مرئي في تصميم الشاشة، وأضيفت موارد الإسبانية والصينية اللازمة للتعريب.

### نتائج التحقق

| البوابة | النتيجة |
|---|---|
| `:core-domain:allTests` | ناجحة |
| `:app:compileDebugKotlin` | ناجحة |
| `:app:testDebugUnitTest` | ناجحة؛ 330 اختبارًا مكتملًا دون failures أو errors |
| `:app:lintDebug` | ناجحة؛ لا أخطاء lint، مع بقاء تحذيرات deprecated غير مانعة |
| `:app:assembleDebug` | ناجحة |
| التحقق من native داخل APK | ناجح؛ `lib/arm64-v8a/libairi_native.so` موجود بحجم 3,758,640 bytes |
| `git diff --check` | ناجح |
| فحص الأسرار والملفات الجزئية | لا أسرار ثابتة أو ملفات `.part/.tmp` ضمن مصدر المشروع |

تمت هذه الاختبارات باستخدام Android SDK حقيقي وJDK 17 كامل يحتوي `javac`. لم تُشغّل طلبات API حقيقية لعدم وجود مفاتيح اختبار، ولم يُشغّل emulator؛ لذلك تثبت النتائج صحة الترجمة، العقود، الاختبارات الحتمية، lint، وتجميع native APK، لكنها لا تدّعي صحة مفاتيح مزود خارجي أو أداء جهاز بعينه.

## 11. حدود الإصدار المتبقية

ما زال يلزم قبل الإطلاق العام اختبار الأجهزة الفعلية لمسار `onTrimMemory(CRITICAL)` والتأكد من تحرير `llama_model` الأصلي فعليًا، وإجراء MockWebServer أو health probe لكل مزود بمفاتيح اختبار للتحقق من model IDs وstatus codes وRetry-After، وتشغيل اختبارات URI providers وPDF/Office/OCR. هذه ليست أعذارًا عن الإصلاحات المنفذة؛ إنها حدود إثبات لا يمكن استنتاجها من APK/JVM وحدهما.
