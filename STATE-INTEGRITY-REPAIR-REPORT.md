# تقرير إصلاح سلامة الحالة والجاهزية — AIRI

**التاريخ:** 2026-09-26  
**الفرع المنفّذ عليه:** `cp-foundation`  
**المرجع المقارن:** `main` و`cp-foundation`

## الخلاصة التنفيذية

تم تنفيذ الجزء الثاني من الخطة فوق الإصلاح الأول، مع التركيز على الثغرات التي كانت لا تزال مصنفة `NEEDS RUNTIME TEST` أو `NEEDS FAULT-INJECTION` من ناحية **source semantics** و**state ownership**. لا أدعي نجاح Android runtime أو Compose أو provider tests ما دام Android SDK/emulator/credentials غير متاحين.

الإصلاحات الحالية تمنع طبقات الكود من عرض:

- `SUCCESS` عند فشل persistence أو connector initialization.
- `EMPTY` عند فشل تحميل الجلسة.
- `READY` قبل نجاح metadata persistence واستخراج الملف.
- state قديم بعد تحميل جلسة أحدث أو بعد disconnect صريح.

## A. Session

### القديم والمشكلة

كان `ChatViewModel.loadSession()` يحول فشل `memoryManager.loadSession()` إلى `emptyList()`، فيظهر فشل Room كأن الجلسة فارغة. كما لم يكن هناك UI يفرق بين welcome/empty وload failure.

### التغيير

أضيف `SessionLoadState`:

- `NotStarted`
- `Loading`
- `Ready(sessionId, messageCount)`
- `Empty`
- `Failed(reason, causeType, sessionId)`

وأضيف `LatestOperationGate`. كل load يبدأ token جديداً، وcompletion القديم لا يستطيع تغيير `_currentSessionId` أو `_messages` أو الحالة النهائية.

أضيف إلى `ChatScreen`:

- progress صريح أثناء `Loading`.
- رسالة فشل وإعادة محاولة للجلسة المطلوبة أثناء `Failed`.
- welcome state فقط عندما لا تكون العملية في loading/failure.

### الاختبارات

- `SessionLoadStateTest`: empty vs failed vs loading.
- older completion مرفوض بعد بدء load أحدث.
- success/failure ordering وcancellation ordering ممثلة بحارس generation.

**الحالة:** `FIXED` من ناحية الكود، و`VERIFIED` static/JVM source. Compose runtime يبقى `NEEDS RUNTIME TEST`.

## B. Initialization Readiness

### dependencies والملكية

- Connector registration وbootstrap يملكانهما `ConnectorRegistry` و`ConnectorBootstrap`.
- `ServiceLocator` ينشئ registry lazy؛ لذلك لا تُشغّل الخدمات غير اللازمة عند cold start.
- `ConnectorRuntimeManager` لا يملك registration lifecycle، بل يستهلك registry.
- credentials تملكها `ConnectorAuthManager` أو `SecureStorage` حسب connector الحالي.

### readiness states

أضيف `ConnectorReadinessState`:

- `NotInitialized`
- `Initializing`
- `Ready(registeredCount)`
- `Degraded(registeredCount, reason)`
- `Failed(reason)`

`ServiceLocator` يعلن `Ready` فقط بعد اكتمال `installDefaults`. إذا فشل bootstrap يسجل `Failed` ثم يعيد رمي الخطأ؛ لا يوجد silent partial success.

**الحالة:** `FIXED` و`VERIFIED` static. اختبار Keystore/partial initialization الحقيقي يبقى `NEEDS RUNTIME TEST`.

## C. Connect / Disconnect

### sequencing الحالي

#### Connect

1. التحقق من credential/provider.
2. حفظ credential في owner الصحيح.
3. استدعاء `ConnectorRegistry.connect(id)`.
4. التحقق من `connected && healthy`.
5. فقط عند النجاح تُغلق نافذة الاتصال وتنعكس الحالة في UI.
6. عند فشل runtime تتم إزالة credential الذي تم حفظه في هذه المحاولة، ولا يظهر connected.

هذا مطبق في GitHub وTelegram flows، وGoogle disconnect يمر عبر registry ثم يفصل خدمة Google.

#### Disconnect

1. `ConnectorRegistry.disconnect(id)` يضع connector في explicit-disconnected قبل استدعاء provider disconnect.
2. يزيد lifecycle generation.
3. يوقف قبول runtime operations الجديدة عبر `ConnectorRuntimeManager`.
4. ينفذ provider disconnect.
5. تزال credentials من owner المناسب.
6. يعاد بناء UI projection.

### stale completion protection

- `ConnectorRegistry` يملك `lifecycleGeneration` و`explicitlyDisconnected`.
- transitions محمية بـ`Mutex` فلا يتسابق Connect وDisconnect على generation.
- late operation بعد disconnect يفشل typed بـ`not_connected` حتى لو حاول connector الداخلي إرجاع state قديم.
- runtime operation لديه `operationId` وtyped terminal state.

### الاختبارات

`ConnectorRuntimeManagerTest` يتضمن:

- timeout لا يتحول إلى success.
- explicit disconnect يمنع execution.
- reconnect يعيد السماح بالتنفيذ.
- approval لا يسجل success بل `AwaitingApproval`.

**الحالة:** `FIXED` source semantics، `VERIFIED` static، وconnector transport disconnect الحقيقي يبقى `NEEDS RUNTIME TEST`.

## D. Import / Delete

### sequencing

Import الآن:

1. create `IMPORTING` metadata.
2. persist metadata؛ failure يعيد `ImportResult.Failed` فوراً.
3. `VALIDATING` ثم bounded staging/hash.
4. `HASHING` وduplicate check.
5. `STORING` عبر MediaLibrary.
6. `EXTRACTING` ثم `INDEXING` projection.
7. extraction failure يبقى failure ولا يصل `READY`.
8. metadata persistence في كل transition تستخدم `replaceOrThrow`؛ فشل الكتابة يذهب إلى failure path.
9. `READY` فقط بعد اكتمال الخطوات المطلوبة وحفظ metadata.

### ownership

- `ProjectFileManager`: lifecycle, files, metadata, trash/delete/restore.
- `ProjectKnowledgeManager`: knowledge chunks/index فقط.
- `MemoryManager/StorageRepository/SessionDao`: session row/messages transaction.
- connector credential owner: `ConnectorAuthManager` أو `SecureStorage` حسب adapter.

لا يوجد `DeleteManager` جديد ولا duplicate deletion owner.

### failure semantics

- MediaLibrary failure: لا `Imported`.
- parsing/extraction failure: `ImportResult.Failed` وlifecycle failure.
- metadata persistence failure: لا `Imported`؛ failure state best-effort persisted.
- duplicate: `Duplicate` وليس success جديد.

**الحالة:** `FIXED` source semantics، `VERIFIED` static. Filesystem/MediaLibrary fault injection الحقيقي يبقى `NEEDS RUNTIME TEST`.

## E. Pending Summary

### owner وpersistence

- الإنشاء: ChatViewModel summary worker بعد generation completion.
- الملكية المؤقتة: ChatViewModel عبر `_pendingSummary`.
- الملكية الدائمة: `MemoryStore.setSummary`.
- القبول: ChatScreen يستدعي `acceptSummary`، والحفظ لا يتم إلا للجلسة والtoken الحاليين.
- الرفض: ChatScreen يستدعي `rejectSummary` ويمسح pending.
- failure في الحفظ: pending لا يُمسح.

### out-of-order

كل summary request يأخذ `summaryToken` عند البدء، لا عند الاكتمال. لذلك:

- A starts
- B starts
- B completes → يمكنه النشر
- A completes لاحقاً → مرفوض لأن token أقدم

وكذلك لا ينشر summary إذا تغيرت الجلسة الحالية.

**الحالة:** `FIXED` source semantics، `VERIFIED` static. اختبار summarizer/Room حقيقي يبقى `NEEDS RUNTIME TEST`.

## F. M-09

### call sites

تم البحث في `app/src/main`, `app/src/test`, و`app/src/androidTest`. لا توجد call sites فعلية خارج `ChatSharingService` نفسه. الـAPI يحتوي:

- `exportAsText`: plain text.
- `exportAsJson`: JSON قابل لإعادة الاستخدام.
- `shareViaIntent`: Android share sheet.
- `publishShareLink`: Firestore cloud share.
- `fetchSharedChat` و`unpublishShareLink`.

### القرار

اخترت **A — التقييد/التصنيف** وليس الادعاء بوجود تشفير. التصدير المحلي الحالي ليس encrypted export ولا authenticity-verified backup. لذلك لا يُسمح بتقديمه كنسخة احتياطية سرية.

### الدليل

لا يوجد في implementation `Cipher`, authenticated tag verification, signature أو integrity envelope للتصدير المحلي. أما cloud sharing فهو مسار مختلف، حساس mode يمنعه، لكنه ليس بديلاً عن encrypted export.

**الحالة:** `FIXED` من ناحية منع الادعاء الخاطئ وتصنيف العقد الحالي، و`NEEDS FOLLOW-UP` فقط إذا كان المنتج يحتاج encrypted backup جديداً. لا يغلق هذا التقرير تشفيراً لم يُنفذ.

## G. Fault Injection

| المجال | السيناريو | إثبات متاح | الحالة |
|---|---|---|---|
| Session | DB read failure | `Failed` لا `Empty` + UI error state | VERIFIED static |
| Session | A/B completion | generation gate + tests | VERIFIED JVM/source |
| Initialization | bootstrap failure | registry `Failed` ثم throw | VERIFIED static |
| Connector | timeout | typed `TimedOut` + test | VERIFIED source/test definition |
| Connector | approval | `AwaitingApproval` لا success | VERIFIED source |
| Connector | disconnect then execute | `not_connected` + test | VERIFIED source/test definition |
| Connector | real HTTP disconnect | يحتاج Android/provider | NEEDS RUNTIME TEST |
| Import | extraction failure | لا `READY` | VERIFIED static |
| Import | metadata write failure | `replaceOrThrow` | VERIFIED static |
| Persistence | summary save failure | pending لا يمسح | VERIFIED static |
| Keystore | unavailable/read/delete | owner paths موجودة لكن لا runtime | NEEDS RUNTIME TEST |
| DB transaction | read/write/transaction | repository contract موجود، لا runtime | NEEDS RUNTIME TEST |

## H. Structured Telemetry

أضيف `RuntimeStateChanged(area, state, reasonTag)` إلى `PrivacyTelemetryReporter` مع consent gate وbounded sanitization.

المجالات التي يمكن تسجيلها دون payload:

- skip/failover reason.
- retrieval state.
- pending/failed jobs.
- cancellation.
- wipe completion.
- connector operation state.
- initialization readiness.
- persistence failure.

الحدود الإلزامية:

- لا prompts.
- لا raw model context.
- لا base64 أو attachment contents.
- لا PII أو API keys أو Authorization headers أو secrets.
- لا raw provider error bodies.

لا توجد runtime data كافية لإغلاق القياسات التالية: failover rate، retrieval unavailable، retry-after-cancel، orphaned embeddings، failed persistence، initialization failures، connector failures، stale completion events. حالتها تبقى `NEEDS MEASUREMENT`.

## I. Verification Gates

| Gate | النتيجة |
|---|---|
| `scripts/airi_core_health.py` | PASS |
| `tools/verify_core_changes.py` | PASS — 88/88 |
| `tools/security_scan.py` | PASS — لا secret findings |
| `git diff --check` | PASS |
| Gradle `testDebugUnitTest` | BLOCKED — SDK location not found (`ANDROID_HOME`/`local.properties` غير متاح) |
| Android build/lint | BLOCKED — لا Android SDK/adb في البيئة |
| Compose tests | NOT RUN — لا Android runtime/emulator |
| ViewModel/DB runtime tests | NOT RUN — لا Android runtime |
| Real-provider tests | NOT RUN — لا provider credentials/runtime |
| Telemetry validation | VERIFIED static sanitization/consent routing؛ runtime collection NEEDS MEASUREMENT |
| Regression scan | PASS — static checks السابقة والراهنة |

لا أعتبر `testDebugUnitTest` ناجحاً؛ Gradle وصل إلى مرحلة configuration ثم توقف بسبب غياب SDK.

## J. Remaining Risks

### FIXED

- empty-on-failure session load.
- missing session loading/failure UI distinction.
- stale session completion.
- connector operation error semantics.
- connect/disconnect ownership and explicit disconnect gate.
- credential/runtime sequencing for GitHub/Telegram/Google disconnect.
- failed import persistence and extraction readiness.
- pending summary ownership and request-order protection.
- M-09 false encryption claim.
- telemetry privacy boundary.

### VERIFIED

- static health gate.
- 88/88 core verification checks.
- security scan with no secret findings.
- diff hygiene.
- JVM test definitions for generation and connector lifecycle/timeout paths.

### NEEDS RUNTIME TEST

- Android Room failure injection.
- Keystore unavailable/read/delete failure.
- real HTTP/provider disconnect during operation.
- MediaLibrary/filesystem failure on import/delete/restore.
- Compose visual distinction: empty vs failed, loading vs ready.
- process death/recovery.

### NEEDS MEASUREMENT

- failover rate.
- retrieval unavailable rate.
- retry-after-cancel.
- orphaned embeddings.
- failed persistence rate.
- initialization failure rate.
- connector failure/timeout rate.
- stale completion event rate.

### BLOCKED

- Android unit/build/lint/instrumentation: SDK/adb unavailable.
- real providers: credentials/runtime unavailable.

## الملفات المعدلة

التنفيذ على `cp-foundation`، و`main` لم يُعدّل في هذه الجولة.


## K. الفحص الساكن الإضافي — Memory / Concurrency / Connector Lifecycle

أضيف السكربت:

`scripts/connector_lifecycle_static_scan.py`

وهو فحص deterministic مركز على دورة حياة الموصلات، لا يكتفي بفحص وجود كلمات عامة، بل يتحقق من العقود التالية:

1. `Mutex` يحمي Connect/Disconnect lifecycle transitions.
2. explicit disconnect gate يمنع execution بعد الفصل.
3. operation history bounded ولا تنمو بلا حد.
4. inflight action ينظف في `finally`.
5. timeout يتحول إلى `TimedOut` typed state.
6. `CancellationException` يعاد رميه ولا يتحول إلى success أو retry وهمي.
7. HealthMonitor يلغي `monitorJob` والـowned scope.
8. HealthMonitor قابل لإعادة التشغيل بعد `stop()`.
9. offline notice map bounded عملياً عبر إزالة connector IDs غير النشطة ومسحها عند stop.
10. لا يوجد `GlobalScope` في lifecycle paths.
11. `ConnectorsViewModel` يمرر lifecycle عبر `ConnectorRegistry`.
12. credential cleanup في IntegrationsViewModel يعمل داخل coroutine بعد disconnect.
13. لا توجد direct UI lifecycle bypasses.

### النتيجة

```text
summary: 13/13 checks passed
```

كما كشف الفحص وأُصلح الآتي:

- `ConnectorRuntimeManager.ensureHealthy()` كان يستدعي `connector.connect()` مباشرة؛ أصبح يمر عبر `registry.connect()`.
- `IntegrationsViewModel` كان يحدّث Google connector مباشرة؛ أصبح يمر عبر registry.
- `ZapierIftttScreen` كان يستدعي disconnect/connect مباشرة؛ أصبح يمر عبر registry.
- `ConnectorHealthMonitor.stop()` كان يلغي job فقط؛ أصبح يلغي scope المملوك ويمسح notice state، مع إعادة إنشاء scope عند restart.

### إعادة التحقق بعد الإصلاح

- lifecycle static scan: **PASS — 13/13**.
- `airi_core_health.py`: **PASS**.
- `verify_core_changes.py`: **PASS — 88/88**.
- `security_scan.py`: **PASS — لا secret findings**.
- `git diff --check`: **PASS**.

هذا فحص ساكن، ولا يثبت وحده غياب تسريب runtime أو race على جهاز Android. لذلك تبقى اختبارات HTTP disconnect الفعلي، provider runtime، وAndroid/Compose ضمن `NEEDS RUNTIME TEST`.


## L. محاكاة Runtime مصغّرة لـ Keystore وإدارة الجلسات

أضيف harness مستقل:

`scripts/runtime_state_simulation.py`

المحاكاة Android-SDK-independent، وتعيد تمثيل العقود الفعلية الموثقة في `SecureStorage` و`ChatViewModel` دون استخدام بيانات سرية أو provider حقيقي.

### Keystore scenarios

- Keystore سليم: الكتابة تذهب إلى encrypted-disk model.
- restart مع Keystore سليم: القيمة تبقى قابلة للقراءة.
- Keystore مكسور: `isEncrypted=false`.
- fallback write: لا توجد أي disk writes plaintext؛ القيمة process-local فقط.
- fallback restart: القيمة المؤقتة لا تبقى بعد process جديد.
- clear في fallback: آمن ولا يعيد credential.
- read fault: يظهر كفشل ولا يتحول إلى credential-present success.

### Session scenarios

- completion لجلسة أقدم لا يستطيع الكتابة فوق جلسة أحدث.
- empty session صالحة تبقى `EMPTY` وليست `FAILED`.
- DB read failure ينتج `FAILED` ولا ينتج `EMPTY` أو `READY`.
- failure يحتفظ بسبب الخطأ لإعادة المحاولة/UI.
- تم اختبار جميع ترتيبات completion الستة الممكنة للجلسات A/B/C؛ في كل ترتيب لا تقبل المحاكاة إلا completion الجلسة الأحدث C.

### النتيجة

```text
summary: 19/19 checks passed
```

### حدود الدليل

هذه نتيجة قوية لعقود state/error/order في harness مصغّر، لكنها ليست بديلاً عن:

- Android Keystore حقيقي على جهاز.
- EncryptedSharedPreferences/Tink initialization الفعلي.
- Room/SQLite fault injection فعلي.
- coroutine scheduling تحت Android runtime.
- process death أو device lock/unlock.

لذلك تبقى تلك البنود مصنفة `NEEDS RUNTIME TEST`، ولا تُعتبر PASS اعتماداً على المحاكاة وحدها.


## M. مراجعة Telemetry للـFailover وإدارة الجلسات

كشفت المراجعة أن `RuntimeStateChanged` كان موجوداً في النموذج و`PrivacyTelemetryReporter`، لكنه لم يكن مربوطاً فعلياً بمسارات failover أو session-load. كما أن `CloudBackend` كان يبني `providerQueue = listOf(primary)` عندما لا يحدد الطلب مزوداً صريحاً، وهو ما يجعل قياس failover متعدد المزودين غير قابل للوصول عملياً.

### التغييرات

أضيف `TelemetryTagPolicy` ب vocabulary مغلق. القيم غير المعروفة تتحول إلى `unknown` بدلاً من تمريرها عبر regex فقط. تم تقييد `area`, `state`, `reasonTag`، كما تم تقييد `deviceTier` و`executionMode` في `SessionBound`. كذلك أصبحت حقول crash telemetry تمر عبر sanitization قبل الـLogging والـAnalytics dispatch.

أصبح `CloudBackend` يبني failover queue حقيقية عندما لا يكون `requestedProviderId` محدداً، بينما يبقى الطلب ذو المزود الصريح مقيداً بذلك المزود ولا يخفي خطأ ownership بالانتقال إلى مزود آخر. تسجل المقاييس الآتية دون provider name أو model أو request payload:

| المجال | الحالة | السبب | المعنى |
|---|---|---|---|
| `cloud_failover` | `started` | `transient_failure` | بدأ الانتقال بعد فشل عابر |
| `cloud_failover` | `succeeded` | `provider_switch` | نجح fallback |
| `cloud_failover` | `exhausted` | `all_providers_failed` | انتهت السلسلة دون نجاح |

تم ربط هذه الأحداث بالـconsent-gated `PrivacyTelemetryReporter`.

تمت إضافة instrumentation لإدارة الجلسات دون `sessionId` أو message count أو content داخل الحدث:

| المجال | الحالة | السبب |
|---|---|---|
| `session_load` | `started` | `unknown` |
| `session_load` | `stale` | `completion_ignored` |
| `session_load` | `failed` | `db_error` |
| `session_load` | `empty` | `valid_empty` |
| `session_load` | `succeeded` | `success` |

### حدود الخصوصية

لا تحمل أحداث runtime operational أي prompt أو response أو message أو session ID أو email أو phone أو API key أو authorization header أو secret أو payload. الأحداث لا تصل إلى Analytics إلا بعد `agentTelemetryEnabled` consent؛ وعند عدم الموافقة يتم إسقاطها داخل coroutine قبل dispatch ولا تُخزن أو تُعاد محاولتها.

### فحص التغطية والخصوصية

أضيف:

`scripts/telemetry_privacy_scan.py`

والنتيجة:

```text
summary: 12/12 checks passed
```

وتحققت البوابات العامة أيضاً:

- `airi_core_health.py`: **PASS**.
- `verify_core_changes.py`: **88/88 PASS**.
- `security_scan.py`: **PASS — لا secret findings**.
- `git diff --check`: **PASS**.

### ملاحظة قياس مهمة

الآن توجد أحداث قابلة للعد لحساب:

- failover start rate.
- fallback success rate.
- exhausted-chain rate.
- session load failure rate.
- stale completion rate.
- valid-empty rate.
- session load success rate.

لكن لا توجد بيانات runtime فعلية في sandbox لحساب معدلات إنتاجية. لذلك تبقى الحسابات الفعلية `NEEDS MEASUREMENT` إلى أن يتم تشغيل التطبيق مع consent مفعّل وbackend telemetry مراقب، مع عدم اعتبار وجود instrumentation وحده دليلاً على معدل فعلي.
