# تقرير تدقيق AIRI الساكن لجاهزية الإصدار

**نطاق التقرير:** دمج مستقل لنتائج التدقيق المقدمة على الرأسين `main=587d6210` و`cp-foundation=c29ea79f`، وهما متطابقان في الشجرة المفحوصة. لم يُعدّل أي source code.

> **تنبيه منهجي:** هذا **تدقيق ساكن**. الأدلة تثبت مسارات كود/إعدادات، لكنها لا تثبت السلوك الفعلي وقت التشغيل، ولا سلامة Android/Native/Provider/Doze/Keystore أو الترتيب الزمني للـcallbacks. أبقيت درجات `likely` و`needs-runtime-proof` حيث يلزم اختبار runtime.

## الملخص التنفيذي

- العدد النهائي بعد دمج التكرار حسب root cause: **104 finding**؛ التوزيع: **P0=0، P1=42، P2=57، P3=5، P4=0**.
- أعلى المخاطر قبل release هي: تجاوز حدود الأدوات والـterminal، تسريب الذاكرة عبر session/expiry/privacy scopes، SSRF وغياب destination validation، تكرار side effects عند retry، فساد lifecycle/cancellation، وفجوة CI/AAB/signing evidence.
- توجد عيوب مؤكدة في البناء والـCI تمنع اعتبار الإصدار قابلاً للتدقيق: لا AAB، لا تحقق توقيع/هش/Mapping، لا بوابة PR لـWindows، ولا build/test gate موحد يغطي Android/KMP/native.
- تم دمج النتائج المتكررة فقط عندما كان root cause واحداً (مثل session scope، SSRF destination validation، credential wipe، وحذف الجلسة أثناء generation)، مع إبقاء الأدلة والأسطح المختلفة داخل finding المدمج.

## خريطة مراحل التدقيق والتغطية

| المرحلة | المساحات المفحوصة | مخرجات/حدود |
|---|---|---|
| 1. Inventory والحدود | Gradle/KMP، manifests، dependency graph، CMake/JNI، architecture docs | ثبتت مطابقة الرأسين؛ لم يُحل graph فعلياً لغياب Android SDK. |
| 2. Agent وexecution | orchestrators، plans، dispatcher، status/trace، retries، terminal | تتبّع مباشر للمسارات الحالية والاختبارات ذات الصلة. |
| 3. Memory وprivacy | Room/migrations، RAG/embeddings، retention، backup، sync، deletion | لا backend/Firestore runtime؛ corruption/WAL races تحتاج runtime. |
| 4. Skills/connectors | HTTP/OAuth، permissions، custom skills، providers، offline/fallback | لا credentials أو endpoints حقيقية؛ cancellation/redirect تحتاج device/network. |
| 5. Chat/UI/lifecycle | ChatViewModel، attachments، Compose، RTL، accessibility، voice/coroutines | لا visual/device/process-death pass؛ بعض layout/callback findings غير مثبتة runtime. |
| 6. Native/local model | JNI/llama، image/vision، model load/unload، RAM/cancel | لا APK/NDK/ARM64 instrumentation؛ الحواجز المصدرية مفقودة. |
| 7. Release/CI | workflows، release-health، packaging، signing، coverage، provenance | ثبتت فجوات workflow والـevidence من YAML/scripts. |

## جدول عدد النتائج حسب severity/layer

| Layer | P0 | P1 | P2 | P3 | P4 | الإجمالي |
|---|---:|---:|---:|---:|---:|---:|
| Accessibility | 0 | 0 | 1 | 0 | 0 | 1 |
| Adaptive UI | 0 | 0 | 1 | 0 | 0 | 1 |
| Agent/Parsing | 0 | 0 | 1 | 0 | 0 | 1 |
| Agent/Security | 0 | 2 | 0 | 0 | 0 | 2 |
| Approval | 0 | 0 | 1 | 0 | 0 | 1 |
| Architecture/Inventory | 0 | 0 | 0 | 1 | 0 | 1 |
| Archive | 0 | 0 | 1 | 0 | 0 | 1 |
| Attachments/Privacy | 0 | 1 | 0 | 0 | 0 | 1 |
| Attachments/Retention | 0 | 0 | 1 | 0 | 0 | 1 |
| Backup/Integrity | 0 | 0 | 1 | 0 | 0 | 1 |
| Build/ABI | 0 | 1 | 0 | 0 | 0 | 1 |
| Build/Native | 0 | 0 | 0 | 1 | 0 | 1 |
| Build/Signing | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Build | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Cloud | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Coverage | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Device | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Diagnostics | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Evidence | 0 | 1 | 0 | 0 | 0 | 1 |
| CI/Oracle | 0 | 0 | 1 | 0 | 0 | 1 |
| CI/Windows | 0 | 1 | 0 | 0 | 0 | 1 |
| Chat/Lifecycle | 0 | 0 | 1 | 0 | 0 | 1 |
| Cloud Sync/Data loss | 0 | 2 | 0 | 0 | 0 | 2 |
| Cloud Sync/Privacy | 0 | 0 | 1 | 0 | 0 | 1 |
| Concurrency/Execution | 0 | 0 | 1 | 0 | 0 | 1 |
| Connectors/Cancellation | 0 | 1 | 0 | 0 | 0 | 1 |
| Connectors/HTTP | 0 | 0 | 1 | 0 | 0 | 1 |
| Connectors/Idempotency | 0 | 1 | 0 | 0 | 0 | 1 |
| Connectors/Lifecycle | 0 | 1 | 1 | 0 | 0 | 2 |
| Crash handling | 0 | 0 | 1 | 0 | 0 | 1 |
| Crash privacy | 0 | 0 | 1 | 0 | 0 | 1 |
| Credential deletion | 0 | 0 | 1 | 0 | 0 | 1 |
| Deletion/Lifecycle | 0 | 1 | 0 | 0 | 0 | 1 |
| Dependencies | 0 | 0 | 1 | 0 | 0 | 1 |
| Desktop Release | 0 | 0 | 1 | 0 | 0 | 1 |
| Embedding | 0 | 0 | 1 | 0 | 0 | 1 |
| Execution/Isolation | 0 | 1 | 0 | 0 | 0 | 1 |
| Execution/Reset | 0 | 0 | 1 | 0 | 0 | 1 |
| Execution/Restore | 0 | 0 | 1 | 0 | 0 | 1 |
| FileProvider | 0 | 0 | 0 | 1 | 0 | 1 |
| Filesystem | 0 | 0 | 1 | 0 | 0 | 1 |
| GitHub/HTTP | 0 | 0 | 1 | 0 | 0 | 1 |
| IFTTT/Health | 0 | 0 | 1 | 0 | 0 | 1 |
| LLM Privacy | 0 | 0 | 1 | 0 | 0 | 1 |
| Lifecycle | 0 | 1 | 0 | 0 | 0 | 1 |
| Localization | 0 | 0 | 1 | 0 | 0 | 1 |
| Memory/Corruption | 0 | 0 | 1 | 0 | 0 | 1 |
| Memory/Privacy | 0 | 1 | 0 | 0 | 0 | 1 |
| Native/Cancel | 0 | 0 | 1 | 0 | 0 | 1 |
| Native/Cancellation | 0 | 1 | 0 | 0 | 0 | 1 |
| Native/Image | 0 | 1 | 0 | 0 | 0 | 1 |
| Native/RAM | 0 | 0 | 1 | 0 | 0 | 1 |
| Native/Unload | 0 | 0 | 1 | 0 | 0 | 1 |
| Native/Vision | 0 | 2 | 0 | 0 | 0 | 2 |
| Navigation/Local | 0 | 1 | 0 | 0 | 0 | 1 |
| Network/SSRF | 0 | 1 | 0 | 0 | 0 | 1 |
| Notion/Input | 0 | 0 | 1 | 0 | 0 | 1 |
| OAuth UX | 0 | 0 | 1 | 0 | 0 | 1 |
| OAuth boundary | 0 | 0 | 1 | 0 | 0 | 1 |
| OAuth/Google | 0 | 0 | 1 | 0 | 0 | 1 |
| OAuth/Zapier | 0 | 0 | 1 | 0 | 0 | 1 |
| Packaging/ABI | 0 | 0 | 1 | 0 | 0 | 1 |
| Plan/Account | 0 | 1 | 0 | 0 | 0 | 1 |
| Plan/Storage | 0 | 0 | 1 | 0 | 0 | 1 |
| Privacy/Logs | 0 | 0 | 1 | 0 | 0 | 1 |
| Provenance | 0 | 0 | 1 | 0 | 0 | 1 |
| R8/Persistence | 0 | 1 | 0 | 0 | 0 | 1 |
| RAG/Model | 0 | 0 | 1 | 0 | 0 | 1 |
| RTL | 0 | 1 | 0 | 0 | 0 | 1 |
| Release Gate | 0 | 1 | 0 | 0 | 0 | 1 |
| Release versioning | 0 | 1 | 0 | 0 | 0 | 1 |
| Release/AAB | 0 | 1 | 0 | 0 | 0 | 1 |
| Retention | 0 | 0 | 1 | 0 | 0 | 1 |
| Retry/Integrity | 0 | 1 | 0 | 0 | 0 | 1 |
| Room/Lifecycle | 0 | 1 | 0 | 0 | 0 | 1 |
| Room/Recovery | 0 | 1 | 0 | 0 | 0 | 1 |
| Routing/Privacy | 0 | 1 | 0 | 0 | 0 | 1 |
| Runtime state | 0 | 0 | 0 | 1 | 0 | 1 |
| Secrets/HTTP | 0 | 0 | 1 | 0 | 0 | 1 |
| Secure storage | 0 | 0 | 1 | 0 | 0 | 1 |
| Signing evidence | 0 | 1 | 0 | 0 | 0 | 1 |
| Skills/Consent | 0 | 0 | 1 | 0 | 0 | 1 |
| Skills/Data loss | 0 | 1 | 0 | 0 | 0 | 1 |
| Skills/Input | 0 | 1 | 0 | 0 | 0 | 1 |
| Skills/Lifecycle | 0 | 1 | 0 | 0 | 0 | 1 |
| Skills/Resources | 0 | 1 | 0 | 0 | 0 | 1 |
| Startup | 0 | 0 | 1 | 0 | 0 | 1 |
| StateFlow | 0 | 0 | 1 | 0 | 0 | 1 |
| Supply chain | 0 | 1 | 0 | 0 | 0 | 1 |
| Terminal/Concurrency | 0 | 0 | 1 | 0 | 0 | 1 |
| Token cache | 0 | 0 | 1 | 0 | 0 | 1 |
| Tools/Offline | 0 | 0 | 1 | 0 | 0 | 1 |
| Tools/Registry | 0 | 1 | 0 | 0 | 0 | 1 |
| Trace | 0 | 1 | 0 | 0 | 0 | 1 |
| Transport | 0 | 0 | 0 | 1 | 0 | 1 |
| Voice | 0 | 1 | 1 | 0 | 0 | 2 |
| Voice/Lifecycle | 0 | 1 | 0 | 0 | 0 | 1 |
| Voice/Native | 0 | 1 | 0 | 0 | 0 | 1 |
| Voice/Ownership | 0 | 0 | 1 | 0 | 0 | 1 |
| **الإجمالي** | **0** | **42** | **57** | **5** | **0** | **104** |

## Release blockers

1. **P1 integrity/security:** terminal_execute وtool allowlist وmemory session scope وSSRF وcustom-skill integrity/consent تمنع اعتماد حدود capability آمنة.
2. **P1 data/lifecycle:** حذف الجلسة أثناء generation، cancellation، retry غير idempotent، Room singleton، Cloud Sync restore/batch، وnative vision cancel قد تفقد بيانات أو تكرر side effects.
3. **P1 release evidence:** CI لا ينتج AAB، لا يتحقق certificate/hash/mapping، وrelease-health غير موصول؛ لا يوجد artifact قابل للتدقيق لمسار Play.
4. **P1 compatibility:** local-only navigation، neutral RTL، arm64/minSdk mismatch، وR8 persisted models تكسر مسارات مدعومة أو الترقية.
5. **P2 conditional blockers:** resource exhaustion، plaintext traces، OAuth custom scheme، offline/provider handling، native RAM/vision bounds، وdevice/coverage gaps يجب إغلاقها أو قبولها بقرار مخاطر موثق قبل GA.

## Findings

### F1 — [P1] terminal_execute يتجاوز بوابة side-effect والسياق الدائم
- **Layer/category:** Agent/Security
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AgentLoopSideEffectPolicy.kt:17-31 لا يذكر terminal_execute في sideEffectingTools؛ AgentLoop.kt:383-392 يمرر ALLOW_READ؛ ToolDispatcher.kt:268-280 ينفذ TerminalRuntime، رغم ToolSchema.kt:128-133 يعلن dangerous=true.
- **Impact:** يمكن للنموذج أو prompt injection تشغيل shell بلا approval/ownership المعتاد، مع تعديل ملفات أو استهلاك موارد خارج lifecycle.
- **Remediation:** أدخله في سياسة side-effect، ارفضه افتراضياً بلا موافقة، واربطه بـexecutionId/actionId/task ownership وطبّق الحارس في dispatcher.

### F2 — [P1] لا يوجد تحقق فعلي من allowlist الأدوات المعلنة للجلسة
- **Layer/category:** Agent/Security
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AgentLoop.kt:124-158 يبني prompt من tools، لكن parseToolCall في :287-353 لا يتحقق من وجود الاسم فيها؛ ToolDispatcher.kt:67-307 يملك dispatch للحالات المعروفة.
- **Impact:** يمكن طلب أدوات غير معلنة، فتفشل capability isolation بين CHAT_ONLY والجلسات المقيدة.
- **Remediation:** أنشئ canonical ToolSchema map وارفض غير المعلن قبل trace/dispatch، مع تحقق schema والـdangerous policy.

### F3 — [P1] PrivacyGuard لا ينظف bytes المرفقات قبل cloud
- **Layer/category:** Attachments/Privacy
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** PrivacyGuard.kt:8-19,46-65 ينظف النص فقط؛ Gemini/OpenAI/Anthropic adapters تبني payload من imageParts/inlineDataParts.
- **Impact:** قد يرسل BALANCED صورة/مستنداً حساساً بالكامل إلى مزود خارجي.
- **Remediation:** عرّف سياسة attachments: حظر افتراضي أو scan/redaction/consent مستقل واختبارات لكل PrivacyLevel.

### F4 — [P1] مُتحقق Debug يطلب ABI arm64 غير المضمّن في Debug
- **Layer/category:** Build/ABI
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** app/build.gradle.kts:35-37 يضع arm64-v8a في defaultConfig، بينما :128-136 يضيف x86_64 لـdebug؛ registerNativeApkVerification() في :314-328 يبحث حرفياً عن lib/arm64-v8a/libairi_native.so، وتُربط المهمة بـassembleDebug في :337-345.
- **Impact:** يفشل verifier أو يعطي نتيجة مضللة عندما يكون APK Debug x86_64؛ وقد يُعتمد artifact لا يطابق ABI المتوقع.
- **Remediation:** اجعل verifier واعياً بالـvariant ويفحص ABI الفعلي، مع اختبار Debug/Release منفصلين.

### F5 — [P1] أدلة الإصدار (mapping/hash/apksigner) غير مولدة
- **Layer/category:** CI/Evidence
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** android_build.yml:137-155 يرفع APK فقط ولا bundle/apksigner/SHA256/mapping؛ app build :309-347 لا يعوض ذلك، والوثيقة تتطلبه.
- **Impact:** لا يمكن تدقيق أو تشخيص artifact الحالي.
- **Remediation:** ارفع APK/AAB/mapping/logs/hashes وcertificate evidence مع if-no-files-found:error.

### F6 — [P1] فجوة بوابة Windows على PR
- **Layer/category:** CI/Windows
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** desktop_windows.yml:3-14 يعرّف push/dispatch بلا pull_request؛ الاختبار/package في :42-48.
- **Impact:** يمكن دمج كسر desktop/core-domain دون check.
- **Remediation:** أضف pull_request/path filters واجعل :app-desktop:test وpackage check مطلوبين.

### F7 — [P1] Cloud Sync يعلن restored دون إدراج الصفوف
- **Layer/category:** Cloud Sync/Data loss
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** CloudSyncCoordinator.kt:246-253 يستدعي recordImportantMemory دون explicitlyRequested=true، بينما MemoryManager.kt:87-105 يعيد مباشرة؛ counter restored يزيد رغم ذلك.
- **Impact:** استعادة ذاكرة الجهاز الجديد صامتة وفاشلة.
- **Remediation:** استخدم restoreSyncedMemory موثوقاً مع ownership/ID وازدد counter بعد نجاح insert والتحقق.

### F8 — [P1] حد batch في pushMemories يفقد ذاكرة نهائياً
- **Layer/category:** Cloud Sync/Data loss
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** CloudSyncCoordinator.kt:187-207 يقرأ أحدث 500، يأخذ MAX_MEMORY_BATCH=100 ثم يضبط lastMemorySyncMs للوقت الحالي بلا cursor.
- **Impact:** الصفوف المتبقية لا تدخل دفعة لاحقة، فيحدث فقدان عبر الأجهزة.
- **Remediation:** استخدم keyset cursor timestamp,id وبatches متتابعة حتى النجاح واستعلم isMemory مباشرة.

### F9 — [P1] إلغاء connector يُبتلع ويصبح retryable
- **Layer/category:** Connectors/Cancellation
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** McpConnector.kt:47-53,85-91 يستخدم runCatching؛ Zapier/GitHub يمسكان Exception في :219-253؛ ConnectorRuntimeManager.kt:70-117 يعيد المحاولة للفشل.
- **Impact:** قد تستمر آثار جانبية أو retry بعد مغادرة الشاشة.
- **Remediation:** أعد رمي CancellationException ولا تعتبره retryable واربط OkHttp Call بالإلغاء.

### F10 — [P1] إعادة المحاولة قد تكرر webhook/automation
- **Layer/category:** Connectors/Idempotency
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ConnectorRuntimeManager.kt:95-117 يعيد Failure حتى ثلاث مرات؛ N8nConnector.kt:166-184 وN8nIntegration.kt:82-91 وZapierConnector.kt:250-253 يعيدون retryable بعد POST غير محسوم.
- **Impact:** قد ينفذ الخادم side effect ثم يضيع الرد فتتكرر الرسائل/المهام.
- **Remediation:** استخدم idempotency key/reconciliation وفصل read/429/5xx عن POST unknown outcome.

### F11 — [P1] سباق execute مع disconnect يعيد connector مفصولاً
- **Layer/category:** Connectors/Lifecycle
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ConnectorRuntimeManager.kt:37-56 يفحص marker مرة ثم ensureHealthy؛ Registry.kt:104-115 يمسح barrier، وconnectors لا تتحقق من marker.
- **Impact:** قد ينفذ طلب قديم side effect بعد Disconnect.
- **Remediation:** استخدم generation check قبل/بعد ensureHealthy وقبل execute ولا تمسح marker إلا بفعل user connect.

### F12 — [P1] حذف الجلسة لا يوقف generation وقد يعيد كتابة البيانات المحذوفة
- **Layer/category:** Deletion/Lifecycle
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** ChatViewModel.kt:1629-1655 يحذف ويبدل الجلسة بلا إلغاء/activeGeneration check؛ الكتابة تستخدم session الملتقط في :2200-2209 و2432-2439، وStorageRepository.kt:116-119 لا يمنع الكتابة اللاحقة.
- **Impact:** قد تعود رسائل بعد الحذف أو تظهر صفوف يتيمة، ما يهزم ضمان الخصوصية.
- **Remediation:** ألغِ generation قبل الحذف وانتظر writer أو افحص generation/deletion state ذرياً قبل كل write.

### F13 — [P1] تبديل conversations يعيد ربط plan القديم بالجديد
- **Layer/category:** Execution/Isolation
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatScreen.kt:343-349 يمرر session فقط؛ AgentPlanViewModel.kt:120-128 يحفظ context/steps القديمة؛ TaskExecutionTracker.kt:22-38,99-115 بلا reset session.
- **Impact:** تسريب trace وعرض owner/status خاطئ.
- **Remediation:** scope tracker بالsession/execution وامسح/detach القديم ولا persist قبل admission.

### F14 — [P1] إلغاء orchestrator يسجّل كـFAILED لا CANCELLED
- **Layer/category:** Lifecycle
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ProductionAgentOrchestrator.kt:441-449,491-500 يستعمل markStepFailed؛ :506-545 يستدعي markFailed، مع أن DurableTask.kt:273-280 يعرّف cancel كحالة مستقلة ولا يُستدعى.
- **Impact:** تختلط نية الإلغاء مع الفشل وقد تُفعّل retry أو رسائل خطأ.
- **Remediation:** مرّر سبب terminal مستقلاً واستعمل markCancelled/cancel واختبر cancel أثناء running وقبل/بعد wave.

### F15 — [P1] memory_recall والفallback قد يعرضان ذاكرة جلسة أخرى
- **Layer/category:** Memory/Privacy
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ToolDispatcher.kt:194-214 يستعمل getRecentMessages(5) عند غياب session/embedding؛ MemoryManager.kt:453-455 وMemoryDao.kt:27-30 يقرآن episodic_memory بلا session. المسار المباشر semanticSearch في MemoryManager.kt:432-434 وEmbeddingService.kt:193-220 لا يفرض privacy/expiry/project scope.
- **Impact:** تسريب cross-session، وقد تصل ذاكرة منتهية أو أعلى خصوصية إلى prompt/cloud.
- **Remediation:** اجعل session وMemoryRetrievalScope إلزاميين، طبّق privacy/expiry/project قبل الإرجاع، وارفض recall غير المعرّف واختبر جلستين.

### F16 — [P1] watchdog vision لا يوقف الحلقة native
- **Layer/category:** Native/Cancellation
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** LlamaManager.kt:2171-2185 يعلن cancel/timeout؛ LlamaBridge.cpp:2625-2635 لا يقرأ g_cancel_requested ويمسك LLAMA_LOCK منذ :2538.
- **Impact:** يستمر CPU/ذاكرة ويمسك lock بعد إبلاغ timeout وقد يسبب ANR.
- **Remediation:** افحص cancel داخل prefill/loop، نظف الموارد، واستخدم generation token للـwatchdog.

### F17 — [P1] أبعاد الصور بلا سقف وبحساب overflow
- **Layer/category:** Native/Image
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** LlamaManager.kt:2155-2162 يحسب expectedBytes كـInt؛ LlamaBridge.cpp:2554-2563 يعيد الحساب قبل التحقق ويحّول إلى uint32؛ LlamaNative.kt:242-245 يقر بعدم وجود cap.
- **Impact:** OOM أو قراءة/تخصيص غير مطابق مع صورة كبيرة/أبعاد ملتفة.
- **Remediation:** استخدم Long/overflow checks وسقوف width/height/bytes قبل JNI وفي C++ مع downscale.

### F18 — [P1] mmproj يبقى مرتبطاً بنموذج قديم أثناء swap
- **Layer/category:** Native/Vision
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** LlamaBridge.cpp:1062-1063 يحرر g_ctx/g_model دون g_mtmd_ctx؛ :2476-2495 ينشئه، :2540-2543 يفحص non-null؛ ModelController.kt:251-281 يطلق autoLoad بلا generation.
- **Impact:** UAF/عدم توافق weights أو crash عند evalImage بعد swap سريع.
- **Remediation:** اجعل model+projector generation واحدة وحرر mmproj قبل model واربط auto-load بlock/request ID.

### F19 — [P1] مسار vision يسمح maxNewTokens غير محدود
- **Layer/category:** Native/Vision
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** LlamaManager.kt:2164-2166 يتحقق >0 فقط؛ LlamaBridge.cpp:2622 reserve(maxNewTokens*4) و:2625-2635 loop بلا clamp، بخلاف حد النص 1024 في :826-843.
- **Impact:** OOM/ANR أو overflow في reserve.
- **Remediation:** حد موحد موجب في Kotlin/C++ مشتق من n_ctx وheadroom مع رفض قبل native.

### F20 — [P1] المستخدم المحلي فقط يُعاد إلى Welcome
- **Layer/category:** Navigation/Local
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AiriApp.kt:217-245 hasAnyModel/hasAnyApiKey يفحص CloudProvider keys فقط؛ WelcomeScreen.kt:29-32 no-model screen ولا يوجد local model readiness gate.
- **Impact:** يُحجب local-only بعد restart أو auth.
- **Remediation:** أضف predicate محلياً persisted/non-blocking واختبر cold start مع zero cloud keys.

### F21 — [P1] فحص الوجهات الشبكية نصي ولا يمنع redirect/DNS/private destinations
- **Layer/category:** Network/SSRF
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** BrowserNavigationPolicy.kt:56-72 يفحص scheme/host دون DNS أو إعادة فحص redirect؛ SearchTool.kt:164-180 و280-298 يرسل URL؛ CustomSkillSecurity.kt:8-12 وCustomSkillExecutor.kt:42-47,155-163 وSkillRegistry.kt:383-388 تكتفي بـhttps/host؛ ZapierConnector.kt:232-243,271-306 يقبل hook_url عشوائياً.
- **Impact:** يمكن الوصول إلى localhost/metadata/RFC1918 أو إرسال payload/credentials إلى endpoint مهاجم.
- **Remediation:** امنع redirects أو أعد فحص كل hop، resolve IPv4/IPv6 وتحقق من private/link-local/ULA، واستخدم allowlists وegress policy.

### F22 — [P1] plan snapshot عالمي وقد يكشف حساباً آخر
- **Layer/category:** Plan/Account
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** PlanSnapshotStore.kt:9-23 يستخدم ملف/key ثابتين؛ AgentPlanViewModel.kt:75-83 يستعيد قبل auth؛ canRestore يتطلب nonblank فقط.
- **Impact:** تبديل الحساب يعرض goal/project/connectors للمستخدم الآخر.
- **Remediation:** namespace بالحساب وامسح عند logout/account change وتحقق ownership.

### F23 — [P1] R8 قد يفقد بيانات Gson عند الترقية
- **Layer/category:** R8/Persistence
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** proguard-rules.pro:57-60 يحفظ SerializedName فقط؛ ProjectFile بلا annotations في ProjectFileManager.kt:62-85 وrestore :660-685.
- **Impact:** تتلف workspace/index بعد release وتُسقط بصمت إلى empty.
- **Remediation:** @SerializedName/keepnames لكل persisted model مع schema migration وcross-release fixtures.

### F24 — [P1] Neutral text يُفرض RTL
- **Layer/category:** RTL
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** LanguageRuntimeManager.kt:38-43 maps NEUTRAL إلى Rtl؛ BidiAwareMarkdownRenderer.kt:42-60 يطبقها، بينما analyseDirection:19-33 لا يغطي zh/numeric/short Latin؛ locales_config يدعم ar/es/zh.
- **Impact:** الصينية/الإسبانية/الأرقام والكود تظهر بمحاذاة واتجاه خاطئ.
- **Remediation:** اجعل fallback حسب locale أو LTR، مع bidi per paragraph واختبارات.

### F25 — [P1] release-health غير موصول ومتعارض مع workflow
- **Layer/category:** Release Gate
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** airi_release_health.py:37-58 يطلب assembleRelease وbundleRelease؛ workflow :98-101 فيه assemble فقط، ولا يستدعي السكربت؛ تشغيله أعاد [FAIL].
- **Impact:** لا توجد بوابة تمنع drift أو غياب AAB.
- **Remediation:** شغّل الحارس قبل packaging ووحّد العقدة واجعل الفشل blocking.

### F26 — [P1] versionCode/versionName ثابتان
- **Layer/category:** Release versioning
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** app/build.gradle.kts:23-28 يثبت 1 و1.0؛ workflow يبني release على كل push main.
- **Impact:** Play يرفض التحديثات اللاحقة.
- **Remediation:** مصدر monotonic من tag/CI محمي وافحص عدم إعادة الاستخدام.

### F27 — [P1] CI لا يبني AAB ولا يرفع دليل الإصدار
- **Layer/category:** Release/AAB
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** android_build.yml:84-101 ينفذ assembleRelease فقط؛ :137-147 يرفع debug/release APK، رغم BUILD_AND_RELEASE.md:26-29,44-50 يعد AAB بوابة.
- **Impact:** مسار Play غير مختبر ولا ينتج AAB.
- **Remediation:** أضف bundleRelease وافحص AAB/ABI وارفع AAB/APK/mapping/hashes.

### F28 — [P1] استراتيجيات AdaptiveRetry لا تُنفذ وتعيد نفس العملية الجانبية
- **Layer/category:** Retry/Integrity
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AdaptiveRetryPolicy.kt:21-25,121-137 ينتج REPLAN/COMPENSATE/REDUCE_SCOPE مع backoffMs؛ ProductionAgentOrchestrator.kt:740-765 يتعامل فقط مع ABORT ويعيد executeTask بنفس المدخلات فوراً.
- **Impact:** قد تتكرر side effects ويتضاعف الضغط بينما السجل يعلن recovery لم يحدث.
- **Remediation:** نفّذ لكل strategy مساراً فعلياً، طبّق backoff/cancellation وidempotency keys، ولا تعاود side-effect غير القابل للإعادة.

### F29 — [P1] سباق إنشاء singleton لقاعدة Room
- **Layer/category:** Room/Lifecycle
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AiriDatabase.kt:224-229 يقرأ INSTANCE قبل synchronized ولا يعيد فحصها داخله.
- **Impact:** قد تُفتح قاعدتان لنفس الملف وتظهر locks/كتابات غير متسقة أو فقدان بيانات.
- **Remediation:** أعد الفحص داخل القفل واختبر التهيئة المتزامنة والإغلاق.

### F30 — [P1] لا توجد آلية عزل أو استرداد عند فساد Room/migration
- **Layer/category:** Room/Recovery
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** AiriDatabase.kt:224-239 يبني Room مباشرة بلا catch/quarantine/backup؛ MemoryManager.kt:55-57 يفتحها أثناء التهيئة.
- **Impact:** فساد SQLite قد يسبب crash startup ويمنع التصدير/المسح.
- **Remediation:** اعزل الملف التالف، حاول checkpoint/restore من backup ثم أنشئ DB جديدة برسالة واضحة، دون destructive fallback صامت.

### F31 — [P1] وجود cloud provider يجعل HYBRID Cloud-first
- **Layer/category:** Routing/Privacy
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatViewModel.kt:1858-1865 يمرر activeCloudProvider دائماً؛ AgentLoop.kt:592-613 يضع providerId في ExecutionRequest.
- **Impact:** ترسل المحادثات للخارج وتُهمل المحلي وتنعكس سياسة fallback.
- **Remediation:** مرر requestedProviderId فقط عند اختيار Cloud صريح واترك HYBRID للـrouter.

### F32 — [P1] هوية توقيع الإصدار لا تُفحَص
- **Layer/category:** Signing evidence
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** android_build.yml:91-101 لا يشغل apksigner/print-certs/fingerprint/mapping/hash رغم docs:44-56.
- **Impact:** قد يمر مفتاح خاطئ أو artifact غير قابل للربط بالمصدر.
- **Remediation:** تحقق certificate/mapping/SHA256 مقابل قيم محمية واربطها بالcommit.

### F33 — [P1] Malformed skill JSON يمسح مجموعة المهارات
- **Layer/category:** Skills/Data loss
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** CustomSkillRepository.kt:30-35 يعيد emptyList عند parse failure؛ save/delete في :12-24 يعيدان الكتابة بـapply بعد إضافة/حذف واحد.
- **Impact:** قيمة واحدة تالفة تؤدي إلى overwrite يفقد كل المهارات بصمت.
- **Remediation:** حوّل parse failure إلى repository error، امنع destructive writes واستخدم storage versioned/atomic مع backup.

### F34 — [P1] المعلمات المطلوبة لا تُتحقق قبل side effect
- **Layer/category:** Skills/Input
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** SkillToolBridge.kt:151-155 يأخذ أول قيمة أو empty؛ CustomSkillAiriSkillAdapter.kt:58-68 يقبل missing input؛ CustomSkillExecutor.kt:97-102 يرسل body.
- **Impact:** JSON ناقص قد ينفذ POST خارجي ببيانات فارغة أو خاطئة.
- **Remediation:** تحقق tool/required keys/types/bounds/enums من canonical manifest قبل authorization والتنفيذ.

### F35 — [P1] CustomSkillExecutor يبتلع CancellationException
- **Layer/category:** Skills/Lifecycle
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** CustomSkillExecutor.kt:147-163 يستخدم withTimeout؛ :245-252 يمسك كل Exception ويعيد SkillResult عادي، ولا يعيد رمي CancellationException.
- **Impact:** إلغاء المستخدم قد يستمر كشبكة/retry ويصدر completion stale.
- **Remediation:** أعد رمي CancellationException أولاً واستخدم bridge OkHttp قابلاً للإلغاء.

### F36 — [P1] Unknown-length HTTP responses تُخزّن كاملة قبل cap
- **Layer/category:** Skills/Resources
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** CustomSkillExecutor.kt:167-182 يفحص contentLength فقط إذا >MAX، يقبل -1 ثم body.string() ويقص بعد materialization.
- **Impact:** chunked response غير محدود قد يسبب OOM/ANR ويستنزف المسار المشترك.
- **Remediation:** اقرأ stream بحد bytes أثناء القراءة وأوقفه مبكراً مع cancellation.

### F37 — [P1] تواقيع المهارات المستوردة advisory فقط
- **Layer/category:** Supply chain
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** SkillPackageVerifier.kt:93-112 يحول checksum/signature الغائبة إلى warning؛ GitHubSkillImporter.kt:112-125 يقبل بلا errors وSkillRegistry.kt:287-303 يفعّل.
- **Impact:** manifest مستبدل قد يغير endpoint/capability ويضلل trust UI.
- **Remediation:** اشترط publisher signature/attestation واربطها بالـcanonical bytes وارفض missing/invalid integrity.

### F38 — [P1] JSON null يلوث ToolRegistry ويسبب crash لاحقاً
- **Layer/category:** Tools/Registry
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ToolScanner.kt:44-51 يضيف Gson result بلا null validation ثم يقرأ tool.name؛ ToolRegistry.kt:16-31 يقبل القائمة ويفك name.
- **Impact:** lookup لاحق قد يرمي NPE ويعطل routing.
- **Remediation:** ارفض null والـdefinitions غير الصالحة قبل النشر وأنشئ immutable validated list.

### F39 — [P1] احتمال خلط trace بين تنفيذين متعاقبين
- **Layer/category:** Trace
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** ExecutionStatusBus.kt:60-71 يبدأ status ثم traceBuffer؛ ExecutionTraceBuffer.kt:19-50 لا يتحقق active execution ويقبل eventId متأخراً.
- **Impact:** قد تنسب أداة/فشل A إلى B.
- **Remediation:** تحقق activeExecutionId داخل buffer أو Mutex مشترك وارفض event غير المطابق.

### F40 — [P1] Vosk لا يلغي final callback بعد Stop/ON_PAUSE
- **Layer/category:** Voice
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** VoskEngine.kt:145-175 يسلّم final callback بعد stopRequested؛ ChatScreen.kt:368-370,520-529 يوقف holder، callback :398-409 يغير input وقد autoSend.
- **Impact:** قد يعيد إرسال كلام بعد Stop أو إلى جلسة جديدة.
- **Remediation:** requestId/session token وأبطل callbacks عند stop/release/pause.

### F41 — [P1] تحميل STT غير المتزامن يعيد تشغيل الميكروفون بعد stop
- **Layer/category:** Voice/Lifecycle
- **Severity:** `P1`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatScreen.kt:372-390 يحمّل النموذج ويضع holder متأخراً؛ stop :368-370 لا يلغي loading، وON_PAUSE :520-529 لا يملك token.
- **Impact:** ميكروفون/بطارية عالقة وcallback إلى شاشة غير مرئية.
- **Remediation:** احتفظ بـJob/generation وألغها في stop/pause وافحص isActive قبل إنشاء engine.

### F42 — [P1] سباق start/release يثبت AudioRecord بعد تحريره
- **Layer/category:** Voice/Native
- **Severity:** `P1`
- **Confidence:** `likely`
- **Evidence (path/code):** VoskEngine.kt:76-130 يثبت sentinel ثم يطلق job خارج lock؛ release :166-175 يلغي sentinel ويحرر الموارد بين الخطوتين.
- **Impact:** استخدام recognizer/AudioRecord محرر قد يسبب crash أو إعادة فتح الميكروفون.
- **Remediation:** أنشئ job داخل lock أو تحقق generation قبل/بعد launch واجعل release ينتظر job الحقيقي.

### F43 — [P2] Activity feed controls صغيرة ودلالتها ناقصة
- **Layer/category:** Accessibility
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ActivityFeedComposable.kt:90-97 يستخدم Text.clickable بلا role/description/target؛ chips :137-145 Box.clickable مع 4dp وemoji.
- **Impact:** TalkBack لا يفهم الأفعال ومناطق اللمس دون 48dp.
- **Remediation:** استخدم IconButton/AssistChip semantics وminimumTouchTargetSize واختبارات Compose.

### F44 — [P2] execution surfaces تستهلك viewport الصغير
- **Layer/category:** Adaptive UI
- **Severity:** `P2`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** ChatScreen.kt:808-882 يكدّس plan/feed/input؛ AgentPlanOverlay.kt:183-189=260dp وActivityFeed :83-89=380dp بلا maxHeight/scroll policy.
- **Impact:** IME/font scale/landscape قد يخفي composer والمحادثة.
- **Remediation:** استخدم bounded collapsible sheet وscroll، واختبر compact/200%/IME/landscape.

### F45 — [P2] tool_call المشوّه يتحول إلى نجاح نصي
- **Layer/category:** Agent/Parsing
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AgentLoop.kt:236-285 يعيد parsing ثم يعامل toolCall=null كـFinalAnswer ويستدعي onGraphCompleted(true) مع LoopResult SUCCESS.
- **Impact:** قد يعلن نجاحاً دون تنفيذ الأداة أو يعرض JSON مشوهاً كإجابة.
- **Remediation:** بعد فشل parsing أعد FAILURE/NO_RESPONSE مع trace واضح ولا تنشر COMPLETED=true.

### F46 — [P2] pending accessibility confirmation واحدة لطلبات متوازية
- **Layer/category:** Approval
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** ChatViewModel.kt:1272-1307 يحتفظ بـDeferred واحدة ويكتب فوقها دون requestId/single-flight.
- **Impact:** تعلق الأولى أو ترتبط إجابة المستخدم بالطلب الخطأ.
- **Remediation:** ارفض الطلب الثاني أو map requestId→Deferred وألغِ عند onCleared/session switch.

### F47 — [P2] OpenXML extractor يفتح decompression bomb
- **Layer/category:** Archive
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AttachmentContentExtractor.kt:45-60 يقرأ entry كاملاً بـreadText ثم take؛ السياسة تحد المضغوط لا المفكوك/عدد entries.
- **Impact:** OOM/استنزاف وقت وبطارية رغم ادعاء bounded.
- **Remediation:** اقرأ بحد remaining bytes/chars وحدد total uncompressed/entries/ratio.

### F48 — [P2] المرفقات المرحلية تصبح orphaned بعد الفشل
- **Layer/category:** Attachments/Retention
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatViewModel.kt:3421-3509 ينسخ إلى filesDir/attachments ولا يحذف عند afterStaging/ownership failure؛ :3651-3667 يمسح المؤشرات فقط.
- **Impact:** تسريب تخزين طويل الأجل ونمو غير محدود لملفات حساسة.
- **Remediation:** تتبّع staged files واحذفها في كل error/cancel ثم انقل الملكية transactionياً بعد Room insert وأضف sweeper.

### F49 — [P2] exportBackup ليس snapshot ذرياً
- **Layer/category:** Backup/Integrity
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** AiriDatabase.kt:256-273 ينفذ WAL checkpoint ثم ينسخ الملف بلا transaction/read lock أو Online Backup API.
- **Impact:** قد يفقد آخر الكتابات أو ينتج snapshot غير متسق.
- **Remediation:** استخدم Online Backup أو snapshot ذري main+WAL واختبر تحت كتابات متزامنة.

### F50 — [P2] توقيع Release اختياري ولا توجد بوابة تمنع artifact غير الموقّع
- **Layer/category:** Build/Signing
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** app/build.gradle.kts:67-115 ينشئ signing config فقط عند وجود الأسرار ويطبقها اختيارياً؛ docs/AIRI_KNOWN_LIMITATIONS.md:7-10 يقبل البناء المحلي unsigned.
- **Impact:** قد يمر artifact Release بلا هوية توقيع؛ يرفضه Play أو يفقد ضمان الأصالة.
- **Remediation:** اجعل التوقيع إلزامياً في release/CI، وافشل مبكراً عند نقص الأسرار وتحقق من الشهادة والبصمة.

### F51 — [P2] لا توجد بوابة CI تبني أو تختبر Android/KMP
- **Layer/category:** CI/Build
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** الموجود .github/workflows/semgrep.yml:1-16 يشغّل Semgrep فقط بلا JDK/SDK أو Gradle test/build؛ لا يُشغّل core-domain أو app-desktop.
- **Impact:** يمكن دمج كسر compile/KSP/Room/JNI/ABI حتى مرحلة النشر.
- **Remediation:** أضف workflow مطلوباً لـtestDebugUnitTest وlintDebug وcore-domain:allTests وdesktop test وdebug/release verifier، واربطه بحماية الفرع.

### F52 — [P2] cloud API smoke يُتخطى بصمت بلا secrets
- **Layer/category:** CI/Cloud
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** android_build.yml:60-64 يطبع SKIP عند غياب OPENAI/GEMINI keys ويكمل؛ الأسرار اختيارية :16-19.
- **Impact:** provider/auth/streaming regressions تمر خضراء.
- **Remediation:** اجعل mock contract حتمياً وrelease environment محمياً mandatory أو not-ready.

### F53 — [P2] لا توجد بوابة coverage قابلة للقياس
- **Layer/category:** CI/Coverage
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** app/build.gradle.kts:183-191 لا JaCoCo/Kover/threshold؛ workflow يشغل unit/lint فقط؛ 665 production Kotlin مقابل 145 unit و8 instrumentation و0 coverage refs.
- **Impact:** قد تنخفض تغطية lifecycle/UI/native دون إنذار.
- **Remediation:** فعّل coverage reports وحدوداً للمكونات الحرجة واربطها بالـPR.

### F54 — [P2] مصفوفة Android لا تثبت ARM64/API حديثاً
- **Layer/category:** CI/Device
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** android_build.yml:119-135 يستخدم API29 وx86_64 ويفحص وجود أي .so فقط؛ verifier في app build يبحث arm64 دون instrumentation ARM64.
- **Impact:** JNI/ABI/API crashes الإنتاج لا تظهر.
- **Remediation:** أضف API حديثاً وARM64 واختبار native فعلي وفحص كل ABI.

### F55 — [P2] فشل Android لا يترك reports/logcat artifacts
- **Layer/category:** CI/Diagnostics
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** run-instrumentation.sh:44-59 يجمع diagnostics، لكن workflow :149-155 يرفع APK فقط وبـif success.
- **Impact:** يضيع دليل crash/ANR وسبب فشل البيئة.
- **Remediation:** ارفع JUnit/lint/logcat/adb diagnostics بـif:always وربطها بالrun/SHA.

### F56 — [P2] Oracle يكتشف الإخفاقات ولا يحجب الدمج
- **Layer/category:** CI/Oracle
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** oracly.py:97-161 يكتب isolated_files/fake_buttons/JNI دون sys.exit؛ oracle.yml:25-33 يرفع التقرير فقط.
- **Impact:** عيوب architecture/JNI تظهر كأخضر.
- **Remediation:** افشل عند findings أو valid=false مع upload if always.

### F57 — [P2] تبديل/إنشاء session لا يلغي generation الجاري
- **Layer/category:** Chat/Lifecycle
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatViewModel.kt:1477-1513 يغير session ويمسح الرسائل بلا cancel؛ sendMessageInternal يواصل استخدام session الملتقط في :2232-2251,2432-2448.
- **Impact:** تستمر أدوات/network وتكتب الرد القديم في جلسة مخفية.
- **Remediation:** invalidate generation واستدعِ cancel للـorchestrator/native وانتظر cleanup قبل تبديل session.

### F58 — [P2] Cloud Sync يسقط scope/privacy/retention/provenance
- **Layer/category:** Cloud Sync/Privacy
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** CloudSyncCoordinator.kt:390-411 يرسل id/role/content/emotion/session/timestamp/isMemory فقط ويعيد defaults لـprojectId/memoryScope/privacyLevel/expiresAt/provenance.
- **Impact:** قد تصبح ذاكرة project/user session دائمة وتسترجع أوسع من المقصود.
- **Remediation:** ثبّت schema version واحفظ metadata وطبّق ownership/scope/expiry أثناء pull مع migration آمن.

### F59 — [P2] ExecutionStatusBus singleton يطمس تنفيذين متزامنين
- **Layer/category:** Concurrency/Execution
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** ExecutionStatusBus.onGraphStarted:55-71 يستبدل status ويبدأ trace بلا رفض لتنفيذ نشط؛ AgentLoop.run يستدعيه لكل تشغيل، والـterminal القديم يُرفض لاحقاً بالهوية في :229-243.
- **Impact:** تشغيلان متزامنان يمسح أحدهما evidence الآخر وقد تعلق الواجهة على التنفيذ الخطأ.
- **Remediation:** افرض owner واحداً أو اجعل الحالة keyed by executionId، ولا تستخدم buffer singleton لكل executions.

### F60 — [P2] HTTP non-2xx في IFTTT/Zapier يعود Success
- **Layer/category:** Connectors/HTTP
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** IftttConnector.kt:217-237 يغلف Trigger failed في Success؛ ZapierConnector.kt:277-280,303-306 يفعل النمط نفسه.
- **Impact:** تظهر 401/404/offline كنجاح ولا يحدث fallback صحيح.
- **Remediation:** حوّل non-2xx إلى Failure مع status وتصنيف retryable المناسب.

### F61 — [P2] إعادة register تمسح حاجز disconnect
- **Layer/category:** Connectors/Lifecycle
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ConnectorRegistry.kt:81-97 يمسح explicitlyDisconnected ويصفر generation عند register بعد unregister.
- **Impact:** إعادة bootstrap قد تعيد الاتصال صامتاً.
- **Remediation:** احتفظ بقرار المستخدم في مصدر دائم ولا تمسح marker في register.

### F62 — [P2] signal handlers عامة وغير آمنة تغيّر العملية
- **Layer/category:** Crash handling
- **Severity:** `P2`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** JNI_OnLoad LlamaBridge.cpp:1026-1029 يثبت handlers؛ :294-310 logging ثم signal/raise بلا chain للمعالج السابق.
- **Impact:** قد يتعطل Crashlytics/NDK أو يحدث crash recursion وفقد tombstone.
- **Remediation:** لا تستبدل process-wide handlers أو chain بأمان واستخدم async-signal-safe marker فقط.

### F63 — [P2] CrashReportStore يحفظ message/stack الخام
- **Layer/category:** Crash privacy
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** CrashReportStore.kt:49-59,72-79,132-145 يpersist message وstackTraceToString دون redaction رغم عقد no PII.
- **Impact:** قد تتسرب paths/URIs/query/provider text إلى ملف وتقارير لاحقة.
- **Remediation:** خزن class/stable code فقط أو redact موحداً واختبر Bearer/path/content URI fixtures.

### F64 — [P2] نجاح wipe يسبق الكتابة المتينة
- **Layer/category:** Credential deletion
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** SecureStorage.kt:239-247 وDataDeletionCoordinator.kt:320-359 يستخدمان prefs.clear().apply() ويسجلان completion فوراً.
- **Impact:** قتل العملية قد يبقي credentials رغم GDPR_DELETE_SUCCESS.
- **Remediation:** استخدم commit/حذف ملف مع verify قبل Success واختبار process-kill.

### F65 — [P2] Accompanist مثبت وفق Compose قديم مع BOM أحدث
- **Layer/category:** Dependencies
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** gradle/libs.versions.toml:5 يحدد composeBom=2025.08.00؛ app/build.gradle.kts:194 يفرضه، بينما :281-283 يثبت accompanist-permissions 0.32.0 المعلّق بأنه متوافق مع BOM 2023.10.01/Compose 1.5.x.
- **Impact:** قد يحدث compile failure أو NoSuchMethodError أو خلل permission UI.
- **Remediation:** حدّث Accompanist أو ثبّت BOM المتوافق، وأضف constraints/locking واختبارات onboarding.

### F66 — [P2] نسخة desktop ثابتة 1.0.0
- **Layer/category:** Desktop Release
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** app-desktop/build.gradle.kts:26-31 يثبت packageVersion=1.0.0 بلا tag/CI.
- **Impact:** التحديث/التثبيت قد يعتبر الحزمة نفسها.
- **Remediation:** مرر version من tag/-Pversion وافحص monotonic MSI/DEB.

### F67 — [P2] التقطيع المعلن لا يقطع النص فعلياً
- **Layer/category:** Embedding
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** LlamaBridge.cpp:2333-2355 يخفّض n_probe فقط ثم يمرر النص الأصلي إلى tokenize buffer صغير ويعيد null عند overflow.
- **Impact:** النص الطويل يفقد semantic embedding ويعتمد fallback.
- **Remediation:** tokenize ثم احتفظ فعلياً بأول/آخر n_ctx tokens أو أعد typed refusal.

### F68 — [P2] reset لا يمسح trace أو lifecycle القديم
- **Layer/category:** Execution/Reset
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ExecutionStatusBus.kt:285-287 يعيّن _status=AgentState() فقط ولا يمسح traceBuffer أو _trace أو ExecutionToolTraceLifecycle.
- **Impact:** قد تعرض الواجهة trace حساساً قديماً أو تقبل callbacks بملكية قديمة.
- **Remediation:** اجعل reset ذرياً يمسح كل الحالة وأضف generation/epoch لاكتشاف callbacks المتأخرة.

### F69 — [P2] snapshot مستعاد يبقى ظاهراً إلى الأبد
- **Layer/category:** Execution/Restore
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AgentPlanViewModel.kt:75-83 يضع _restoredPlan؛ cleanup IDLE في :96-109 مشروط بـ!_restoredPlan؛ showPanel :54-58 يعرض خطوات restored.
- **Impact:** خطة مكتملة/مقطوعة تظهر كتنفيذ حالي بعد restart.
- **Remediation:** احفظ terminal/creation timestamps وexpire عند IDLE أو غياب runtime execution.

### F70 — [P2] MediaLibrary يقرأ الملفات بلا حد
- **Layer/category:** Filesystem
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** MediaLibrary.kt:319-327 يستخدم readBytes/readText؛ AttachmentPolicy يسمح 25MiB/512KiB لكن القراءة كاملة قبل التقليم.
- **Impact:** OOM/GC/ANR مع مرفقات كبيرة أو متزامنة.
- **Remediation:** stream bounded وارفض الحجم قبل التخصيص وسقف إجمالي للعملية.

### F71 — [P2] طلبات GitHub تسرّب موارد اتصال عند الاستثناء
- **Layer/category:** GitHub/HTTP
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** GitHubConnector.kt:308-310 يحرر connection بعد readText الناجح فقط؛ helpers :273-289 لديها finally جزئي.
- **Impact:** تتراكم streams/threads مع retries وتسبب بطئاً أو ANR.
- **Remediation:** استخدم response.use/try-finally واقرأ errorStream وصنّف status.

### F72 — [P2] IFTTT يعلن healthy دون health request
- **Layer/category:** IFTTT/Health
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** IftttConnector.kt:97-105 يضع connected/healthy من وجود webhook key؛ execute :133-165 لا يحدث state عند network failure.
- **Impact:** offline أو key منتهٍ يظهر سليماً حتى أول فشل.
- **Remediation:** سمّ الحالة configured أو نفذ probe محدوداً وحدّث unhealthy.

### F73 — [P2] خطأ auth/quota في LLM يرسل prompt لمزود آخر
- **Layer/category:** LLM Privacy
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** RemoteLlmConnector.kt:100-118 يجعل كل Throwable retryable؛ مزودو OpenAI/Anthropic/Gemini يحولون كل non-2xx إلى IOException؛ AgentRouter.kt:81-89 يمرر للمرشح التالي.
- **Impact:** قد تتسرب prompts أو يستهلك quota ويختفي السبب الحقيقي.
- **Remediation:** صنّف 401/403/4xx hard failure و429/5xx/network fallback وفق سياسة خصوصية.

### F74 — [P2] رسائل status/chat hard-coded خارج resources
- **Layer/category:** Localization
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatScreen.kt:264,452,861-864,1217-1219,1261-1264 و2271-2320 وWelcomeScreen.kt:62-65 تحتوي literals عربية/إنجليزية بلا stringResource.
- **Impact:** واجهة مختلطة اللغة وaccessibility announcements خاطئة.
- **Remediation:** انقل كل strings إلى resources ar/es/zh مع source check وlocale pass.

### F75 — [P2] BLOB embedding الفاسد يسبب crash
- **Layer/category:** Memory/Corruption
- **Severity:** `P2`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** EmbeddingService.kt:196-209 يستدعي bytesToFloatArray؛ :349-353 يقرأ dim floats دون فحص vector.size==dim*4.
- **Impact:** BufferUnderflow أو allocation غير متوقع يفشل memory recall/RAG.
- **Remediation:** تحقق dim والحجم والحد الأعلى، تجاهل/احذف الصف التالف وسجل metric.

### F76 — [P2] legacy one-shot لا يمسح cancel flag
- **Layer/category:** Native/Cancel
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** LlamaManager.kt:910-927 يستدعي generateResponse؛ LlamaBridge.cpp:1383-1396 وairi_append_text:704-719 يقرأ العلم؛ manager :1389-1404 يعيد String فارغاً عند exception.
- **Impact:** follow-up بعد cancel قد يفشل ويظهر نجاحاً فارغاً.
- **Remediation:** امسح العلم atomically قبل generation وأرسل typed error عند cancellation/native error.

### F77 — [P2] فحص RAM لا يمثل ميزانية native/low-memory
- **Layer/category:** Native/RAM
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** ModelValidator.kt:31-53 يقارن availMem ولا يفحص lowMemory/process budget؛ native يثبت n_ctx/batch في LlamaBridge.cpp:1103-1108 وقد يحمل embedding/projector :2256-2302.
- **Impact:** قد يمر model ثم يقتل Android العملية تحت ضغط.
- **Remediation:** احسب budget شامل واحجز هامشاً وأخلِ optional resources واختبر low-RAM/model swap.

### F78 — [P2] زر Unload لا يحرر model weights
- **Layer/category:** Native/Unload
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** LlamaManager.kt:624-682 يقر بعدم native unload؛ unload يحرر session/mmproj/embedding فقط، بينما native loadModel :1062-1063 يحرر القديم عند load تالٍ.
- **Impact:** ضغط ذاكرة مستمر ويخالف توقع المستخدم وقد يمنع نموذجاً آخر.
- **Remediation:** أضف JNI unload تحت LLAMA_LOCK يحرر mmproj/ctx/model بترتيب واضح وينتظر قبل UNLOAD_COMPLETE.

### F79 — [P2] filter_json غير صالح يتحول إلى query بلا filter
- **Layer/category:** Notion/Input
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** NotionMcpConnector.kt:266-278 يستخدم runCatching{JSONObject} ثم JSONObject() عند الخطأ.
- **Impact:** قد تتوسع القراءة إلى كل الصفوف.
- **Remediation:** أرجع bad_input عند malformed JSON وتحقق schema/size.

### F80 — [P2] OAuth callback يُستهلك قبل نجاح exchange ولا يملك recovery
- **Layer/category:** OAuth UX
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** MainActivity.kt:157-179 يستهلك OAuthStateRegistry قبل exchange ويكتفي بتسجيل failure؛ ZapierConnector.kt:155-183 لا يغلق response ولا retry آمن.
- **Impact:** فقد شبكة/process recreation يهدر code ويجبر OAuth جديداً.
- **Remediation:** احتفظ بحالة pending/continuation آمنة وأرسل success/failure UI وأغلق response.

### F81 — [P2] custom-scheme OAuth قابل للاختطاف وإسقاط callback
- **Layer/category:** OAuth boundary
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** Manifest.kt:111-122 يعلن airi://oauth/callback BROWSABLE autoVerify=false؛ MainActivity.kt:152-169 يستهلك state.
- **Impact:** تطبيق آخر قد يعترض redirect ويسبب DoS؛ PKCE الحالي يقلل سرقة token.
- **Remediation:** استخدم HTTPS App Link/assetlinks أو loopback، ألزم PKCE ولا تستهلك state قبل تحقق كامل.

### F82 — [P2] Google access token يستخدم حتى 401 بلا تجديد
- **Layer/category:** OAuth/Google
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** GoogleAuthService.kt:19-25,87-93 يحتفظ access token فقط؛ GoogleConnector.kt:245-270 يمسحه عند 401/403 ولا يحاول refresh.
- **Impact:** تفشل الخلفية وتُجبر إعادة التفويض.
- **Remediation:** أضف silent refresh/reauthorization محمياً من race وميّز user cancellation.

### F83 — [P2] Zapier يخزن refresh token ولا ينفذه
- **Layer/category:** OAuth/Zapier
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ZapierConnector.kt:159-169 يخزن access/refresh/expiry؛ :197-199 و226-227 يتحققان من isTokenValid فقط ولا يستدعيان ConnectorAuthManager.getRefreshToken.
- **Impact:** ينقطع التكامل بعد ساعة ويطلب OAuth مجدداً رغم refresh صالح.
- **Remediation:** نفذ refresh تحت mutex وحدث الزوج atomically وتعامل مع invalid_grant.

### F84 — [P2] release arm64 فقط رغم minSdk عام
- **Layer/category:** Packaging/ABI
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** app/build.gradle.kts:23-36 يضع minSdk=26 وabiFilters arm64 فقط؛ x86_64 debug فقط.
- **Impact:** أجهزة 32-bit لا تثبت release رغم minSdk.
- **Remediation:** وسّع ABIs أو وثق الحد وارفق فحص APK/AAB وتثبيت ممثل.

### F85 — [P2] أهداف التنفيذ محفوظة plaintext خلف denylist ناقصة
- **Layer/category:** Plan/Storage
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** AgentPlanViewModel.kt:133-147 يخزن goal/memoryQuery/details/IDs؛ PlanSnapshotStore SharedPreferences عادي؛ Policy.kt:9-16 يرفض substrings قليلة فقط.
- **Impact:** قد تحفظ PII/prompts حساسة على الجهاز.
- **Remediation:** لا تحفظ free-form أو شفّرها scope، خزّن opaque IDs/status typed redaction.

### F86 — [P2] ExecutionHistoryStore يحفظ input/error plaintext
- **Layer/category:** Privacy/Logs
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ExecutionHistoryStore.kt:27-29,43-80 يكتب أول 80 محرفاً وerror/reason إلى SharedPreferences عادي دون PrivacyGuard/SecureStorage.
- **Impact:** prompts/PII/secrets تبقى على القرص والنسخ الاحتياطية.
- **Remediation:** خزن tags/codes بعد redaction أو encrypted short-retention وأدرجه في eraseLocalData.

### F87 — [P2] release evidence لا يطابق الرأس محل التدقيق
- **Layer/category:** Provenance
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** main=587d6210 وcp-foundation=c29ea79f tree متطابق لكن docs تنسب runs إلى ca881a1b/8656fd12/91ceeb9c؛ git rev-list أعاد 13/14 commits غير مشتركة مع patch-equivalence.
- **Impact:** قد تنسب سلامة artifact تاريخي إلى كود حالي مختلف.
- **Remediation:** ولّد manifest من github.sha واربط artifact/run/mapping/hash به، ووسم الأدلة القديمة.

### F88 — [P2] تغيير embedding model بنفس البعد يخلط المتجهات
- **Layer/category:** RAG/Model
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** MessageEmbedding.kt:43-51 يخزن dim/vector بلا model fingerprint؛ EmbeddingDao.kt:27-28 يعيد القديم والجديد معاً.
- **Impact:** تشابه cosine غير صالح ويولد سياقاً غير ذي صلة.
- **Remediation:** خزن model/tokenizer fingerprint وقيد الاستعلام به وأعد الفهرسة atomically.

### F89 — [P2] expiry يرشّح الاسترجاع فقط ولا يطبق retention
- **Layer/category:** Retention
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** MemoryDao.kt:91-110 يفحص expiresAtMs في استعلام محكوم، لكن :27-28 وMemoryManager.kt:324-329 وChatViewModel.kt:1730-1733 تعرض الصفوف بلا expiry ولا يوجد reaper.
- **Impact:** تبقى بيانات منتهية وembeddings ونسخ احتياطية وتظهر في UI/export.
- **Remediation:** أضف reaper transactionياً يزيل expired rows/embeddings وطبّق السياسة على UI/export/backup.

### F90 — [P2] API/webhook tokens توضع في URL
- **Layer/category:** Secrets/HTTP
- **Severity:** `P2`
- **Confidence:** `likely`
- **Evidence (path/code):** GeminiProvider.kt:93-99 يضع key query؛ IFTTT :227-230 path؛ Telegram :166-168,219-223 URL.
- **Impact:** قد تسجلها proxies/logging/diagnostics.
- **Remediation:** استخدم headers/redaction وتجنب URL logging واختبر عدم التسريب.

### F91 — [P2] Keystore fallback غير مشفر لكنه يعلن connector usable
- **Layer/category:** Secure storage
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** SecureStorage.kt:53-76 يستخدم InMemorySharedPreferences عند فشل التشفير؛ ConnectorBootstrap.kt:82-92 لا يفحص isEncrypted.
- **Impact:** قد يفقد token بعد process death مع بقاء connected ظاهرياً.
- **Remediation:** ارفض persistence-dependent connectors أو اعرض حالة non-persistent صريحة.

### F92 — [P2] المهارات الكتابية لا تملك confirmation boundary
- **Layer/category:** Skills/Consent
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** SkillPermission.kt:25-35 يسمح WRITE لكل HTTP method وEXTERNAL_CALL عند online؛ SkillRegistry.kt:287-303 وCustomSkillExecutor.kt:81-95 لا يطلبان confirmation.
- **Impact:** LLM قد ينفذ POST/PUT/PATCH/DELETE بلا token approval.
- **Remediation:** اربط approval بالمهارة/endpoint/method/summary وأنفذه عند request boundary.

### F93 — [P2] Keystore/EncryptedPrefs تُهيأ على main أثناء start route
- **Layer/category:** Startup
- **Severity:** `P2`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** AiriApp.kt:225-235 ينشئ SecureApiKeyStore ويقرأ كل keys في composition؛ SecureStorage.kt:53-77 يبني MasterKey/EncryptedSharedPreferences بلا remember/IO.
- **Impact:** startup jank أو ANR على keystore بطيء/مقفل.
- **Remediation:** استخدم application-scoped StateFlow وIO/loading route وقياس cold start.

### F94 — [P2] ExecutionStatusBus يستخدم update غير ذري
- **Layer/category:** StateFlow
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ExecutionStatusBus.kt:81-88,101-107,198-206,231-240 يستدعي extension محلياً في :344-346 ينفذ value=transform لا MutableStateFlow.update.
- **Impact:** تحديثان متزامنان يفقد أحدهما currentAction/nodes/phase.
- **Remediation:** استخدم update الحقيقي أو Mutex/dispatcher وحيد واختبار interleaving.

### F95 — [P2] مخرجات terminal تعتمد على snapshot عالمي
- **Layer/category:** Terminal/Concurrency
- **Severity:** `P2`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** ToolDispatcher.kt:273-280 يحسب lines.size ثم يأخذ drop؛ TerminalRuntime.kt:63-68 يملك lines عالمية و:123-128 يمنع commandInFlight فقط.
- **Impact:** قد تختلط مخرجات طرف آخر أو تُفقد سطور، فتتخذ الأداة قراراً من evidence خاطئ.
- **Remediation:** أعد CommandResult مملوكاً لـcommandId/executionId أو sequence range ذري واختبر التزامن.

### F96 — [P2] Notion يحتفظ بالتوكن بعد disconnect/rotation
- **Layer/category:** Token cache
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** NotionMcpConnector.kt:67-82 يضع cachedToken TTL=5m و:116-119 teardown لا يمسحه؛ McpConnector.kt:57-63 لا يبطل cache.
- **Impact:** قد تستخدم طلبات لاحقة secret قديماً بعد revoke.
- **Remediation:** امسح cache في teardown/credential change واستخدم generation.

### F97 — [P2] ToolExecutor يطبق online requirement على local tools
- **Layer/category:** Tools/Offline
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ToolExecutor.kt:30-39 يفحص الشبكة قبل lookup رغم وجود Notification/Notes/Calendar محلية وToolCapabilitySchema.kt:26-60 per-tool requiresNetwork.
- **Impact:** المستخدم offline لا يستطيع أدوات الجهاز المحلية.
- **Remediation:** حل الأداة أولاً وطبّق network gate فقط عند requiresNetwork.

### F98 — [P2] Stop في LiveVoiceService قد يعيد تسليح STT عبر TTS callback
- **Layer/category:** Voice
- **Severity:** `P2`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** LiveVoiceService.kt:260-268 يوقف ويصفر flag؛ VoiceManager.kt:722-727 يوقف TTS لكن listener :278-285 يستدعي onSpeakingDone، وservice :384-389 requestListen.
- **Impact:** قد يعود microphone بعد زر Stop أو onDestroy.
- **Remediation:** استخدم turn generation/stopping flag وتجاهل callbacks الملغاة ولا requestListen إلا مع رغبة وحالة STARTED.

### F99 — [P2] VoiceManager داخل Compose يناقض LiveVoiceService
- **Layer/category:** Voice/Ownership
- **Severity:** `P2`
- **Confidence:** `confirmed`
- **Evidence (path/code):** ChatScreen.kt:485-505 ينشئ VoiceManager وdestroy في onDispose؛ LiveVoiceService.kt:33-50 يعلن ownership واستمرارية، ولا توجد start/bind service في الشجرة.
- **Impact:** rotation أو Activity recreation يوقف الجلسة ولا يتحقق survival المعلن.
- **Remediation:** اختر owner واحداً: service عبر binding أو ViewModel/service حقيقي واختبر rotation/process death.

### F100 — [P3] inventory الآلي لا يتحقق من graph Gradle الفعلي
- **Layer/category:** Architecture/Inventory
- **Severity:** `P3`
- **Confidence:** `confirmed`
- **Evidence (path/code):** scripts/airi_platform_dependency_scan.py:140-217 يعتمد regex للنص؛ scripts/airi_oracle.py:73-100 يطابق اسم الملف في import ولا يحل variants/transitives، ولا workflow يستدعي compile للتحقق.
- **Impact:** قد تصنف ملفات/حدوداً أو عزلاً خطأً وتخفي variant/JNI dependencies.
- **Remediation:** استهلك resolved dependency reports لكل configuration واستخدم AST/compiler symbols واجعل اختلاف التقرير عن build evidence فشلاً.

### F101 — [P3] إجبار CMake على Release يخلط Debug وRelease native artifacts
- **Layer/category:** Build/Native
- **Severity:** `P3`
- **Confidence:** `needs-runtime-proof`
- **Evidence (path/code):** app/build.gradle.kts:55-62 يضع -DCMAKE_BUILD_TYPE=Release داخل defaultConfig؛ CMakeLists.txt:265-272 يعتمد CONFIG:Debug لإضافة instrumentation.
- **Impact:** قد تختفي أدوات تشخيص Debug أو تختلف symbols/instrumentation، ما يصعّب root-cause.
- **Remediation:** اترك AGP يدير النوع أو مرّر قيمة variant-specific وتحقق من compile definitions/symbols.

### F102 — [P3] FileProvider يمنح نطاق cache عاماً
- **Layer/category:** FileProvider
- **Severity:** `P3`
- **Confidence:** `likely`
- **Evidence (path/code):** Manifest.kt:174-181 وfile_paths.xml:5-8 يعرّفان cache-path وexternal-cache-path path="." مع grantUriPermissions.
- **Impact:** خطأ caller قد يشارك secret/transcript من cache.
- **Remediation:** خصص subdirectory share مؤقتاً واختبر رفض URI خارج الجذر.

### F103 — [P3] RuntimeStore غير ذري ويفقد diagnostics
- **Layer/category:** Runtime state
- **Severity:** `P3`
- **Confidence:** `confirmed`
- **Evidence (path/code):** RuntimeState.kt:17-22 يستخدم state.value=state.value.transform؛ VoiceManager callbacks وChatViewModel يحدّثانه من مسارات متوازية.
- **Impact:** قياسات latency/voice/query type قد تصبح غير متسقة.
- **Remediation:** استخدم MutableStateFlow.update أو Mutex واختبر concurrent updates.

### F104 — [P3] Certificate pinning معطل دائماً
- **Layer/category:** Transport
- **Severity:** `P3`
- **Confidence:** `confirmed`
- **Evidence (path/code):** LlmCertPins.kt:29-36 يثبت PINNING_ENABLED=false؛ :94-104 لا يركب CertificatePinner.
- **Impact:** لا توجد حماية من CA compromise رغم تصميم الحماية المعلن.
- **Remediation:** فعّل pins مع backup/rotation أو أزل الادعاء وعرّف threat model/gate.

## خطة الإصلاح 30/60/90 يوماً

### 0–30 يوماً: إيقاف المخاطر الحرجة
- تعطيل/حجب `terminal_execute` غير المصرح، فرض allowlist وsession scope، وإغلاق SSRF عبر DNS/IP/redirect policy.
- إيقاف retries للـunknown side effects وإضافة idempotency keys؛ تصحيح CANCELLED، generation tokens، وsession deletion barriers.
- إصلاح Room double-check، Cloud Sync restore/cursor، malformed tool/skill validation، bounded HTTP/archive/image reads.
- جعل release pipeline يبني `assembleRelease` و`bundleRelease`، ويتحقق من signature/fingerprint/hash/mapping؛ تشغيل release-health كـrequired check.

### 31–60 يوماً: تثبيت الجودة والخصوصية
- توحيد MemoryRetrievalScope/retention/reaper/model fingerprint وbackup snapshot؛ تشفير/تقليص execution/crash history ومسح credentials بـcommit+verify.
- تنفيذ OAuth refresh/cancellation وnon-2xx mapping وconnector disconnect generations، مع custom-skill publisher verification وconfirmation.
- إصلاح local-only navigation، RTL neutral fallback، plan snapshot account scoping، localization/accessibility، وvoice ownership.
- إضافة native unload/cancel/vision bounds وRAM budget، وإطلاق ARM64/API حديث instrumentation.

### 61–90 يوماً: إثبات الإصدار وقابلية التشغيل
- بناء مصفوفة CI موحدة لـAndroid/KMP/Desktop وPR Windows، Oracle blocking، coverage thresholds، cloud contract tests، ورفع diagnostics بـif:always.
- إضافة process-death/rotation/Doze/Keystore/redirect/DNS/chunked/OOM/WAL/concurrency tests مع تقارير artifacts مرتبطة بـSHA.
- تشغيل release rehearsal على signed AAB/APK وdesktop packages، تحقق Play/upgrade عبر version monotonic وR8 round-trip، ثم مراجعة قبول المخاطر المتبقية.

## اختبارات تحقق مقترحة

- **Security/SSRF:** private IPv4/IPv6/link-local/metadata DNS، redirect إلى private، DNS rebinding، custom skill وZapier وSearchTool، مع إثبات عدم إرسال credentials/payload.
- **Capability:** tool غير معلن، dangerous schema، terminal ownership، malformed args/JSON null، confirmation token replay، وpublisher signature invalid/missing.
- **Memory/data:** جلستان متوازيتان، missing session/embedding fallback، expiry reaper، embedding corrupt/dimension/model fingerprint، delete أثناء streaming، Cloud Sync >100 rows وrestore، وbackup أثناء WAL writes.
- **Lifecycle/concurrency:** interleaving status/trace A/B، reset أثناء callback، cancel أثناء connector/native/voice، disconnect مقابل execute، Vosk start/release، TTS stop callback، وsingle-flight confirmation.
- **Release/build:** compile/lint/unit/common/desktop، AAB/APK لكل ABI، apksigner fingerprint، mapping/hash manifest، version monotonic، R8 persisted fixture، Oracle failure exit، Windows PR، وartifact upload عند failure.
- **Device/UI:** local-only cold start، RTL ar/es/zh neutral/mixed، account switch/process death plan snapshot، TalkBack semantics/48dp، IME/landscape/200% font، rotation voice، API حديث وARM64 native.
- **Resource robustness:** chunked unknown HTTP، Zip bomb، huge attachments/images/maxTokens، low-RAM model swap، native timeout cancellation، and heap/ANR profiling.

## مناطق فشل فيها التدقيق أو بقي إثباتها ناقصاً

- **Gradle/Android SDK:** تعذر حل dependency graph وتشغيل compile/lint/test/assemble فعلياً لغياب Android SDK و`local.properties` صالح؛ لذلك لا يوجد ادعاء بأن APK runtime بُني في هذه الجولة.
- **Runtime/device/native:** لم يُشغّل Android/ARM64/Desktop runtime أو emulator أو Accessibility/TTS/STT/Doze/process-kill؛ findings `likely` و`needs-runtime-proof` تحتاج إثباتاً تنفيذياً.
- **Providers/OAuth/cloud:** لم تُستخدم credentials حقيقية ولم تُختبر endpoints أو Firestore/backend أو DNS/redirect الشبكي؛ لم تُرفع نتائج backend غير قابلة للإعادة.
- **Coverage/visual:** لم تُنفذ visual RTL/font-scale/TalkBack ولا heap/ANR profiling ولا native instrumentation؛ الفحوص الثابتة لا تثبت الأداء أو semantics الفعلية.
- **Source changes:** لم يُعدّل أي source code. أي ملفات بيئية مولدة من Gradle لم تُستخدم كدليل finding.

## خلاصة قرار الإصدار

**التوصية: لا تعتمد AIRI للإصدار العام حالياً.** يلزم أولاً إغلاق P1، وإنتاج evidence موثق من CI للرأس الحالي، ثم تنفيذ اختبارات التحقق runtime للنتائج المصنفة `likely`/`needs-runtime-proof`. هذا الحكم مبني على التدقيق الساكن ولا يعني أن كل finding سيتحول حتماً إلى runtime failure، بل أن الضوابط الحالية لا تثبت الأمان/الاعتمادية المطلوبة للإصدار.
