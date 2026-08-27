# AIRI — مصفوفة جذور السبب المشتركة

> هذه المصفوفة مشتقة من تدقيق الرأس `b4ea8397` ومن جرد الحالة/التخزين/الشبكة والخدمات. كلمة «مرشح» تعني أن التتبع السطري أو runtime لم يكتمل بعد؛ لا تتحول إلى حقيقة إلا بعد اختبار يثبتها.

| Symptom | Root Cause | Shared Component | Affected Features | Fix Once | Problems Resolved |
|---|---|---|---|---|---|
| اختيار النموذج يتبدل أو الرد الثاني يفشل | لا يوجد عقد واحد يربط الاختيار المحفوظ بالمحادثة والطلب والـrouter في كل المسارات | `ModelSelectionState` + `ConversationState` + `RuntimeRouter` | 07، 08، 19، 27B | إنشاء `ConversationModelBinding` typed، يملكها ViewModel/Repository، مع fallback معلن وrequestId | يمنع random model، stale callback، mismatch، وتكلفة model خاطئة |
| draft أو attachment يختفي عند تبديل الشاشة/الجلسة | ملكية transient state موزعة بين Compose وViewModel ولا تشمل كل lifecycle transitions | `ChatComposerDraft` + `ChatViewModel` | 10، 11، 12، 14 | state machine واحدة keyed by persisted sessionId مع restoration/cancellation | يحفظ النص والمرفقات والـselection ويمنع عبور state بين الجلسات |
| greeting/credits/settings لا تتبع الحساب نفسه | هوية Firebase وprofile وpreferences وsession ليست عقد account واحداً | `SessionManager` + `UserProfileRepository` + account-scoped stores | 03، 05، 27A | `AccountContext` واحد مع ownership وswitch/logout/delete semantics | يصلح greeting، profile، credits، model preference، conversation ownership |
| نجاح أو فشل صامت في OAuth | مزود الهوية يعيد نتائج متعددة لكن UI يحتاج sealed outcome موحداً مع callback/state/session mapping | `AuthService` + `OAuthStateRegistry` + integration ViewModels | 02، 17، 22 | `AuthOutcome` موحد: success/cancel/provider/network/config/identity/token/session | يمنع silent failure وfalse connected state ويضبط revoke/cancel |
| شاشة تعرض ميزة كأنها متصلة وهي غير مهيأة | capability state مشتقة من وجود connector/route بدلاً من authorization وcredential وexecution evidence | `ReleaseScopePolicy` + `ConnectorRuntimeManager` | 06، 17، 21، 22، 23، 26 | capability contract بمراحل `NotConfigured/Authorized/Running/Failed/Revoked` | يمنع fake update، fake provider، routes الداخلية، وclaims غير المثبتة |
| planning panel يعرض خطوات لم تُنشأ | UI tracker كان يمكن أن يملأ placeholders بدلاً من event admitted صادر من execution | `ExecutionStatusBus` + `TaskExecutionTracker` | 09، 27B | executionId/actionId typed event log يخلق العنصر عند admission فقط | يمنع fake progress ويفصل running/fail/cancel/complete |
| الخصم لا يطابق الاستهلاك الفعلي | interaction credits وtoken usage لهما semantics مختلفة وحدود event غير موحدة | `CreditMeteringEngine` + `TokenAccountant` | 03، 07، 19 | request ledger idempotent يبدأ من accepted/inference ويعالج retry/cancel/fail | يمنع الخصم عند الرفض أو الفشل قبل inference والتكرار المزدوج |
| نصوص مترجمة لكن تجربة RTL/LTR غير مستقرة | فحص key parity لا يغطي hardcoded text وformat/plural/bidi ومرئيات الخط | resources + Compose typography/layout | 01، 05، 06، 14، 15، 26 | localization contract يربط كل UI text وformat locale ويضيف screenshot/accessibility checks | يمنع mixed language، truncation، direction errors، labels غير مترجمة |
| الثيم يغيّر الوضوح أو contrast | ألوان محلية متفرقة لا تمر عبر semantic token واحد | Material theme + component tokens | 06، 14، 16، 26 | semantic color/typography/elevation tokens مع light/dark/system/dynamic matrix | يمنع surfaces مخفية وحدوداً متعارضة ونصوصاً غير مقروءة |
| attachment يظهر اسمه لكن لا يصل إلى model context | pipeline لا يثبت كل عقد URI/permission/read/metadata/process/admission/persistence | `AttachmentDispatchPolicy` + ingestion repository | 10، 11، 12، 27B | `AttachmentLifecycle` typed ومملوك لجلسة مع bounded read وcontext admission | يمنع missing attachment، unsafe path، model mismatch وsilent drop |
| ميزات المحادثات تبدو موجودة لكن operations غير صالحة | history/session state وUI action eligibility لا يملكان نموذجاً صريحاً للحالات | `ChatSessionActionPolicy` + session repository | 04، 13، 27B | حالات No/Empty/Active/Archived/Deleted مع operations domain-level | يمنع share/pin/rename/export غير الصالحة وdelete races |
| صوت READY رغم عدم وجود نموذج/إذن | UI يخلط وجود service أو backend مع readiness طرفية حقيقية | `VoiceCapabilityPolicy` + `VoiceManager` + `VoskModelManager` | 06، 18، 20 | capability derived من permission/model/engine/lifecycle، والتنزيل صريح | يمنع auto-download وready claims وleaks عند background/stop |
| permission screen تعرض متطلبات غير لازمة | manifest/runtime/special permissions ليست مصنفة في عقد عرض واحد | `PermissionDisplayPolicy` + `PermissionService` | 01، 18، 20، 23 | capability-to-permission map مع required/optional/not-applicable وstate refresh | يمنع overclaim وطلبات مبكرة وعدّاد رفض مضلل |
| terminal/sandbox UI لا يثبت أمان التنفيذ | وجود manager أو شاشة لا يثبت isolation/process cleanup/approval/ownership | `SandboxManager` + `ToolPermissionPolicy` + workspace runtime | 06، 23، 25 | typed execution boundary مع approval، workspace owner، bounded filesystem وcleanup | يمنع إطلاق shell غير مملوك وتسريب output/path وfalse execution |
| scanner يعطي ثقة أكبر من دليله | حراس source قد يثبتون نمطاً لا نتيجة path/runtime، أو يفوتون negative cases | `security_scan.py` + release contracts | 23، 24، 25 | كل finding له positive/negative regression وprovenance وحالة blocked عند نقص البيئة | يقلل false confidence في secrets/manifest/deeplink/files/shell |
| secrets تظهر عبر metadata أو crash/analytics | wrappers متعددة قد تصل إلى provider/telemetry دون redaction contract شامل | `SecureStorage` + `FirebaseCrashReporter` + connector boundary | 02، 17، 22، 24، 25 | `SecretRedactionPolicy` وsecure-store failure closed، مع rotation/delete/account scope | يمنع logging/analytics/crash/UI/Git/CI leakage |
| update/about يعرض معلومات غير قابلة للمصدر | build metadata وrelease metadata وlegal links مختلطة أو بعضها ثابت | `UpdateAvailabilityPolicy` + build metadata + About repository | 21، 26 | source-backed metadata فقط، وNot Configured عند غياب catalog أو legal source | يمنع fake release state وclaims قانونية/متجر غير مثبتة |
| process recreation يعيد حالة ناقصة | بعض runtime managers تستخدم SharedPreferences أو in-memory state دون transaction/restore contract | lifecycle + repositories + WorkManager | 07، 09، 11، 18، 23، 27A | persistence boundary موحدة، restore ordering، idempotent workers، وcancellation propagation | يمنع فقد session/task/audio state والعمليات المكررة بعد restart |

## ترتيب المعالجة

الأولوية الأولى هي `RC-01` و`RC-02` و`RC-03` لأنها تؤثر في النموذج والحساب والمحادثة والوكيل معاً. بعدها تأتي `RC-04` لحجب claims والسطوح غير المهيأة، ثم عقود التوطين والثيم والأمان. لا ينبغي تنفيذ تحسينات شكلية لواجهة planning أو About قبل تثبيت event/state/capability ownership الذي يحدد محتواها.

## حدود المصفوفة

لا تمثل هذه المصفوفة دليلاً على provider أو جهاز أو متجر. كل علاقة تحتاج اختباراً مناسباً: unit/JVM للسياسات، integration للـrepositories/connectors، instrumentation لمكوّنات Android، UI لـCompose/navigation، وruntime على أجهزة ARM64 عندما يتطلب المسار ذلك.


## Evidence register — M2

| Root cause | Evidence status | Applied change | Verification |
|---|---|---|---|
| RC-01: split ownership بين session/model/request | **MITIGATED FOR M2** | `expectedSessionId` في مسار الإرسال، `requestedModelId` في ExecutionRequest، وsnapshot عبر AgentLoop | Android CI `33066904320`، Deep Audit `33066904321` |
| RC-03: فقدان state أو عبور attachment بين lifecycle transitions | **MITIGATED FOR M2** | metadata المرفق keyed by `pendingAttachmentSessionId` مع fail-closed ownership policy | Attachment policy regression + Android CI `33066904320` |
| RC-01: تنفيذ native على نموذج تغيّر بعد admission | **MITIGATED FOR LOCAL BACKEND** | LocalLlamaBackend يرفض streaming وbatch عند `model_changed` | Architecture Audit `33066904307` وAndroid CI `33066904320` |

هذه الحالة لا تعني أن كل طبقات المحادثة أو التنفيذ أُغلقت؛ cancellation، provider fallback، event ledger، وruntime device/provider gates تبقى ضمن M3–M9 حسب `AIRI_FIX_PLAN.md`. التقرير التفصيلي هو `AIRI_M2_CONVERSATION_CORE_REPORT.md`.


## Evidence register — M3 AI Execution

| Root cause | Evidence status | Applied change | Verification |
|---|---|---|---|
| RC-01: stale execution events attach to active plan | **MITIGATED FOR GRAPH EVENTS** | `ExecutionStatusBus` requires explicit nonblank matching executionId; `AdaptiveGraphEngine` owns one id per graph | `ExecutionStatusBusTest`, Android CI `33070581767` |
| RC-03: graph lifecycle loses ownership at terminal state | **MITIGATED FOR AdaptiveGraphEngine** | graph admission and completion/cancel/failure events carry the same executionId | Architecture Audit `33070581824`, Android CI `33070581767` |
| M3-open: network cancellation may still rely on adapter cooperation | **OPEN / NEXT AUDIT** | Requires cancellation and retry regression across HybridOrchestrator/CloudBackend | No DONE claim; scheduled for next M3 iteration |
| M3-open: terminal event idempotency across fallback | **OPEN / NEXT AUDIT** | Requires explicit request/execution ledger semantics | No DONE claim; scheduled for next M3 iteration |

التقرير التفصيلي: `AIRI_M3_AI_EXECUTION_REPORT.md`.


## Evidence register — M3 cancellation

| Root cause | Evidence status | Applied change | Verification |
|---|---|---|---|
| Cloud retry/failover continues after user cancellation | **MITIGATED IN CloudBackend** | Atomic `cancelRequested`, non-blocking `cancelStream()`, retry and provider-loop cancellation guards | Android CI `33073079681`, Deep Audit `33073079638` |
| Runtime backend cancellation contract is no-op by default | **OPEN BY DESIGN** | Cloud and local backends now implement their concrete propagation paths; future custom backends must implement `cancelStream()` | Architecture Audit `33073079670`; provider-specific runtime remains external |
| Exactly-once terminal event across all adapters | **OPEN / NEXT AUDIT** | Requires provider adapter integration tests and event ledger assertions | No DONE claim |


## Evidence register — M3 terminal delivery

| Root cause | Evidence status | Applied change | Verification |
|---|---|---|---|
| Adapter callback may deliver completion more than once | **MITIGATED IN HybridOrchestrator** | `completionDelivered` guard accepts only the first completion for a generation | Android CI `33075669176`, Deep Audit `33075669201` |
| Terminal delivery across every provider implementation | **PARTIALLY OPEN** | Orchestrator guard is closed; provider-specific integration coverage remains required for live adapters | No live-provider DONE claim |
