# تقرير Postmortem لمشروع AIRI

**تاريخ المراجعة:** 2026-09-26  
**نطاق الأدلة:** طبقات التنفيذ والتوجيه، agent runtime، الذاكرة وRAG، الموصلات، واجهة الدردشة، الأمن والخصوصية ودورة الحياة.  
**قاعدة القراءة:** كل بند موسوم **Bug مثبت** يعني أن عيب العقد أو مسار البيانات مثبت من الكود الذي تمت مراجعته. لا يعني ذلك أن الأثر الإنتاجي شُوهد على جهاز حقيقي؛ حيث يلزم ذلك أوسم البند بأنه يحتاج قياساً/اختباراً تكاملياً. البنود الموسومة **مخاطرة تحتاج قياساً** لا تُرفع إلى عيب مؤكد في الأثر دون اختبار إضافي.

## الملخص التنفيذي

توجد **مشكلتان Critical، و26 High، و10 Medium، ولا توجد مشكلة Low** بعد دمج النتائج المتكررة. أهم مانعي إطلاق هما: (1) غياب هوية مالك موثوقة عن USER memory، بما يسمح نظرياً بخلط بيانات الحسابات في التخزين والاسترجاع، و(2) عدم إبطال التوليد عند حذف/مسح الجلسة، بما يسمح بعودة رد متأخر إلى واجهة أو جلسة بديلة.

هناك أيضاً مخاطر إطلاق مباشرة في failover السحابي، إلغاء graph execution، ثبات هوية الخطط والنماذج، تحميل الجلسات، الحذف، وسلامة queues الخاصة بالـembedding/extraction. كما توجد ثغرات خصوصية وأسرار: صور لا تمر عبر PrivacyGuard، context عالمي بلا scope، PII لجهات الاتصال في المخرجات، مفتاح IFTTT في URL، وأسرار cloud في job لطلبات pull request.

**قرار الإطلاق المقترح:** لا إطلاق قبل معالجة Critical، ثم إغلاق مجموعة High الخاصة بالهوية والإلغاء والتوجيه والحذف، وتشغيل اختبارات Room/Compose/instrumentation وموصلات حقيقية أو MockServer. تعذر تشغيل Gradle قبل الاختبارات بسبب غياب Android SDK (`ANDROID_HOME` و`local.properties` غير مضبوطين)، لذلك لا يوجد دليل تشغيل آلي ناجح لهذه المراجعة.

## مصفوفة الأولوية

| الأولوية | العدد بعد إزالة التكرار | قرار ما قبل الإطلاق | النمط الغالب |
|---|---:|---|---|
| **Critical** | 2 | حاجز إطلاق مطلق | تسريب/خلط بيانات، وعودة بيانات بعد الحذف |
| **High** | 26 | يجب الإغلاق أو قبول مخاطرة موثق من مالك المنتج مع اختبار بديل | فشل تنفيذ، إلغاء، هوية، حذف، أسرار، lifecycle |
| **Medium** | 10 | يجب الإغلاق قبل الإصدار العام؛ يمكن ترتيبها بعد Critical/High التشغيلية | حالات فشل صامتة، عقود UI/API، متانة |
| **Low** | 0 | — | لم يثبت بند بهذه الأولوية |

> **ملاحظة عن التكرار:** دُمجت ملاحظتا USER memory المتطابقتان من مراجعة الذاكرة ومراجعة الأمن، وملاحظتا ContextEngine، وملاحظتا RAG fallback/إخفاء الأخطاء. لم تُدمج البنود المختلفة التي تشترك في كلمة «scope» إذا كان لكل منها مسار أو إصلاح أو اختبار مستقل.

## Critical

### C-01 — USER memory بلا هوية مالك/حساب

- **الحالة:** **Bug مثبت** في عقد التخزين والاستعلام؛ اختبار تبديل الحساب الفعلي ما زال مطلوباً لقياس الأثر النهائي.
- **الموقع:** `core-domain/src/commonMain/kotlin/airi/core/memory/MemoryEntry.kt:8-14`; `app/src/main/java/com/airi/assistant/memory/entity/MemoryEntities.kt:15-46`; `app/src/main/java/com/airi/assistant/memory/dao/MemoryDao.kt:39-58`; `app/src/main/java/com/airi/assistant/memory/repository/MemoryManager.kt:125-145,340-356`; `app/src/main/java/com/airi/assistant/memory/rag/RagRetriever.kt:101-115`.
- **الدليل:** `MemoryScope` يفرض `ownerId` غير فارغ، لكن `ChatMessage`/الصفوف المستخدمة للحفظ لا تحمل `ownerId` أو `accountId`. عمليات الحفظ والاسترجاع تعتمد على `sessionId`/`projectId`/privacy، واستعلام USER لا يفرض هوية المالك.
- **الأثر:** بعد تبديل الحساب يمكن أن تدخل USER memories القديمة في recall/prompt للحساب الجديد. هذا cross-account data disclosure وidentity drift، وليس مجرد اختلاف ترتيب.
- **الإصلاح:** أضف `authenticatedOwnerId` إلى الرسائل والفهارس وكل write/read/delete وRAG query، من مصدر auth موثوق لا من prompt أو session. ارفض USER بلا owner، واعزل/امسح البيانات عند logout/account switch. صنّف legacy rows كـunknown ولا تسترجعها قبل re-association صريح.
- **اختبار التحقق:** اختبار Room/RAG بحسابين A وB: خزّن USER memory لـA ثم بدّل إلى B، وتحقق أن `getScopedLongTermMemories` و`RagRetriever` و`semanticSearch` لا تعيدها، وأن تغيير `sessionId` وحده لا يغير owner.

### C-02 — حذف/مسح الجلسة لا يبطل التوليد المتأخر

- **الحالة:** **Bug مثبت** في حارس الكتابة وتدفق الإلغاء؛ ترتيب completion الفعلي يحتاج coroutine test.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:1433-1450,1488-1510,2087-2100`.
- **الدليل:** `clearCurrentSessionForPrivacy` و`deleteSession` يحذفان الجلسة ويمسحان `_messages`، لكن لا يستدعيان `cancelGeneration` ولا يزيدان generation token. التوليد يحتفظ بـ`sessionId` القديم، والحارس يتحقق من `generationId` فقط؛ يمكن بعدها كتابة رد assistant وإضافته إلى الواجهة.
- **الأثر:** قد تعود بيانات يفترض أنها حُذفت إلى جلسة بديلة أو إلى projection الواجهة، وقد يُكتب رد في جلسة محذوفة.
- **الإصلاح:** اجعل الحذف/المسح انتقالاً ذرياً: ألغِ job، أبطل generation وsession token قبل حذف السجلات، واشترط تطابق generation وsession الحالي في كل كتابة Room/UI. استخدم منسقاً واحداً بدل launches مستقلة.
- **اختبار التحقق:** ابدأ توليداً بطيئاً، نفّذ clear أو delete، ثم أطلق completion متأخراً؛ يجب ألا يوجد assistant row للجلسة المحذوفة، وألا تتغير `_messages` البديلة.

## High

### H-01 — failover السحابي لا يتبع إعلان availability

- **الحالة:** **Bug مثبت** في data-flow؛ اختبار CloudBackend/factory الحقيقي مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/execution/backend/CloudBackend.kt:84-97,131-135,141-173,275-285`.
- **الدليل:** `isAvailable` يعيد true إذا وُجد مفتاح لأي مزود في `FAILOVER_PRIORITY`، لكن `generateStream` يبني queue من `primary` فقط. إذا كان preferred بلا مفتاح ومزود آخر صالحاً، يُتخطى primary وينتهي المسار بالفشل دون تجربة البديل، رغم تعليق الملف.
- **الأثر:** فشل سحابي أو fallback محلي غير متوقع مع وجود مزود صالح؛ اختبارات FakeBackend قد لا تكشفه.
- **الإصلاح:** ابنِ queue من primary ثم `FAILOVER_PRIORITY` عندما لا يوجد requested provider صريح، واستخدم snapshot موحداً للمفاتيح/config، وسجّل سبب skip/failover. حافظ على ownership عند الطلب الصريح.
- **التحقق:** OpenRouter key فقط مع preferred Gemini يجب أن يستدعي OpenRouter؛ طلب صريح لـGemini يجب ألا يبدّل الملكية بصمت.

### H-02 — بوابة ChatViewModel تمنع المحلي عند غياب إذن الإنترنت

- **الحالة:** **Bug مثبت** في تناقض عقد التوجيه.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:1706-1719`; عقد الاختيار في `app/src/main/java/com/airi/assistant/execution/router/RoutingPolicy.kt:97-99`.
- **الدليل:** إذا كان remote مهيأً وmode ليس LOCAL_ONLY وinternet permission غير ممنوح، يعيد `sendMessage` false قبل generation، بينما RoutingPolicy يختار local عند غياب الإذن ما لم يوجد target سحابي صريح.
- **الأثر:** hybrid مع local model جاهز يفشل بدلاً من fallback محلي.
- **الإصلاح:** طبّق المنع فقط عند cloud-only أو target صريح، أو دع RuntimeRouter يقرر ثم أعط خطأً فقط عند غياب أي قدرة. استخدم `effectiveMode` لا الخام.
- **التحقق:** local ready + remote + HYBRID + permission=false يصل إلى AgentLoop محلياً؛ explicit cloud target يرفض بوضوح.

### H-03 — `generate` قد ينفذ نموذجاً غير المطلوب

- **الحالة:** **Bug مثبت** في عدم اتساق عقدي streaming وbatch.
- **الموقع:** `app/src/main/java/com/airi/assistant/execution/backend/LocalLlamaBackend.kt:248-252,272-303`.
- **الدليل:** `generateStream` يرفض mismatch بين requested وloaded model، لكن `generate` يسجل warning ثم يستدعي النموذج المحمل.
- **الأثر:** تُنسب نتيجة/أداة إلى هوية نموذج لم ينفذ الطلب، مع اختلاف جودة أو سلوك محتمل.
- **الإصلاح:** وحّد العقد برفض mismatch مع `model_binding_mismatch`، أو نفّذ rebind صريحاً مثبتاً. أضف modelId الفعلي إلى النتيجة والسجل.
- **التحقق:** loaded=A وrequested=B يجب أن يعيد Failure ولا يستدعي native generation في المسارين.

### H-04 — `cancel(planId)` لا يوقف graph execution

- **الحالة:** **Bug مثبت** في مسار cancellation؛ JNI/dispatcher لم يُثبت runtime.
- **الموقع:** `app/src/main/java/com/airi/assistant/agent/execution/runtime/ExecutionGraphRuntime.kt:43-47,54-61,85-166`.
- **الدليل:** `cancel` يحدّث snapshot فقط، لكن الحلقة لا تقرأ state ولا تفحص الإلغاء قبل waves/nodes. finalization يحسب من `failed` فقط وقد يكتب COMPLETED/FAILED فوق CANCELLED.
- **الأثر:** استمرار side effects أو nodes تالية، وظهور المهمة ناجحة بعد طلب الإلغاء.
- **الإصلاح:** احتفظ بـJob/JobHandle وألغِه فعلياً، افحص state قبل كل wave/node وبعد await، وأوقف finalization إذا سبق الإلغاء.
- **التحقق:** node طويل ثم cancel أثناء الانتظار؛ لا يعمل node التابع، snapshot النهائي CANCELLED، ولا PlanCompleted نجاحاً.

### H-05 — `planId` عشوائي يمنع resume والإلغاء المستقر

- **الحالة:** **Bug مثبت** في هوية correlation؛ process-death test مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/agent/execution/runtime/ExecutionGraphRuntime.kt:54-59,68-75,263-277`.
- **الدليل:** `buildRuntimePlan` يولد UUID جديداً لكل resume/execute، ثم يبحث resume بالـUUID الجديد لا بمعرف snapshot السابق.
- **الأثر:** فقدان completed nodes وإعادة side effects أو فقدان cancellation/deduplication بعد retry/process death.
- **الإصلاح:** مرّر planId ثابتاً أو اشتقه من accepted plan، وافصل executionId لكل attempt. حمّل snapshot قبل runtime random وتحقق من scope.
- **التحقق:** execute جزئياً ثم runtime جديد وresume؛ يجب تحميل نفس planId وcompletedNodeIds، وأن يعمل cancel بالمعرف نفسه.

### H-06 — lambdas طويلة العمر من ChatViewModel داخل singleton

- **الحالة:** **Bug مثبت** في lifecycle ownership؛ leak/arrival الفعلي يحتاج lifecycle/heap test.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:1154-1195,1373-1384`.
- **الدليل:** `init` يحقن lambdas تلتقط ViewModel وcontext في `ServiceLocator._androidAgent`/engine المشتركين، بينما `onCleared` لا يفك الحقول أو owner token.
- **الأثر:** يمكن أن يستدعي singleton state قديم بعد rotation/owner switch، مع احتفاظ بالـViewModel.
- **الإصلاح:** owner-scoped injection و`detach()`، امسح gate/planner إن كانا تابعين لهذا owner، واربط callbacks بـgeneration/owner token.
- **التحقق:** بعد onCleared لا يغير استدعاء gate/planner state القديم؛ كرر recreation مع heap/lifecycle check.

### H-07 — embedding model مختلف بنفس dim يخلط فضاءات المتجهات

- **الحالة:** **Bug مثبت** في schema/query؛ دقة النتائج تحتاج قياساً.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/embedding/EmbeddingService.kt:69-75,180-220`; `.../entity/MessageEmbedding.kt:43-51`; `.../dao/EmbeddingDao.kt:27-28`.
- **الدليل:** التخزين/query يرشحان على dim وsession فقط ولا يخزنان model identifier/fingerprint؛ dot product يقارن vectors من نماذج قديمة وجديدة كما لو كانت فضاءً واحداً.
- **الأثر:** recall صامت خاطئ وحقن ذكريات غير ذات صلة.
- **الإصلاح:** خزّن fingerprint/version وnormalization/config، ورشح بها؛ اعزل أو أعد فهرسة embeddings عند model change قبل isReady.
- **التحقق:** نموذجان mock بنفس dim ومتجهات مختلفة؛ بعد تحميل الثاني لا تعود rows من الأول.

### H-08 — fallback الزمني في RAG معلن لكنه غير منفذ

- **الحالة:** **Bug مثبت** في fallback؛ أثر جودة الإجابة يحتاج اختبار JNI/DB.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/rag/RagRetriever.kt:17-20,97-103,129-133,150-154,197-202`; و`app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:1919-1926`.
- **الدليل:** عند عدم جاهزية semantic memory تصبح القائمة فارغة ولا يُستدعى `getRecentMessages`. `runCatching` يحول semantic exception إلى empty context، وViewModel يحول retrieval failures إلى نص فارغ.
- **الأثر:** cold start أو JNI/DB failure يرسل prompt بلا سياق، بينما caller لا يفرق بين no-hit وbackend error.
- **الإصلاح:** أعد recent messages ضمن نفس session/project/privacy gates، وميّز `NoContext` عن `Unavailable` و`Failed` مع telemetry/retry.
- **التحقق:** embedding غير جاهز أو exception؛ النتيجة تحتوي recent messages المصرح بها فقط، والخطأ مصنف لا empty success.

### H-09 — سباق check-then-insert يكرر long-term memory

- **الحالة:** **Bug مثبت** في التزامن؛ concurrency test مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/repository/MemoryManager.kt:113-149`; `.../dao/MemoryDao.kt:36-37`.
- **الدليل:** `findLongTermMemoryId` ثم insert عمليتان منفصلتان بلا transaction أو unique constraint.
- **الأثر:** duplicate facts وrecall متكرر، ونتائج Stored متعددة بدلاً من Duplicate.
- **الإصلاح:** transaction واحدة للـcheck/insert/prune، canonical hash وunique index يشمل owner/scope/project/session، وتحويل SQLITE_CONSTRAINT إلى Duplicate.
- **التحقق:** عشرات coroutines لنفس content؛ صف واحد وStored واحدة والباقي Duplicate، مع rollback test.

### H-10 — embedding واستخراج facts fire-and-forget بلا retry

- **الحالة:** **Bug مثبت** في تسليم الحالة؛ معدل الفشل يحتاج قياساً.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/repository/MemoryManager.kt:172-187,192-225`; `app/src/main/java/com/airi/assistant/core/ServiceLocator.kt:402-412`.
- **الدليل:** `embedAndStore` وdurable-fact extraction يعملان في scopes منفصلة، failure يسجل فقط، ولا توجد outbox/status/retry؛ تعاد الرسالة قبل اكتمال الاستخراج.
- **الأثر:** history موجودة بلا embedding/facts بعد JNI أو process death، مع نجاح ضمني لا يبين pending/failed.
- **الإصلاح:** durable queue مرتبطة بـmessageId مع status/retry/backoff/DLQ أو نتيجة Pending/Failed واضحة.
- **التحقق:** حقن exception ثم restart/worker؛ يجب وجود pending job وإعادة المحاولة، وألا يعلن UI نجاحاً نهائياً غير مشروط.

### H-11 — ContextEngine عالمي بلا session/account scope

- **الحالة:** **Bug مثبت** في contract التخزين؛ leakage الفعلي يحتاج account-switch test.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/repository/ContextEngine.kt:21-57`; `.../entity/ContextCacheEntity.kt:6-13`; `.../dao/ContextCacheDao.kt:12-18`.
- **الدليل:** singleton وDB global، و`saveContext` لا يقبل owner/session/project، و`getRecentContext` يقرأ آخر capture زمنياً فقط.
- **الأثر:** screen context من حساب/جلسة أخرى قد يدخل التخطيط، مع تخزين accessibility content حساس.
- **الإصلاح:** owner/session/project/purpose/expiry في schema وpredicates، مسح عند logout/switch، redaction/consent، وحاجز يمنع الكتابة أثناء wipe.
- **التحقق:** captures لحسابين/جلستين ثم query وlogout وexpiry؛ لا يعاد row بلا owner ولا يدخل prompt global.

### H-12 — ProjectKnowledge يعلن INDEXED بعد فشل persist

- **الحالة:** **Bug مثبت** في نتيجة API؛ filesystem failure test مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/knowledge/ProjectKnowledgeManager.kt:105-128,201-211,214-237`.
- **الدليل:** mutation in-memory ثم `persist()` يبتلع IOException/rename/copy في `runCatching`، وبعدها `markIndexed(success=true)` وINDEXED.
- **الأثر:** UI يعتقد أن الفهرس محفوظ، ثم يختفي بعد restart ويعطي RAG no hits.
- **الإصلاح:** `persist` يرجع Result/يرمي، ولا markIndexed إلا بعد fsync/atomic replace؛ backup/journal وإعادة الحالة FAILED.
- **التحقق:** filesystem غير قابل للكتابة أو rename failure؛ النتيجة FAILED وmarkIndexed(false)، وmanager جديد لا يدعي INDEXED.

### H-13 — Contacts permission يستخدم هوية connector خاطئة

- **الحالة:** **Bug مثبت** في mapping؛ Compose/ActivityResult test مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/screens/ConnectorsScreen.kt:121-127,345-356`; `.../connector/local/ContactsConnector.kt:31-34`.
- **الدليل:** الموصل مسجل باسم `contacts`، لكن مسار الشاشة يطلب permission فقط لـ`contacts_local` ثم يعيد الاتصال بهذه الهوية غير المسجلة.
- **الأثر:** لا يظهر permission flow الصحيح، وبعد grant لا تصبح الهوية الفعلية connected.
- **الإصلاح:** مصدر ID واحد من registry/connector، واستخدم `contacts` في الشرط والcallback وأعد الاتصال بنفس instance.
- **التحقق:** denial ثم grant؛ callback يستدعي registry.get("contacts") وتصبح الحالة connected/healthy.

### H-14 — كتالوج Google يعرض IDs غير مسجلة

- **الحالة:** **Bug مثبت** في identity contract؛ authorization الحقيقي يحتاج integration test.
- **الموقع:** `app/src/main/java/com/airi/assistant/connector/ConnectorDefinition.kt:126-130`; `.../ConnectorBootstrap.kt:95-98`; `.../ConnectorRegistry.kt:39-50`; `.../ui/screens/ConnectorCatalog.kt:34-36,79`.
- **الدليل:** catalog يعرض `google_gmail/calendar/drive` كـPARTIAL، بينما bootstrap يسجل adapter واحداً باسم `google`؛ UI يتيح CTA على IDs لا يملكها registry.
- **الأثر:** طلب اتصال لا ينفذ أو يضلل المستخدم بشأن نطاقات الصلاحية.
- **الإصلاح:** adapters حقيقية لكل ID مع scopes، أو entry واحد `google` بقدرات واضحة؛ امنع CTA لأي catalog-only entry.
- **التحقق:** كل عنصر غير COMING_SOON له adapter مطابق، والضغط على كل Google item يستدعي الهوية الصحيحة.

### H-15 — Cancellation تتحول إلى Failure قابلة لإعادة المحاولة

- **الحالة:** **Bug مثبت** في exception handling؛ نقل HTTP الفعلي يحتاج اختبار cancellation.
- **الموقع:** `app/src/main/java/com/airi/assistant/connector/local/VoiceConnector.kt:94-102`; `.../connector/app/GoogleConnector.kt:138-158,178-180,208-210`; `.../ConnectorRuntimeManager.kt:82-99`.
- **الدليل:** `runCatching`/`catch(Exception)` يلتقطان cancellation ويعيدان Failure retryable، والـruntime يعيد كل retryable حتى maxRetries.
- **الأثر:** طلب ملغى قد يعاد أو يستمر، وقد تتكرر بيانات صوتية/side effects.
- **الإصلاح:** افصل `CancellationException` وأعد رميها قبل تحويل الأخطاء، وألغِ inflight state.
- **التحقق:** إلغاء أثناء transcribe وHTTP؛ caller يستلم cancellation، عدد المحاولات 1 ولا retry loop.

### H-16 — ContactsOutput يحتوي الاسم والرقم كاملين

- **الحالة:** **Bug مثبت** في least-data contract؛ مرور PII إلى remote يحتاج تتبع/قياس.
- **الموقع:** `app/src/main/java/com/airi/assistant/connector/local/ContactsConnector.kt:73-88,91-96,111-125`.
- **الدليل:** DISPLAY_NAME وNUMBER يخرجان كـ`Name (number)` وفي النص، بلا capability/privacy boundary خاص.
- **الأثر:** caller/agent قد يمرر PII إلى نموذج/موصل سحابي أو يسجله؛ READ_CONTACTS لا يثبت موافقة cloud.
- **الإصلاح:** capability `read.contacts` وتصنيف بيانات، least-data response، حجب الرقم افتراضياً ومنع remote أو طلب confirmation.
- **التحقق:** list/search لا يعيدان أرقاماً كاملة افتراضياً، وcloud route يرفض أو يطلب موافقة صريحة.

### H-17 — IFTTT key في URL

- **الحالة:** **Bug مثبت**: السر ظاهر في request URL؛ مدى تسجيله الإنتاجي **مخاطرة تحتاج قياساً**.
- **الموقع:** `app/src/main/java/com/airi/assistant/connector/app/IftttConnector.kt:162-175`.
- **الدليل:** URL يُبنى من `.../with/key/$key` والمفتاح من EncryptedSharedPreferences.
- **الأثر:** يمكن أن يظهر السر في proxy/server/access logs أو diagnostics؛ من يملكه يستطيع تشغيل أحداث IFTTT.
- **الإصلاح:** header/body إن كان provider يسمح، منع URL logging وredaction، تدوير المفتاح عند الانكشاف. إن كان path إلزامياً فاعزل الطلب ووثق القيد.
- **التحقق:** MockWebServer/interceptor يتأكد أن المفتاح لا يظهر في logs أو exceptions أو activity events.

### H-18 — deleteAllData لا يمسح `semantic_memory`

- **الحالة:** **Bug مثبت** في مسار المحو.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/repository/StorageRepository.kt:191-201`; `.../memory/entity/MemoryEntities.kt:52-58`.
- **الدليل:** deleteAllData يحذف الجداول المذكورة ومنها episodic/chat/embedding/context/audit، ولا يوجد DELETE لـsemantic_memory.
- **الأثر:** تبقى UserPreference بعد deleteAccount/eraseLocalData رغم نجاح العملية.
- **الإصلاح:** DAO ومسح semantic_memory داخل نفس transaction، مع عدّاد تحقق لكل جدول قبل success.
- **التحقق:** أنشئ UserPreference ثم wipe؛ count semantic_memory يساوي صفراً.

### H-19 — wipe يتسابق مع الكتابات الخلفية ويبتلع cancellation

- **الحالة:** **Bug مثبت** في orchestration؛ race instrumented test مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt:194-199,239-257,391-411`; `.../memory/repository/ContextEngine.kt:27-51`.
- **الدليل:** `cancelAllWork()` لا ينتظر quiescence، وsaveContext يطلق coroutine بلا deletion gate؛ `runStep` يلتقط Throwable بما فيه CancellationException.
- **الأثر:** worker قد يعيد كتابة بعد المسح، أو يعلن wipe جزئياً كنجاح.
- **الإصلاح:** await quiescence، mutex/epoch يمنع الكتابة أثناء وبعد wipe، وإعادة رمي cancellation.
- **التحقق:** saveContext/worker بالتوازي مع wipe؛ بعد انتظار الجميع لا توجد كتابة لاحقة، وإلغاء delete واضح كـCancelled.

### H-20 — SecretVault لا يُفرغ أثناء محو البيانات

- **الحالة:** **Bug مثبت** في مسار wipe.
- **الموقع:** `app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt:260-264,338-345`; `.../vault/SecretVault.kt:67-76,209-214`.
- **الدليل:** deleteAccount وeraseLocalData يستدعيان `secureStorage.clearAll` فقط، لا `SecretVault.clear`; vault يحتفظ بالقدرات وبـfallback raw map.
- **الأثر:** تبقى الأسرار والقدرات في الذاكرة حتى TTL أو نهاية العملية.
- **الإصلاح:** استدعاء SecretVault.clear ضمن CREDENTIAL_WIPE، وإبطال capabilities وإغلاق store.
- **التحقق:** خزّن secret وcapability ثم wipe؛ يجب رفض capability وعدم وجود secret، بما فيه fallback.

### H-21 — connectorId غير الصالح يتحول إلى scope عام

- **الحالة:** **Bug مثبت** في fail-closed semantics.
- **الموقع:** `app/src/main/java/com/airi/assistant/vault/SecretVault.kt:84-95,131-143,180-195`.
- **الدليل:** `normalizeConnectorId` يعيد null للمل malformed، لكن storeProjectSecret يعيد true بعد إسقاطه، وuseProjectCapability يقبل null.
- **الأثر:** قد ينحرف secret من connector scope إلى project scope أوسع.
- **الإصلاح:** ميّز absent عن invalid وارفض كل non-empty ID غير المطابق للنمط في التخزين والإصدار والاستخدام.
- **التحقق:** IDs فيها `/` أو whitespace تعيد false/null/DENIED؛ اختبر connector-a وconnector-b منعاً للتداخل.

### H-22 — فشل consumer يستهلك capability ويبدو نجاحاً

- **الحالة:** **Bug مثبت** في نتيجة API؛ أثر providers يحتاج اختبار.
- **الموقع:** `app/src/main/java/com/airi/assistant/vault/SecretVault.kt:230-262`.
- **الدليل:** remainingUses ينقص والقدرة تحذف قبل consumer، ثم `runCatching` يعيد `CONSUMED` بلا value عند exception.
- **الأثر:** network failure أو cancellation يفقد القدرة ويضلل caller.
- **الإصلاح:** لا تلتقط cancellation، وأعد Failure صريحاً، أو نفّذ reservation ثم commit بعد نجاح consumer مع retry واضح.
- **التحقق:** consumer يرمي IOException وCancellationException؛ لا تُعد النتيجة consumed نجاحاً ولا تفقد token بلا سياسة معلنة.

### H-23 — cloud secrets متاحة في job لطلبات pull request

- **الحالة:** **Bug مثبت في YAML exposure**؛ قابلية التسريب الفعلية **مخاطرة تحتاج قياساً/تحققاً في CI**.
- **الموقع:** `.github/workflows/android_build.yml:3-23,53-64`.
- **الدليل:** `OPENAI_API_KEY` و`GEMINI_API_KEY` على مستوى job يشغل سكربتات checkout في push وpull_request، وcloud smoke اختياري.
- **الأثر:** تغيير PR قد يشغل Python/Gradle ويقرأ env أو يسرب الأسرار.
- **الإصلاح:** mock افتراضي في untrusted job، smoke موثوق بعد merge وفي خطوة معزولة وبأقل secret.
- **التحقق:** PR داخلي/خارجي يثبت عدم رؤية الأسرار، وskip لا يُحسب كنجاح cloud test.

### H-24 — تبديل الجلسات أثناء load/generation يخلط state

- **الحالة:** **Bug مثبت** في غياب request/session token؛ ترتيب coroutines يحتاج test.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:1456-1473,1787-1806,1824-1831`.
- **الدليل:** كل load مستقل بلا sequence؛ completion لجلسة A يكتب بعد اختيار B، وgeneration يضيف messages دون فحص current session.
- **الأثر:** رسائل وسياق LLM يظهران في جلسة خاطئة، وRoom لا يطابق projection.
- **الإصلاح:** token أحادي لكل load، إلغاء job السابق، وتطبيق النتائج فقط عند تطابق token/session/generation؛ أو منع التبديل أثناء التوليد.
- **التحقق:** عكس ترتيب load(A/B) وcompletion بعد switch؛ B فقط يبقى في UI/history.

### H-25 — فشل تحميل التاريخ يتحول إلى جلسة فارغة

- **الحالة:** **Bug مثبت** في error state.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:1461-1472`.
- **الدليل:** `runCatching(loadSession).getOrElse { emptyList() }` ثم تعيين الجلسة و`setHistory(emptyList())` بلا Failed/Retry.
- **الأثر:** فقدان ظاهري صامت وقد يرسل المستخدم فوق شاشة فارغة.
- **الإصلاح:** Loading/Loaded/Failed، إبقاء الرسائل السابقة عند الفشل، وإظهار retry دون محتوى حساس.
- **التحقق:** injected SQLException يبقي state السابق ولا يستدعي setHistory فارغاً؛ empty session الحقيقية تبقى Empty.

### H-26 — pending summary غير مربوط بمالك الجلسة

- **الحالة:** **Bug مثبت**؛ يعتمد الأثر على إيقاع التلخيص وتبديل الجلسة.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:2170-2185,646-650`; `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt:846-853`.
- **الدليل:** `_pendingSummary` String فقط؛ `acceptSummary(currentSessionId, summary)` يستخدم جلسة Compose الحالية لا session الملتقطة عند بدء المهمة.
- **الأثر:** summary من A قد يُحفظ في B، وreject يمسح حالة عامة بلا owner.
- **الإصلاح:** كائن يحوي ownerSessionId وrequest/generation token، وعند القبول استخدم owner الملتقط؛ أبطل عند switch/delete.
- **التحقق:** summarizer يكمل بعد A→B؛ لا banner ولا write لـB، أو تُهمل النتيجة حسب العقد.

## Medium

### M-01 — الصور تتجاوز PrivacyGuard في BALANCED

- **الحالة:** **Bug مثبت** في contract sanitization؛ إرسال provider الحقيقي يحتاج قياساً.
- **الموقع:** `app/src/main/java/com/airi/assistant/execution/privacy/PrivacyGuard.kt:46-65`; `app/src/main/java/com/airi/assistant/execution/cloud/OpenAIAdapter.kt:235-243`.
- **الدليل:** guard ينسخ النص والتاريخ فقط ولا يعالج `imageParts`؛ adapter يضع base64 الخام في data URL.
- **الأثر:** قد تُرسل صورة حساسة كاملة رغم redaction النصي وعدم وجود موافقة cloud مستقلة.
- **الإصلاح:** سياسة صريحة للصور: block/redact في BALANCED، requiresVision، limits MIME/size، وعدم تسجيل base64.
- **التحقق:** image marker حساس لا يظهر في sanitized request أو adapter body عند وضع الحجب.

### M-02 — Connect/Disconnect غير متسلسلين

- **الحالة:** **Bug مثبت** في state machine؛ السباق الفعلي يحتاج test.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ConnectorsViewModel.kt:55-67`; `.../ui/screens/ConnectorDetailsScreen.kt:147-152`.
- **الدليل:** كل ضغطة coroutine مستقلة بلا Mutex/generation، بينما inflight tracking لا يزامن lifecycle mutations.
- **الأثر:** ترتيب عكسي قد يجعل state لا يمثل آخر نية للمستخدم.
- **الإصلاح:** Mutex لكل connector أو job replace، تعطيل الزر أثناء العملية، ونتيجة operation observable.
- **التحقق:** fake connector بإيقاعات معكوسة؛ connect→disconnect→connect لا يتداخل والنتيجة النهائية تطابق آخر طلب.

### M-03 — فشل connector يُخفى ولا يصل إلى state/UI

- **الحالة:** **Bug مثبت** في observability والعقد.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ConnectorsViewModel.kt:55-67`; `.../connector/ConnectorRegistry.kt:77-82,125-128`.
- **الدليل:** `runCatching` يتجاهل نتيجة connect/disconnect وconnectAll best-effort؛ StateFlow يبقى على حالته السابقة.
- **الأثر:** بطاقة Connected/Disconnected قديمة بعد secure storage أو handshake failure.
- **الإصلاح:** ConnectorState(error, healthy=false) أو operation result، مع structured redacted error وإعادة رمي cancellation، وتقرير لكل connector في connectAll.
- **التحقق:** fake يرمي من connect/disconnect؛ state يعرض failure ولا يبقى connected=true.

### M-04 — زر Test connection يعمل مع Coming Soon

- **الحالة:** **Bug مثبت** في UI contract.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/screens/ConnectorDetailsScreen.kt:147-170`; `.../ui/viewmodel/ConnectorsViewModel.kt:55-60`.
- **الدليل:** Connect يعطل نفسه عند `isComingSoon`، لكن Test connection لا يعطل الزر ويستمر `onTry` بعد رفض connect.
- **الأثر:** feedback يوهم باختبار غير قابل للتنفيذ.
- **الإصلاح:** تعطيل Test/Manage أو عرض preview صريح بلا workflow.
- **التحقق:** كل COMING_SOON لا يطلق connect أو onTry.

### M-05 — Zapier لا يتحقق من HTTP status ولا يغلق response

- **الحالة:** **Bug مثبت** في HTTP resource/error handling؛ مزود حقيقي غير مختبر.
- **الموقع:** `app/src/main/java/com/airi/assistant/connector/app/ZapierConnector.kt:155-179`.
- **الدليل:** `execute()` دون `use` ودون `response.isSuccessful` قبل parsing/storage.
- **الأثر:** body فاشل يحوي token قد يصبح نجاحاً، وتسرب موارد response.
- **الإصلاح:** `execute().use`، status قبل parse، تحقق token_type/scope/identity، ومسح token الجزئي عند failure.
- **التحقق:** 400/401/500 وbody ناقص/متعارض لا يخزن token ولا يصبح connected، وMockWebServer يثبت الإغلاق.

### M-06 — import يقرأ session ID قبل اكتمال createNewSession

- **الحالة:** **Bug مثبت** في sequencing؛ يعتمد ظهوره على delay.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:3685-3693,1403-1418`.
- **الدليل:** `createNewSession()` يطلق launch داخلياً ولا ينتظر، ثم يقرأ caller `_currentSessionId` فوراً وقد يبقى blank.
- **الأثر:** إسقاط import أو callback count مضلل.
- **الإصلاح:** `suspend createNewSession` يعيد ID أو `currentSessionOrCreate` داخل نفس coroutine.
- **التحقق:** delay في create ثم import؛ كل الصفوف تحفظ في الجلسة المنشأة.

### M-07 — deleteMessage لا يتحقق من ملكية session

- **الحالة:** **Bug مثبت** في DAO predicate؛ stale callback test مطلوب.
- **الموقع:** `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt:3519-3525`; `app/src/main/java/com/airi/assistant/memory/dao/MemoryDao.kt:147-151`.
- **الدليل:** DELETE يعتمد على messageId فقط، بلا sessionId أو row ownership.
- **الأثر:** callback قديم بعد switch يحذف row من جلسة أخرى.
- **الإصلاح:** `id AND sessionId` والتحقق من expected/current قبل تحديث UI.
- **التحقق:** stale message من A أثناء B لا يحذف أي row خارج المالك.

### M-08 — SecureStorage يبلع فشل الكتابة

- **الحالة:** **Bug مثبت** في نتيجة persistence؛ فشل Keystore يحتاج instrumentation.
- **الموقع:** `app/src/main/java/com/airi/assistant/auth/SecureStorage.kt:88-106,172-181`.
- **الدليل:** `safePutString` يمسك Throwable، وsave methods لا تعيد نتيجة ولا تتحقق من القراءة؛ fallback in-memory يفقد البيانات بعد process death.
- **الأثر:** Settings قد تعرض نجاحاً بينما المفتاح غير دائم.
- **الإصلاح:** Result يميز persisted/in-memory/failed، read-after-write، ومنع ادعاء الديمومة عند !isEncrypted.
- **التحقق:** injected Keystore failure ثم recreation؛ النتيجة توضّح الفشل ولا تدعي persisted.

### M-09 — exportBackup ينسخ Room الخام إلى destination غير مقيد

- **الحالة:** **مخاطرة تحتاج قياساً/تتبع call site**؛ عقد API المعرّض مثبت، ولم يثبت مستدعٍ حالي.
- **الموقع:** `app/src/main/java/com/airi/assistant/memory/AiriDatabase.kt:230-267`.
- **الدليل:** checkpoint ثم `copyTo(destFile)` بلا تشفير أو تحقق من app-private destination/redaction.
- **الأثر:** destination مشاركة قد يحتوي محادثات/context/audit كنص SQLite.
- **الإصلاح:** URI/share contract بتأكيد المستخدم، تشفير بمفتاح منفصل أو رفض destination الخارجي، retention وحذف.
- **التحقق:** export خارجي يرفض أو ينتج ملفاً مشفراً؛ تتبع call sites قبل اعتبارها خطراً مستغلاً.

### M-10 — deferred initialization يفشل مع إعلان AIRI Ready

- **الحالة:** **Bug مثبت** في readiness contract؛ معدل وقوع الفشل يحتاج fault injection.
- **الموقع:** `app/src/main/java/com/airi/assistant/app/AIRIApplication.kt:148-168,205-213,232-237`.
- **الدليل:** secretVault وDB وRAG داخل runCatching واحد، onFailure يسجل warning فقط، ويستمر onCreate إلى Ready.
- **الأثر:** واجهة جاهزة وخدمات حساسة غير مهيأة أو بلا retry.
- **الإصلاح:** مراحل readiness صريحة، منع العمليات الحساسة قبل dependencies، retry/backoff أو failure UI.
- **التحقق:** cold start مع فشل injected لخدمة؛ لا تُعلن readiness ولا تقبل العمليات الحساسة قبل التعافي.

## الإيجابيات التي لم تجدها المراجعة

هذه ليست ضماناً بعدم وجود عيوب خارج النطاق، لكنها نقاط لم يظهر فيها فشل ضمن الأدلة التي فُحصت:

- اختبارات pure الخاصة بـRoutingPolicy وmemory admission/ranking/retention وnormalization وUI policies تغطي الحدود والسياسات المعلنة، وإن كانت لا تغطي adapters/Room/runtime الحقيقية.
- وُجدت اختبارات `ExecutionIntegrity` و`ExecutionGenerationGate` و`ModelIdentity` و`CloudErrorMapper`، لكن لم تثبت مسارات CloudBackend/factory أو كل حالات الهوية المتغيرة.
- اختبارات OAuthStateRegistry تثبت uniqueness وone-time consume وPKCE hash.
- اختبارات سياسات Google/GitHub تثبت القراءة/الموافقة وبعض منع عمليات الكتابة؛ كما أن Google scopes المقروءة هي Gmail readonly وCalendar readonly وDrive metadata readonly وفق الفحص النصي.
- اختبارات migration وDAO insert/update وAiriDatabase migration موجودة، ولم تُسجل مشكلة في هذه المسارات ضمن النتائج؛ لكنها لا تغطي wipe/semantic_memory/backup.
- توجد اختبارات لـSecretVault في capability/TTL/revoke وبعض عزل project/connector، وSecureApiKeyStore في hash/persistence/overwrite/clear؛ الفجوات محددة أدناه.
- لم تُسجل تعديلات على الملفات أثناء المراجعة، ولا توجد عناصر في قائمة الفشل المقدمة.

## فجوات الاختبار والقيود

1. تعذر تشغيل Gradle قبل الاختبارات بسبب غياب Android SDK: لا `ANDROID_HOME` ولا `local.properties` مع `sdk.dir`.
2. لم تُنفذ instrumentation/real-device tests لـJNI/llama.cpp أو HTTP providers أو Compose/ActivityResult.
3. لا توجد اختبارات مباشرة لتبديل الحساب/logout تربط auth owner بـRoom/RAG، أو تثبت مسح semantic_memory وContextEngine وSecretVault.
4. لا توجد اختبارات cancellation/race لترتيب: generation مع clear/delete/session switch، graph cancel مع node completion، wipe مع background writers، أو connect/disconnect.
5. لا توجد اختبارات model replacement بنفس embedding dim، أو fallback recent messages عند embedding unavailable/exception، أو persistence failure لفهرس المشروع.
6. اختبارات routing تستخدم FakeBackend ولا تغطي CloudBackend/factory/failover الحقيقيين؛ واختبارات connectors لا تغطي Contacts permission، Google catalog mapping، cancellation/retry، Zapier response، أو OAuth provider فعلي.
7. لم يُثبت وجود HTTP logging interceptor إنتاجي؛ تسرب IFTTT في URL ثابت من الكود، أما مدى ظهوره في logs فهو قياس مطلوب.
8. لم يُثبت call site لـ`AiriDatabase.exportBackup`؛ الملاحظة M-09 تخص API contract، ويجب تتبع الاستخدام قبل تقدير التعرض.
9. لم تُراجع طبقات خارج النطاق إلا عند الحاجة لتتبع ServiceLocator وModelSettings وregistry وDAO والمستهلكين المباشرين؛ لا يجوز تعميم النتائج على sync/cloud أو طبقات غير مفحوصة.

## خطة الإصلاح المرتبة قبل الإطلاق

### بوابة 0 — قبل أي Release Candidate

1. إصلاح C-01 وC-02 أولاً، مع migration/transaction واختبارات account isolation وdelete/generation race.
2. ضبط Android SDK في CI والبيئة المحلية، ثم تشغيل الاختبارات الكاملة؛ لا تُستخدم نتائج pure tests كبديل عن instrumentation.
3. إنشاء owner واضح لكل بند: identity/data deletion، execution/runtime، memory/RAG، connectors/security، UI/state.

### بوابة 1 — سلامة الهوية والإلغاء والحذف

1. تطبيق ownerId الموثوق على memory وcontext وكل queries/deletes، وعزل legacy rows.
2. جعل session/generation/request tokens جزءاً من كل load/generation/summary/import/delete callback.
3. جعل graph cancellation حقيقياً، planId ثابتاً، وwipe ينتظر quiescence ويمنع الكتابة اللاحقة.
4. إدراج semantic_memory وSecretVault في wipe transaction، ومعالجة invalid connector IDs وcapability failures fail-closed.
5. **معيار الخروج:** اختبارات race وRoom تعطي isolation صفرية، ولا row أو callback يعود بعد deletion.

### بوابة 2 — التنفيذ والتوجيه والذاكرة

1. توحيد cloud availability وqueue/failover، وعقد local model mismatch، وإصلاح بوابة cloud-vs-local.
2. تنفيذ recent-message fallback وتمييز no-hit/error، وإضافة durable embedding/fact queue.
3. إضافة model fingerprint للـembeddings، transaction/unique index للـmemory، وatomic persistence لـProjectKnowledge.
4. **معيار الخروج:** اختبارات provider failover، model binding، model replacement، JNI/DB failure، concurrency، وrestart/resume ناجحة.

### بوابة 3 — الخصوصية والأسرار والموصلات

1. سياسة imageParts في PrivacyGuard، least-data لجهات الاتصال، وowner/privacy scope لـscreen captures.
2. إزالة secret من IFTTT URL أو إثبات redaction الشامل، وعزل cloud secrets عن untrusted PR jobs.
3. توحيد IDs لـContacts/Google وتعطيل Coming Soon CTA، وفصل CancellationException عن retries.
4. تصحيح Zapier status/use وSecureStorage result/read-after-write.
5. **معيار الخروج:** MockServer/interceptor وinstrumentation يثبتان عدم تسريب secrets/PII، وعدم retry بعد cancellation.

### بوابة 4 — الحالة والـUX والجاهزية

1. إضافة SessionLoadState وConnector operation errors وinitialization readiness state بدلاً من empty/silent success.
2. تسلسل Connect/Disconnect، إصلاح import sequencing وdelete ownership، وتأكيد pending summary owner.
3. تحديد قرار M-09 بعد تتبع call sites؛ إما تقييد API أو توثيق أن export مشفر ومؤكد.
4. **معيار الخروج:** اختبارات Compose/ViewModel بترتيبات completion معكوسة، وfault injection للـDB/Keystore/init، دون state stale أو success مضلل.

### بوابة 5 — قياس ومراقبة ما بعد الإصلاح

- سجّل structured telemetry غير حساس لأسباب skip/failover، retrieval state، pending/failed jobs، cancellation، wipe completion، connector operation state، وreadiness.
- لا تسجل prompts أو base64 أو PII أو secrets. راقب معدلات failover، retrieval unavailable، retry-after-cancel، orphaned embeddings، وfailed persistence.
- أعد فتح تقييم البنود الموسومة «مخاطرة تحتاج قياساً» بعد تشغيل instrumentation/real-provider tests؛ لا تُعتبر مغلقة بمجرد تعديل الكود.

## خلاصة القرار

**الإطلاق غير موصى به حالياً.** يوجد دليل ساكن قوي على عيبين Critical وعدة عقود High مكسورة. لا توجد نتيجة اختبار آلي ناجحة في هذه المراجعة بسبب بيئة Android غير المهيأة؛ لذلك يلزم أولاً إصلاح البوابات أعلاه ثم إعادة تشغيل الاختبارات والتحقق من الأثر الفعلي، مع إبقاء البنود التي تحتاج قياساً منفصلة عن bugs المثبتة.
