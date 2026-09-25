# تقرير مراجعة مسار الاستجابة في AIRI

## النطاق والمنهج

راجعت ملف المهمة المرفق كاملًا، ثم تتبعت المسار الفعلي من `ChatViewModel.sendMessageInternal` إلى `AgentLoop.callLLM` و`ExecutionRequest` و`HybridOrchestrator` ثم `RuntimeRouter` و`LocalLlamaBackend`/`CloudBackend`، وصولًا إلى حفظ الرسالة في `MemoryManager` وتحديث `StateFlow` وواجهة المحادثة. كما قارنت التاريخ المرتبط بمسار الاستجابة في 19 و20 أغسطس، ثم راجعت الإصلاحات اللاحقة الخاصة بملكية التنفيذ وتسليم النتيجة.

المرجع التاريخي يبين أن invariants المطلوبة ليست بحاجة إلى محرك جديد: commits `3770e221` و`9122f3f6` و`2044f333` و`4e52a0fd` أضافت أو شددت ربط session/model، generation gate، الخصوصية، وفصل محاولات fallback وتسليم terminal exactly-once. كلا الفرعين الحاليين `main` و`cp-foundation` يحتويان هذه الطبقة عبر `ExecutionIntegrity.kt` و`ExecutionRequest.identity` و`HybridOrchestrator.TerminalDeliveryGuard`.

## النتيجة الجذرية الأصغر

لم يثبت التتبع وجود regression حالي يعيد ردًا من cache أو mock أو local fallback عند اختيار cloud-only. على العكس، `ResponseOptimizer.tryFastResponse` محصور في جدول ردود قصيرة مع بوابة طول ومطابقة محافظة، ولا يطابق prompt عشوائيًا طويلًا؛ كما أن `HybridOrchestrator` لا يستعمل fast path، و`CloudBackend` لا يرسل إلى local backend داخله. لذلك لم أحذف capability الصحيحة، وأضفت اختبارات تمنع overmatch للرسائل العشوائية وتحافظ على greetings المقصودة.

التحسين الصغير المنفذ هو إضافة `requestedModelId` إلى metadata الآمنة في حدث بدء orchestrator، إلى جانب `requestId` و`executionId`، من دون تسجيل prompt أو context أو credentials. هذا يجعل تتبع سؤال “أي model/provider نفذ الطلب؟” قابلاً للإثبات في debug trace. كما أضيف assertion يثبت أن retry لا يفقد model identity.

المشكلة التي كانت تمنع CI ليست في runtime response pipeline؛ كانت أخطاء Kotlin صريحة في آخر commit داخل `AdvancedInputBar.kt` و`ChatScreen.kt`: استدعاء `stringResource` داخل `Modifier.semantics`, استخدام `maxWidth` من receiver غير صحيح داخل modifier lambda، وcallback attachment مستنتج كـ`Function0<Result<Unit>?>` بدل `Function0<Unit>`. عولجت هذه المواضع فقط، دون تغيير سلوك أو تصميم UI.

## العقود المثبتة

| العقد | الدليل في الكود | حالة المراجعة |
|---|---|---|
| هوية الطلب | `ExecutionIdentity` يحمل `requestId`, `sessionId`, `executionId` | مثبت |
| هوية retry | `ExecutionRequest.copy` يحافظ على `identity` و`requestedModelId` | مثبت باختبار جديد |
| منع callbacks القديمة | `ExecutionGenerationGate` و`generationGate.accepts(genId)` | مثبت باختبارات موجودة |
| تسليم terminal مرة واحدة | `TerminalDeliveryGuard` في `HybridOrchestrator` | مثبت باختبار موجود |
| عزل fallback | `attemptBuffer` لكل backend وعدم إرسال النص قبل نجاح المحاولة | مثبت بالمراجعة |
| إلغاء local | `LocalLlamaBackend.cancelStream` يصل إلى `LlamaManager.cancelStream`، و`Job.invokeOnCompletion` يغلق native generation | مثبت بالمراجعة |
| إلغاء cloud | `CloudBackend.cancelStream` يلغي `activeRequestJob`، وadapters تفصل الاتصال عند إلغاء coroutine | مثبت بالمراجعة |
| فشل صريح | `CloudErrorType` و`onError` و`ExecutionLifecycleState.FAILED` | مثبت بالمراجعة |
| fast path | جدول محدود، بوابة طول، عدم مطابقة prompt عشوائي أو طويل | مثبت باختبارات جديدة |
| metadata الآمنة | request/execution/model في `RuntimeEventLog`، دون raw input أو secrets | محسّن في هذا التغيير |

## الاختبارات المضافة

أضيفت `ResponseOptimizerTest` لتغطية greeting المشروع، prompt عشوائي، prompt طويل يحتوي كلمة greeting، وprompt عربي غير معروف. كما وُسع `ExecutionIntegrityTest` ليثبت بقاء `requestedModelId` مع retry إلى جانب execution identity.

## مصفوفة التحقق

لا أدرج نتيجة `PASS` لاختبار يتطلب Android SDK أو جهازًا أو credentials غير متاحة في sandbox. لذلك يجب اعتبار الصفوف التالية نتائج تنفيذية فقط بعد تشغيل GitHub Actions على commit الدفع:

| Mode | Network | Model | Expected result | Evidence status |
|---|---|---|---|---|
| Local | OFF | valid loaded local | real local response | يحتاج جهاز/نموذج فعلي |
| Local | ON | valid loaded local | local response without cloud | يحتاج جهاز/نموذج فعلي |
| Cloud-only | ON | valid provider/model | cloud response with provider/model trace | يحتاج credentials |
| Cloud-only | OFF | cloud-only | honest network error; no answer | contract path reviewed; runtime test يحتاج device |
| Cloud-only | ON | invalid model | explicit model/provider error | adapter/error contract reviewed |
| Local-only | OFF | missing/unloaded model | explicit model readiness error | local guard reviewed |
| Either | ON/OFF | cancellation | cancellation terminal state; next request owns new generation | generation/backend guards reviewed |
| Any | any | random unknown prompt | no canned fast response | covered by JVM test |

## CI/build evidence

قبل التغيير، فشل آخر تشغيل للفرعين في `:app:compileDebugKotlin` بالأخطاء الأربعة المحددة أعلاه. تشغيل JVM المحلي تعذر قبل compilation لأن sandbox لا يحتوي Android SDK (`SDK location not found`)، لذلك لا يصح اعتبار الاختبارات المحلية ناجحة. التحقق النهائي المطلوب هو تشغيل GitHub Actions بعد الدفع، ثم فحص كل jobs في `AIRI Android CI`, `AIRI Deep Audit`, `AIRI Architecture Audit`, و`AIRI Oracle` على SHA الدفع.

## مصادر رسمية

تعتمد مراجعة الإلغاء على [Kotlin Cancellation and Timeouts](https://kotlinlang.org/docs/coroutines-cancellation.html)، الذي يوضح أن الإلغاء تعاوني وأن `Job`, `isActive`, `ensureActive`, و`finally` ضرورية لإيقاف العمل وتنظيف الموارد. كما راجعت [Android coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)، خصوصًا جعل العمل قابلاً للإلغاء وعدم ابتلاع exceptions داخل `viewModelScope`. ولعقد streaming/model path راجعت [Google Gemini generate-content API](https://ai.google.dev/api/generate-content)، الذي يحدد model path المطلوب و`models.streamGenerateContent` وإرجاع stream من `GenerateContentResponse`.

## حدود المراجعة

لم أغيّر JNI أو Room schema أو routing architecture، ولم أختبر provider حقيقيًا أو local model فعليًا داخل sandbox؛ كلاهما يتطلب Android SDK/device أو credentials. بعد استكمال CI يجب إبقاء هذه الحدود واضحة في release evidence بدل تحويل contract/static tests إلى ادعاء نجاح runtime.
