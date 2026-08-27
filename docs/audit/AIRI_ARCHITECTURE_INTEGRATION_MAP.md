# AIRI — خريطة المعمارية والتكامل

> **نقطة الخريطة:** `b4ea8397` على `main` و`cp-foundation`. هذه خريطة تدقيق وليست ادعاءً بأن كل سهم يعمل runtime. كل مزود خارجي أو جهاز أو متجر يبقى بوابة منفصلة.

## الخريطة العامة

```text
AccountContext
 ├── AuthService / SessionManager
 ├── UserProfileRepository
 ├── Account-scoped Preferences
 ├── CreditMeteringEngine / TokenAccountant
 └── PermissionService / Consent

ConversationState(sessionId)
 ├── Messages → Room/session repository
 ├── ChatComposerDraft → ViewModel + restoration boundary
 ├── Attachments → bounded ingestion + provenance
 ├── Selected Model → persisted binding
 ├── Task/Execution → Agent status bus
 └── Usage → request ledger / token accountant

ModelSelection
 ├── LocalModelManager / llama.cpp JNI
 ├── RemoteModelRegistry
 ├── RuntimeRouter
 └── Provider Connector

AgentExecution
 ├── Intent admission
 ├── Planner / admitted actions
 ├── ToolPermissionPolicy / approvals
 ├── ExecutionStatusBus(executionId)
 ├── Result / failure / cancellation
 └── Next-step proposal only after real completion

Integrations
 ├── GoogleAuthService → GoogleConnector → OAuth/credential boundary
 ├── GitHub OAuth → AuthService (no client secret in APK)
 ├── ZapierConnector → disabled release capability
 └── IftttConnector → disabled release capability

ReleaseBoundary
 ├── ReleaseScopePolicy
 ├── Security scanner / source contracts
 ├── R8 / native verification / signing
 ├── Consent and redaction
 └── Runtime, provider, Play, legal gates
```

## سجل الملكية والعقود

| المجال | المالك الحالي | مصدر الحقيقة المقصود | API/الحد الفاصل | Persistence | Lifecycle | Security boundary | Coverage الحالي |
|---|---|---|---|---|---|---|---|
| Account/Auth | `AuthService` و`SessionManager` | Firebase user + session abstraction | Firebase Auth/OAuth callback | Firebase session، local session metadata | sign-in/restore/logout/delete | callback validation، secure storage، no raw token logs | سياسات وCI؛ provider/device pending |
| Profile | `UserProfileRepository` | profile record المرتبط بـaccount | profile read/write API محلي/بعيد حسب feature | local repository | load/update/switch account | account ownership، redacted identity | tests جزئية؛ UI/device pending |
| Preferences | preference/profile stores | account-scoped preference contract | ViewModel → repository | DataStore/SharedPreferences حسب المجال | restore/update/reset | no secrets in ordinary prefs | source inventory؛ توحيد مطلوب |
| Permissions | `PermissionService` + `PermissionDisplayPolicy` | Android PackageManager/runtime state | OS permission APIs | Android system state | request/deny/settings return/refresh | least privilege، optional vs required | JVM policy + CI؛ device pending |
| Conversations | `ChatViewModel` + session repository | persisted session/message identity | Room/repository boundary | Room | create/load/switch/delete/restart | session ownership، failed-delete recovery | targeted policy/CI؛ UI/device pending |
| Draft/Input | `ChatComposerDraft` | sessionId-owned transient state | ViewModel reducer | restoration/local transient store | model/session/voice/lifecycle changes | no cross-session leakage | unit/CI؛ UI lifecycle pending |
| Attachments | `AttachmentDispatchPolicy` + ingestion path | attachment provenance + session | ContentResolver/file validation/context admission | app files/Room metadata | staged/processed/attached/failed/cancelled | bounded reads، path/provider checks | security regression + CI؛ device pending |
| Model selection | `ModelController` + remote/local selection policy | persisted conversation model binding | RuntimeRouter/connector interface | model prefs/session binding | loading/stale callback/switch | no random fallback؛ explicit policy | unit/CI؛ multi-request runtime pending |
| Model execution | `RuntimeRouter` + connector adapters | accepted request/result state | local JNI or remote HTTP adapter | response/message persistence | stream/cancel/retry/timeout | provider credential and network policy | source/CI؛ provider runtime pending |
| Usage | `CreditMeteringEngine` + `TokenAccountant` | requestId event ledger (target) | metering at accepted/inference/complete | local usage store | idempotent retry/fail/cancel | no secret/provider payload in meter | targeted tests/CI؛ full provider pending |
| Agent plan | `ExecutionStatusBus` + `TaskExecutionTracker` | admitted runtime action events | executionId/actionId event contract | task/execution records | start/action/result/fail/cancel/complete | no chain-of-thought; approval policy | unit/CI؛ device/long-run pending |
| Skills | `SkillRegistry` + invocation access policy | registry + capability contract | agent tool invocation boundary | preferences/audit store | enable/disable/version/outcome | ownership and approval | source/policy tests؛ skill integration pending |
| Memory/Knowledge | `MemoryManager`، `ProjectKnowledgeManager`، RAG policies | explicit admission policy (target) | query/rank/store boundaries | Room/JSON/preferences in separate components | insert/rank/prune/delete | user/project ownership، bounded context | source inventory؛ retention/quality audit pending |
| Scheduled tasks | `ScheduledJobOrchestrator` + `ScheduledAgentWorker` | reserved job input + execution record | WorkManager boundary | SharedPreferences/Room بحسب runtime | enqueue/run/retry/cancel/reboot | fail closed for maintenance and agent fallback | policy/CI؛ Doze/OEM pending |
| Google integration | `GoogleAuthService` + `GoogleConnector` | authorized account/token state | Firebase/Google OAuth/API | secure storage and integration state | connect/refresh/revoke/error | OAuth state، redaction، consent | outcome policy/CI؛ live account pending |
| GitHub integration | `AuthService` OAuth path + connector | provider identity/token | Firebase OAuth or approved backend path | secure storage | connect/callback/revoke | no client secret in APK | source audit؛ live provider pending |
| Zapier/IFTTT | connectors behind `ReleaseScopePolicy` | disabled capability in current release | connector methods fail closed | no credential initialization in disabled path | not configured | no OAuth/webhook/network in release | CI/source verified؛ intentionally disabled |
| Terminal/Workspace/Sandbox | internal managers and policies | owned workspace/task/artifact | process/filesystem boundary | Room/SharedPreferences/files | start/stop/cleanup/recover | internal release gate، approval، path bounds | source/CI partial؛ runtime pending |
| Secrets | `SecureStorage` + vault wrappers | encrypted secret store | connector credential boundary | EncryptedSharedPreferences/secure store | create/replace/rotate/delete/logout | no logs/analytics/crash/UI/Git/CI leakage | security scan + unit؛ device/provider pending |
| Telemetry/Crash | `FirebaseCrashReporter` + consent store | explicit user consent | Firebase SDK wrapper | local consent preference | grant/revoke/restart | collection disabled by default، redaction | source/CI؛ Firebase DebugView pending |
| Updates/About | `UpdateAvailabilityPolicy` + build metadata | installed version/build only until catalog exists | no provider in current scope | build constants/resources | display only | no fake availability/install claims | policy/CI؛ store/legal pending |

## تدفق ownership المطلوب

كل request يجب أن يحمل `accountId/sessionId/requestId` حيث يلزم، وكل انتقال حالة يجب أن يمر عبر مالك واحد قابل للاختبار. لا يجوز لـComposable أن يستدعي provider مباشرة، ولا للوكيل أن يتجاوز approval/capability boundary، ولا لموفر خارجي أن يُعلن متصلاً قبل credential + authorization + successful state.

## أخطر نقاط الفصل الحالية

تعدد SharedPreferences الظاهر في الجرد لا يعني تلقائياً عطلاً، لكنه نقطة خطر لمصادر الحقيقة والـmigration/account scoping. كما أن وجود services متعددة للصوت والوكيل لا يثبت lifecycle ownership. ستُفحص هذه النقاط في مراحل الإصلاح قبل إعادة تصميم واسع، مع الحفاظ على الفروع والبنية الحالية.
