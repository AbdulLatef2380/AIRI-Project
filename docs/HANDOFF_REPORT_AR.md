# تقرير تسليم مشروع AIRI إلى جلسة Manus أخرى

**المستودع:** `https://github.com/AbdulLatef2380/AIRI-Project.git`  
**الفرع المستهدف:** `main`  
**الفرع المرجعي:** `cp-foundation`  
**الفرع المحلي عند التسليم:** `main`  
**آخر commit قبل هذه الدفعة:** `1c714ba1 Fix connector lifecycle static guard for runtime IDs`

> هذا التقرير مكتوب لكي تبدأ جلسة جديدة من دون الاعتماد على ذاكرة المحادثة الحالية. يجب قراءة هذا الملف أولاً، ثم تشغيل أوامر التحقق المذكورة في نهايته.

---

## 1. هدف المهمة الأصلية

المشكلة التي بدأ منها العمل كانت أن نموذج AIRI، سواء كان سحابياً أو محلياً، لا يصل بصورة موحدة إلى وظائف النظام، ومنها:

- Skills.
- Connectors.
- Memory.
- Durable Tasks.
- Schedules.
- Internet/search.
- الوقت والتاريخ.
- قدرات Android المحلية.
- أدوات القراءة والكتابة.

كان التطبيق يبدو مكتملاً من حيث الوحدات، لكن مسار المحادثة كان يسقط الأدوات أو يمنعها حسب نوع السؤال قبل أن تصل إلى النموذج. لذلك كان الهدف إصلاح **مسار الوصول الجذري** لا إضافة حالات خاصة لكل سؤال.

المبدأ الذي اعتمدناه:

```text
User request
  -> capability intent detection
  -> runtime tool catalog
  -> permission policy
  -> local/cloud/hybrid backend
  -> model tool calling
  -> ToolDispatcher
  -> builtins / skills / connectors / memory / tasks
  -> typed result
  -> user-facing localized error or result
```

وبالنسبة للموصلات:

```text
ConnectorDefinition
  -> AuthStrategy
  -> AuthorizationManager
  -> provider authorization
  -> state / PKCE validation
  -> encrypted credential storage
  -> health check
  -> connected && healthy
  -> tool exposure
  -> real execution
```

---

## 2. حالة الفروع عند بداية العمل

كان `main` و`cp-foundation` متطابقين عند نقطة العمل الأساسية، وكان الفرع المحلي مثبتاً على `main`.

آخر commit الأساسي قبل التغييرات المحلية:

```text
1c714ba1 Fix connector lifecycle static guard for runtime IDs
```

الأوامر للتحقق من الوضع بعد الاستنساخ:

```bash
git fetch origin
git switch main
git pull --ff-only origin main
git show-ref --heads --remotes | grep -E 'main|cp-foundation'
```

---

## 3. التشخيص الجذري الذي تم إنجازه

تم اكتشاف أن المشكلة لم تكن غياب الوحدات فقط، بل وجود عدة بوابات تقطع المسار:

### 3.1 بوابة QueryClassifier

الأسئلة القصيرة مثل:

- كم الوقت؟
- ما التاريخ اليوم؟
- اقرأ رسائل Gmail.
- ابحث في الإنترنت.

كانت تعامل أحياناً كسؤال بسيط أو دردشة مباشرة، فتسقط الأدوات قبل وصول الطلب إلى AgentLoop.

### 3.2 غياب قرار موحد للقدرات

تم إنشاء `CapabilityIntentDetector` ليكشف نية استخدام:

- الوقت.
- الذاكرة.
- البحث والإنترنت.
- الموصلات.
- البريد والتقويم.
- المهام والجدولة.

### 3.3 تجميع الأدوات كان موزعاً

كانت builtins وskills وconnectors تضاف عبر مسارات منفصلة. تم إنشاء:

- `RuntimeToolContract`.
- `RuntimeToolCatalog`.

ليصبح التجميع عند حد المحادثة قبل تمرير الأدوات إلى النموذج.

### 3.4 نتائج الأدوات كانت نصية وغير typed

تم إنشاء `ToolResultContract` مع:

- error code.
- source/provenance.
- retryable.
- error normalization.

ثم تم تمرير الكود إلى AgentLoop وواجهة المستخدم.

### 3.5 الأخطاء كانت غير مفهومة للمستخدم

تم إنشاء `ToolErrorPresentationPolicy` وموارد لغوية لرسائل مثل:

- يجب ربط Google أولاً.
- الاتصال بالإنترنت غير متاح.
- الذاكرة غير جاهزة.
- الموصل غير مصادق.
- authorization منتهي.

الموارد تم تحديثها باللغات:

- English.
- Arabic.
- Spanish.
- Chinese.

### 3.6 retry غير آمن

تم جعل الإصلاح الذاتي يحترم `retryable`، ولا يعيد الأدوات ذات الفشل النهائي أو الأثر الجانبي بلا شروط.

---

## 4. ما تم إنجازه في runtime والوصول إلى الأدوات

الملفات المحورية:

- `app/src/main/java/com/airi/assistant/ai/CapabilityIntentDetector.kt`
- `app/src/main/java/com/airi/assistant/agent/loop/tool/RuntimeToolContract.kt`
- `app/src/main/java/com/airi/assistant/agent/loop/tool/RuntimeToolCatalog.kt`
- `app/src/main/java/com/airi/assistant/agent/loop/tool/ToolResultContract.kt`
- `app/src/main/java/com/airi/assistant/agent/loop/ToolDispatcher.kt`
- `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`
- `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`

النتيجة المعمارية:

- الطلب الذي يحتاج قدرة حية لا يفقد tools بسبب قصر النص.
- النموذج المحلي والسحابي يريان سياق الأدوات عند الحاجة.
- hybrid failover يحافظ على tool context.
- builtins وskills وconnectors تمر عبر مسار موحد.
- الأخطاء تعود typed ثم تعرض محلياً بصورة مفهومة.

---

## 5. ما تم إنجازه في الموصلات العامة

تم إنشاء وتسجيل:

- `ConnectorAuthStrategy`.
- `ConnectorAuthorizationManager`.
- `ConnectorRuntimeDescriptor`.
- `ConnectorRolloutRegistry`.
- تحسين `ConnectorRegistry` لمنع تكرار runtime adapters في الكتالوج.

المسار الموحد يدعم استراتيجيات:

- OAuth2 + PKCE.
- API key.
- Personal access token.
- Device code.
- MCP configuration.
- Webhook.
- Android local permission.
- No authentication.
- Coming soon / blocked.

العقد الأمني:

```text
Authorization
  -> secure storage
  -> health check
  -> connected && healthy
  -> tool exposure
```

لا يتم إعلان الموصل Connected بمجرد ضغط زر الاتصال.

---

## 6. الموصلات الرئيسية السبعة

المقصود بالسبعة في الخطة الأصلية هو:

1. Google Gmail.
2. Google Calendar.
3. Google Drive.
4. GitHub.
5. Telegram.
6. Notion.
7. Zapier.

تم إنشاء:

`app/src/main/java/com/airi/assistant/connector/PrimaryConnectorContract.kt`

واختباره في:

`app/src/test/java/com/airi/assistant/connector/PrimaryConnectorContractTest.kt`

المعروف لكل سطح:

| Catalog ID | Runtime ID | Auth |
|---|---|---|
| `google_gmail` | `google` | Google OAuth/PKCE + data authorization |
| `google_calendar` | `google` | Google OAuth/PKCE + data authorization |
| `google_drive` | `google` | Google OAuth/PKCE + data authorization |
| `github` | `github` | PAT آمن |
| `telegram` | `telegram` | Bot token آمن |
| `notion` | `notion_mcp` | MCP configuration/token |
| `zapier` | `zapier` | OAuth2 + PKCE |

### إصلاح Notion/MCP

كان Notion يملك transport وhandshake، لكنه لا يكشف الأدوات عبر `ConnectorToolBridge`.

تم تعديل:

`app/src/main/java/com/airi/assistant/connector/mcp/McpConnector.kt`

بحيث:

- كل MCP tool يظهر binding مستقلاً.
- اسم الـtool النهائي فريد في bridge.
- dispatch الداخلي يستخدم `invoke_tool`.
- لا تظهر الأدوات قبل نجاح handshake.

### إصلاح Zapier

تمت إضافة `agentActions()` إلى:

`app/src/main/java/com/airi/assistant/connector/app/ZapierConnector.kt`

وتوحيد أسماء dispatch:

```text
list_zaps
list_triggers
trigger_zap
pause_zap
resume_zap
status
```

### Microsoft

تم تنفيذ Microsoft Outlook/Calendar كـvertical slice إضافي مستقل، وليس جزءاً من عدّاد السبعة الأصلي.

الملفات:

- `MicrosoftGraphConnector.kt`
- `MicrosoftGraphTokenService.kt`
- `OAuthConfiguration.kt`

يدعم:

- OAuth Authorization Code.
- PKCE S256.
- state one-time validation.
- access/refresh token lifecycle.
- encrypted storage.
- refresh rotation.
- Graph `/me` health check.
- Outlook read.
- Calendar read.

التوثيق:

`docs/MICROSOFT_VERTICAL_SLICE_AR.md`

---

## 7. دفعة بقية الموصلات

قبل بدء هذه المرحلة كان هناك 28 موصلاً `COMING_SOON` في التقدير القديم. بعد إضافة Microsoft Outlook/Calendar أصبح العدد الفعلي الحالي:

> **26 تعريفاً `COMING_SOON`**

تم إنشاء عقد موحد لكل الـ26 في:

`app/src/main/java/com/airi/assistant/connector/ProviderAdapterContract.kt`

وتغطيتها في:

`app/src/test/java/com/airi/assistant/connector/ProviderAdapterContractTest.kt`

كل عقد يحتوي:

- `catalogId`.
- provider.
- auth mode.
- required scopes.
- read-only health endpoint.
- adapter ID.
- official documentation URL.
- `readOnlyFirst`.
- `isExecutable = false`.

الـ26 هي:

```text
Google Docs
Google Sheets
Google Contacts
Google Tasks
Google Meet
Microsoft OneDrive
Microsoft Teams
Microsoft SharePoint
Microsoft To Do
GitLab
Bitbucket
Jira
Linear
Slack
Discord
Trello
Asana
ClickUp
Monday
Todoist
Dropbox
Box
Figma
Canva
Airtable
Zoom
```

تم ربط العقد بمدير التفويض. عند طلب موصل من هذه المجموعة، يرجع:

```text
adapter_not_installed
```

مع معلومات:

- rollout batch.
- required adapter.
- auth mode.
- scopes.
- health endpoint.

مهم: لم يتم تحويل هذه الموصلات إلى Connected وهمياً، ولم يتم تنفيذ HTTP أو OAuth عام غير موثق.

التوثيق:

`docs/REMAINING_CONNECTORS_BATCH_CONTRACT_AR.md`

---

## 8. ملفات التوثيق المضافة

- `docs/ROOT_CAUSE_TOOL_ACCESS_AUDIT_AR.md`
- `docs/CONNECTOR_AUTH_ROLLOUT_PLAN_AR.md`
- `docs/CONNECTOR_ROLLOUT_PHASE3_AR.md`
- `docs/FINAL_RUNTIME_INTEGRATION_MATRIX_AR.md`
- `docs/MICROSOFT_VERTICAL_SLICE_AR.md`
- `docs/PRIMARY_SEVEN_CONNECTOR_GATE_AR.md`
- `docs/REMAINING_CONNECTORS_BATCH_CONTRACT_AR.md`
- `docs/HANDOFF_REPORT_AR.md`

---

## 9. أدوات التحقق المضافة أو المحدثة

الأداة الأهم:

`tools/final_runtime_integration_audit.py`

وتتحقق من:

- runtime tool catalog.
- local/cloud tool calling.
- hybrid failover.
- skills/connectors/builtins convergence.
- memory and scheduled tasks boundaries.
- connector rollout.
- Microsoft vertical slice.
- primary seven contract.
- remaining 26 provider contracts.
- regression test files.

---

## 10. إثباتات التحقق

آخر نتائج مثبتة قبل التسليم:

```text
git diff --check                       PASS
final_runtime_integration_audit       PASS
primary_seven_tool_dispatch_contract  PASS
remaining_26_batch_contract           PASS
no_phantom_activation_guard           PASS
verify_core_changes                   96/96 PASS
security_scan                         PASS
```

الفحوص الأمنية أثبتت:

- لا cleartext traffic override.
- FileProvider غير exported.
- URI grants صريحة.
- attachments داخل app-private storage.
- حدود attachment/text موجودة.
- لا source URI persistence.
- لا secret findings.

نتيجة الفحص الأمني الأخيرة:

```json
{"secret_findings": [], "status": "PASS"}
```

---

## 11. ما لم ينجز بعد

هذه العناصر لا ينبغي اعتبارها منجزة لمجرد وجود العقد:

### 11.1 Android compile فعلي داخل Sandbox

تعذر تشغيل Gradle Android في Sandbox بسبب غياب Android SDK:

```text
SDK location not found
```

يجب تشغيل:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease
```

في CI أو جهاز تطوير Android.

### 11.2 OAuth provider evidence

لم يتم تنفيذ login حقيقي لكل مزود لأن ذلك يحتاج:

- App registrations.
- client IDs.
- redirect URI registration.
- user consent.
- provider accounts.
- scopes approved by provider.

### 11.3 Adapters حقيقية للـ26

العقود موجودة، لكن ما زال مطلوباً بناء vertical slices حقيقية لكل مزود، بالترتيب المقترح:

1. Microsoft OneDrive ثم Teams/SharePoint/To Do.
2. Google Docs/Sheets/Contacts/Tasks.
3. Slack وDiscord.
4. Dropbox وBox.
5. Trello وAsana وClickUp وMonday وTodoist.
6. GitLab وBitbucket وJira وLinear.
7. Figma وCanva وAirtable وZoom.

### 11.4 اختبارات provider الحقيقية

لكل Adapter يجب إضافة اختبارات:

1. بدء الاتصال.
2. cancel.
3. deny.
4. state mismatch.
5. replay callback.
6. missing/wrong PKCE verifier.
7. token exchange failure.
8. secure storage failure.
9. expired/revoked token.
10. health success/failure.
11. tool hidden before readiness.
12. no token leakage.

### 11.5 مراجعة scopes

الـscopes الحالية هي baseline contract وليست موافقة نهائية. يجب مراجعتها مقابل وثائق كل مزود قبل تفعيل adapter، خصوصاً:

- Microsoft high-privilege scopes.
- Google Meet.
- Slack write scopes.
- Jira/Atlassian OAuth permissions.
- Canva/Airtable scopes.

---

## 12. نقطة الاستئناف للحساب الآخر

ابدأ من الفرع `main` بعد commit التسليم الموجود في نهاية هذا التقرير.

الخطوة التالية الموصى بها ليست إضافة تعريفات جديدة؛ التعريفات والعقود موجودة. ابدأ ببناء أول Adapter حقيقي من الدفعة، والأفضل:

```text
Microsoft OneDrive
```

لأنه يعيد استخدام:

- `MicrosoftOAuthConfiguration`.
- `MicrosoftGraphTokenService`.
- `MicrosoftGraphConnector`.
- runtime ID: `microsoft_graph`.

المطلوب في أول خطوة للحساب الآخر:

1. قراءة `docs/HANDOFF_REPORT_AR.md`.
2. قراءة `docs/REMAINING_CONNECTORS_BATCH_CONTRACT_AR.md`.
3. فحص `ProviderAdapterContract` للـcatalog ID المطلوب.
4. إنشاء capability/actions للقراءة فقط.
5. استخدام `ConnectorAuthorizationManager` لا مسار UI خاص.
6. تنفيذ health check حقيقي.
7. عدم جعل `isExecutable=true` إلا بعد الاختبارات.
8. تشغيل كل الفحوص.
9. ثم الانتقال للموصل التالي.

أوامر البداية:

```bash
git fetch origin
git switch main
git pull --ff-only origin main
python3 tools/final_runtime_integration_audit.py
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
```

إذا كان Android SDK متاحاً:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

---

## 13. قاعدة مهمة للحساب الآخر

لا يتم استخدام أي من الأساليب التالية:

- إضافة `if provider == ...` داخل UI لكل مزود.
- إعلان Connected عند حفظ credential فقط.
- وضع client secrets في APK.
- إنشاء generic HTTP adapter يدعي دعم كل المزودين.
- تحويل كل `COMING_SOON` إلى live.
- كشف أدوات write قبل approval.
- اعتبار static contract دليلاً على OAuth حقيقي.

الأسلوب المطلوب:

```text
One provider adapter
  -> typed auth
  -> secure storage
  -> real health check
  -> read-only tools
  -> tests
  -> evidence
  -> next provider
```

---

## 14. ملخص تنفيذي

تم إصلاح الأساس المعماري لوصول النموذج إلى الأدوات، وتوحيد النتائج والأخطاء والصلاحيات، وتوحيد دورة حياة الموصلات. تم تثبيت السبعة الرئيسية، وإضافة Microsoft vertical slice فعلي، وإنشاء عقد كامل للـ26 موصلاً المتبقية مع منع التشغيل الوهمي.

العمل المتبقي هو **تنفيذ provider adapters حقيقية واختبار OAuth/health على CI أو جهاز Android**، وليس إعادة تصميم العقد. يجب أن تبدأ الجلسة التالية من Microsoft OneDrive أو أول موصل Microsoft متبقٍ، وتعيد استخدام manager/token lifecycle الموجودين.
