# تقرير التدقيق الجذري لمسارات الموصلات والمهارات والأدوات في AIRI

**التاريخ:** 2026-10-16  
**الفرع المفحوص:** `main`  
**آخر commit مفحوص:** `21abde84`  
**النطاق:** Connector runtime، Google/GitHub authorization، SkillRegistry/SkillToolBridge، Tool catalog، AgentLoop، ToolDispatcher، firewall، sandbox/terminal.

## الخلاصة التنفيذية

المشكلة ليست عيبًا واحدًا في التوكن. مسار AIRI الحالي يطبق عدة حدود مستقلة، وأي حد منها يكفي لظهور سلوك «لا أستطيع الوصول»:

1. **تسجيل الدخول لا يساوي تفويض البيانات في Google.** Google Identity يثبت هوية الحساب، بينما Gmail وDrive وCalendar تحتاج `AuthorizationClient` وaccess token خاصًا بالنطاق المطلوب.
2. **صلاحيات GitHub ليست صلاحية شاملة واحدة.** نجاح `GET /user` لا يثبت قراءة مستودع خاص أو محتوى ملف؛ fine-grained PAT يطبّق صلاحيات على مستوى المستودع والـendpoint.
3. **الوصول في AIRI مقسوم إلى profile لكل surface، ثم صحة الموصل، ثم provider scope، ثم بوابة AgentLoop/firewall.** وجود token وحده لا يتجاوز هذه الطبقات.
4. **كان كاشف نية الموصلات يختار أول موصل مذكور فقط.** هذا كان يسقط الموصلات الأخرى في الطلب المركب مثل: «اعرض رسائل Gmail ومستودعاتي في GitHub». تم إصلاحه في هذا التدقيق مع اختبار regression.
5. **المسار الإنتاجي الحالي موصول فعليًا:** `ChatViewModel → RuntimeToolCatalog → AgentLoop → ExecutionFirewall → ToolDispatcher → ConnectorToolBridge/SkillToolBridge → ConnectorRuntimeManager/SkillRegistry`. توجد مسارات legacy (`ToolRegistry`/`ToolExecutor`) ما زالت مستخدمة في مهام خلفية محدودة، لكنها ليست مسار AgentLoop الرئيسي.

## الأدلة من المصدر

### مسار الموصلات

- `ConnectorBootstrap` يسجل الموصلات في `ConnectorRegistry` ثم يستعيد الاتصالات المحفوظة.
- `ConnectorToolBridge` يبني أسماء الأدوات من الشكل `connector_<connector>_<action>` ويربطها بالـruntime.
- `ConnectorRuntimeManager` يرفض التنفيذ إذا كان:
  - الموصل غير مسجل أو مفصولًا صراحة؛
  - الفعل غير معلن في `agentActions()`؛
  - access profile غير مناسب؛
  - المدخلات لا تطابق schema؛
  - فحص الصحة أو حالة surface غير سليمة.
- `ToolDispatcher` يمرر الأدوات الديناميكية إلى `SkillToolBridge` أو `ConnectorToolBridge`، ولا ينفذها مباشرة من اسمها.
- `ToolPermissionPolicy` يمنح dynamic connector tools وskill tools بوابة firewall الخارجية، ثم تبقى صلاحيات الموصل/المهارة الداخلية إلزامية.

### مسار المهارات

- `OfficialSkillLibrary` هو مصدر manifests الرسمي.
- `SkillRegistry` يفحص dependencies و`connectorActionDependencies` قبل عرض المهارة.
- `SkillToolBridge` يعيد فحص permission، connector health، model access، وtimeout عند الاستدعاء.
- المهارة غير الجاهزة لا تظهر كأداة قابلة للتنفيذ؛ هذا صحيح أمنيًا، لكنه قد يبدو للمستخدم كأن المهارة غير موجودة ما لم يقرأ inventory.

### مسار الأدوات

- `RuntimeToolCatalog` يفرق بين `candidates` و`exposed` و`filtered`.
- الموصل لا يُعرض للنموذج إلا إذا كان مطلوبًا من النية (`connectorIds` أو `*`) وكان جاهزًا.
- `ToolSchemaPromptBudget` يزيل الأدوات التي لا تتسع في سياق النموذج، ويجعلها غير متاحة في نفس الطلب حتى لا يرى النموذج أداة لا يستطيع dispatcher قبولها.
- `AgentLoop` يتحقق من الوسائط typed قبل تمريرها إلى dispatcher.

## الأسباب المؤكدة لسلوك الرفض

### 1. Google: الهوية ليست access token لبيانات المستخدم

`GoogleAuthService` يحتفظ بهوية الحساب وبجلسات access token لكل surface منفصل:

- `google_gmail`
- `google_drive`
- `google_calendar`
- `google_docs`
- `google_sheets`
- `google_contacts`
- `google_tasks`

لذلك قد تكون هوية Google «متصلة» بينما Gmail أو Drive في حالة `authorization_required`. كذلك يعاد طلب التفويض بعد إعادة تشغيل العملية لأن access token في الذاكرة فقط، بينما تحفظ scopes المشفرة لإعادة التفويض الآمن.

هذا مطابق للتوثيق الرسمي: Google يميز بين authentication وauthorization، ويوصي باستعمال `AuthorizationClient.authorize()` عند تنفيذ فعل يحتاج بيانات Google، مع طلب scopes تدريجيًا.

### 2. GitHub: فحص `/user` لا يثبت صلاحية repository contents

`GitHubConnector.connect()` يفحص `/user` لتأكيد صحة PAT. هذا اختبار صحة اعتماد الحساب، وليس اختبار صلاحية:

- repository metadata؛
- repository contents؛
- المستودع المطلوب تحديدًا؛
- المؤسسة التي تفرض SAML SSO؛
- fine-grained repository selection.

بالتالي قد يكون `get_user` ناجحًا ثم يفشل `get_file` بـ`403` أو `404` حسب صلاحيات PAT أو خصوصية المستودع. هذا ليس تناقضًا في GitHub API.

### 3. access profile مستقل عن token

كل action يستخدم `surfaceId` و`ConnectorAccessProfileStore`. القيمة الافتراضية `NOT_CONFIGURED` ترفض كل فعل. الترحيل القديم يمنح `READ_ONLY` فقط لبعض الاتصالات القديمة (Google/GitHub/Telegram)، ولا ينبغي اعتبار وجود token في التخزين دليلاً على وجود profile حالي.

### 4. التفويض Google مقسم لكل surface

حتى لو تمت الموافقة على Gmail، لا يعني ذلك أن Drive أو Calendar مفوضان. `GoogleDataScopes` و`GoogleConnector.stateForAction()` يطبقان هذا الفصل عمدًا. هذا مطابق لقاعدة Google الرسمية بأن access token صالح فقط لمجموعة العمليات والموارد التي يغطيها scope.

### 5. الطلبات المركبة كانت تسقط الموصلات اللاحقة

قبل الإصلاح كان `CapabilityIntentDetector` يستخدم `firstOrNull` على أهداف الموصلات. النتيجة: طلب يجمع Gmail وGitHub يرسل `google` فقط إلى `RuntimeToolCatalog`، فتُحجب أدوات GitHub حتى لو كانت متصلة وصحية.

**الإصلاح المنفذ:** جمع جميع الأهداف المطابقة في `LinkedHashSet`، مع إزالة aliases التي تشير إلى نفس runtime، وإرسال المجموعة كاملة إلى `Intent.connectorIds`.

## الإصلاح المنفذ في هذا التدقيق

- تعديل `CapabilityIntentDetector.kt` لدعم كل الموصلات المذكورة في الطلب الواحد.
- إضافة اختبار `compoundRequestKeepsEveryMentionedConnector` في `CapabilityIntentDetectorTest.kt`.
- الحفاظ على السلوك السابق للطلبات ذات الموصل الواحد وwildcard `*`.
- عدم تخفيف أي profile أو provider scope أو firewall gate.

## نتائج الاختبار والفحص

### ناجح

- `scripts/connector_lifecycle_static_scan.py`: **13/13 checks passed**.
- `git diff --check`: ناجح.
- فحص ساكن لمسار الدمج: dynamic connector bridge موجود، وdynamic dispatch موجود، وskill dispatch موجود.
- فحص سجل GitHub الحي عبر `gh api`:
  - `GET /user`: `200 OK`.
  - الوصول إلى المستودع: ناجح، وGitHub أكد صلاحيات الحساب الإداري على المستودع.
- الفحص لم يجد أسرارًا أو tokens داخل المصدر.

### محجوب بيئيًا

تشغيل:

```text
./gradlew :app:testDebugUnitTest --tests ...
```

توقف قبل compilation لأن بيئة Sandbox لا تحتوي Android SDK ولا `ANDROID_HOME`/`local.properties`. هذا **ليس فشلًا في اختبارات Kotlin**؛ بل عائق toolchain يجب تشغيله في CI أو Android development environment.

### لا يمكن إثباته من Sandbox

لا يمكن إثبات Gmail/Drive end-to-end أو GitHub private repository access دون تشغيل APK على جهاز المستخدم مع:

- حساب Google المستخدم وإكمال consent لكل surface؛
- PAT الخاص بالمستخدم، دون إرساله داخل المحادثة؛
- مستودع خاص فعلي واختيار fine-grained permissions؛
- Android SDK وGoogle Play Services العاملين.

لذلك لا يصح إعلان أن كل الموصلات تعمل «100%» اعتمادًا على الفحص الساكن وحده.

## مقارنة التدفق بالمراجع الرسمية والمشاريع المشابهة

### Google Identity الرسمي

التدفق الصحيح هو:

1. authentication لإثبات الحساب؛
2. authorization منفصل لكل data surface؛
3. طلب scope عند الحاجة؛
4. فحص النطاقات التي أعادها provider؛
5. استدعاء API باستخدام `Authorization: Bearer <access token>`؛
6. إعادة التفويض/تحديث access token عند انتهاء صلاحيته.

AIRI يطبق الفصل بين الهوية وdata authorization، وهو أفضل من استعمال ID token كأنه Gmail token.

### GitHub REST الرسمي

GitHub يطلب Authorization header، ويؤكد أن fine-grained PAT يحتاج permissions محددة لكل endpoint، وأن نقص الصلاحية قد يظهر `403` أو `404`. لذلك يجب تفسير نجاح `/user` كـaccount authentication فقط.

### نمط MCP/الوكلاء

النمط السليم هو فصل `tools/list` عن `tools/call`، وإعادة بناء قائمة الأدوات عند تغير session/auth، ثم تطبيق authorization عند call لا عند العرض فقط. AIRI يطبق ذلك عبر `RuntimeToolCatalog` و`ConnectorRuntimeManager`، مع ضرورة إبقاء مسار legacy تحت المراقبة.

## طريقة التحقق على جهاز Android

1. افتح شاشة الموصلات وتأكد أن surface نفسه ليس فقط runtime العام في حالة Connected.
2. اختر profile `READ_ONLY` لكل surface مطلوب.
3. Google: نفذ sign-in ثم وافق على Gmail أو Drive أو Calendar كل واحد عند الحاجة.
4. GitHub: أعد حفظ PAT الصحيح، ثم اختبر `get_user` وبعده `list_repos` ثم `get_file` لمستودع محدد.
5. افحص logcat بحثًا عن:
   - `TOOL_LIST_SIZE`
   - `AIRI TOOL_CALL`
   - `AIRI CONNECTOR_TOOL_DISPATCH`
   - `authorization_required`
   - `permission_denied`
   - `not_connected`
6. إذا ظهر `TOOL_LIST_SIZE ... connectors=0` رغم اتصال الموصل، افحص profile/health/surface قبل التوكن.
7. إذا ظهر `connector_github_get_file` ثم `403/404`، فالمشكلة provider repository permission أو SSO، وليست AgentLoop.

## المراجع الرسمية

- [Android: Authorize access to Google user data](https://developer.android.com/identity/authorization)
- [Google OAuth 2.0](https://developers.google.com/identity/protocols/oauth2)
- [GitHub REST authentication](https://docs.github.com/rest/authentication/authenticating-to-the-rest-api)
- [GitHub fine-grained token permissions](https://docs.github.com/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens)
- [Model Context Protocol tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [GitHub MCP Server](https://github.com/github/github-mcp-server)

## الحكم النهائي

تم العثور على سبب برمجي مؤكد وإصلاحه: **إسقاط الموصلات اللاحقة في الطلبات متعددة الموصلات**. كما ثبت أن رفض Google/GitHub في حالات أخرى قد يكون سلوكًا صحيحًا ناتجًا عن فصل الهوية عن data authorization، أو profile، أو endpoint/repository permissions.

المسار البرمجي لا يسمح بتجاوز هذه البوابات، وهذا صحيح أمنيًا. لا يمكن إعلان الحل النهائي لكل حساب قبل تشغيل الاختبارات الحية على APK مع حسابات Google/GitHub الفعلية؛ أما إثبات البناء الآلي فيتطلب توفير Android SDK في CI أو بيئة Android محلية.

## متابعة التدقيق: أسباب إضافية تجعل AIRI يبدو كـ chatbot

### 6. أفعال الموصلات الكتابية لم تكن تدخل مسار الأدوات

كان `CapabilityIntentDetector` يتعرف على أفعال القراءة فقط مثل `read/list/search`، بينما صيغ مثل `send/create/update/delete` كانت تمر أحيانًا كـ`user_action` عامة. لذلك لا تُرسل هوية الموصل إلى `RuntimeToolCatalog`، ولا يرى النموذج أدوات الموصل المطلوبة.

تمت إضافة كشف الأفعال الإنجليزية والعربية (`send/create/...` و`ارسل/انشئ/اكتب/حدث/احذف/...`) مع اختبار Gmail وGitHub. هذا لا يمنح صلاحية كتابة ولا يتجاوز approval؛ إنه يصل الطلب إلى بوابة الموصل كي تعيد `approval_required` أو `unsupported` بدل إجابة عامة.

> ملاحظة: بعض الموصلات الحالية read-only عمدًا. كشف طلب الكتابة يجعل النتيجة صريحة، لكنه لا يخترع فعل كتابة غير معلن.

### 7. ضغط prompt كان قد يحذف الفعل المطلوب

`RuntimeToolCatalog` كان يضع أفعال الموصل في المقدمة، لكن يرتب الأفعال الشقيقة أبجديًا. في نموذج بسياق صغير وميزانية أدوات محدودة، يمكن أن تظهر `list_*` و`status` بينما يُحذف `get_file` أو `gmail_read` المطلوب.

تمت إضافة `requestRelevanceScore` وترتيب الفعل المطابق لعبارات الطلب قبل الأفعال الشقيقة، مع مرادفات عربية مثل `ملف → file` و`مستودع → repo` و`رسائل → email`، وإضافة اختبار يثبت أن `connector_github_get_file` يتقدم عند طلب قراءة ملف.

### 8. مهارة Gmail القديمة كانت تعيد صندوق Sent دائمًا

`GmailAssistantSkill` كان يرسل query ثابتًا `in:sent` حتى عند طلب «افحص صندوق الوارد». هذا لا يمنع الرد، لكنه ينتج نتيجة خاطئة ويجعل المستخدم يعتقد أن الموصل لا يعمل.

تم تصحيح الاستعلام ليحترم نية المستخدم:

- inbox / البريد الوارد → `in:inbox`
- unread / غير مقروء → `is:unread`
- sent / المرسلة → `in:sent`
- الافتراضي → `in:anywhere`

## متابعة الاختبارات

- فحص lifecycle: **13/13 ناجح**.
- فحوص ثابتة مخصصة للإصلاحات الجديدة: **4/4 ناجحة**.
- `git diff --check`: ناجح.
- اختبارات Gradle المستهدفة لم تبدأ مرحلة compilation بسبب غياب Android SDK و`ANDROID_HOME` في Sandbox:

```text
SDK location not found ... local.properties
```

يجب تشغيل اختبارات JVM في Android CI أو بيئة تحتوي SDK؛ لا يجوز تفسير هذا كفشل لاختبارات منطق Kotlin.
