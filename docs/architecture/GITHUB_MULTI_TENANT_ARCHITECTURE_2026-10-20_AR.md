# معمارية تكامل GitHub متعدد المستخدمين في AIRI

**الحالة:** تصميم مقترح، غير منفذ وغير معتمد للتشغيل الإنتاجي
**الفرع:** `main`
**التاريخ:** 2026-10-20

## القرار المعماري

AIRI ليس تطبيقًا شخصيًا مرتبطًا بحساب GitHub واحد. لذلك يجب أن يكون كل اتصال GitHub موردًا متعدد المستأجرين مرتبطًا بهوية مستخدم AIRI، ثم بمشروع AIRI، ثم بتثبيت GitHub App ومجموعة مستودعات محددة.

التصميم المقترح للنسخة الأولى هو **GitHub App installation flow مع Broker server-side وجلسة opaque**. لا يرسل Broker access token أو refresh token إلى Android. يحتفظ Broker بمفتاح GitHub App وببيانات الاعتماد الخادمية، وينشئ installation access token قصير العمر عند الحاجة. يختار المستخدم الحساب أو المؤسسة والمستودعات من شاشة تثبيت GitHub App، ثم يمكن لـAIRI تطبيق تضييق إضافي على repository IDs لكل مشروع.

هذا يختلف عن OAuth App مشترك أو PAT مشترك. لا يستخدم AIRI حساب مالك المنتج في وصول المستخدمين، ولا يخزن PAT للمطور، ولا يجعل Android طرفًا موثوقًا بمفرده.

تؤكد وثائق GitHub أن تثبيت GitHub App يمنح التطبيق وصولًا إلى مستودعات الحساب المحددة، وأن installation access token محدود بالمستودعات والصلاحيات الممنوحة للتثبيت، وينتهي بعد ساعة. كما تميز GitHub بين installation token الذي يمثل التطبيق وuser access token الذي يمثل المستخدم. [1] [2] [3]

## نتيجة فحص البنية الحالية

### `GithubBrokerClient`

العقد الحالي في:

```text
app/src/main/java/com/airi/assistant/connector/app/github/GithubBrokerClient.kt
```

هو عقد مجال Kotlin وليس wire contract HTTP. يعرّف بدء التفويض، تبادل code، التجديد، الإبطال، وسرد المستودعات. التنفيذ الافتراضي هو `NotConfiguredGithubBrokerClient` ويفشل مغلقًا بـ`BROKER_NOT_CONFIGURED`.

العقد الحالي لا يميز صراحةً بين هوية مستخدم AIRI وهوية GitHub، ولا يعبر عن `installationId` و`connectionId` ككيانات متعددة المستأجرين. كما أن `beginAuthorization()` لا يعيد authorization URL أو handoff contract؛ وهذا صحيح حاليًا لأنه يمنع اختراع HTTP contract قبل وجود Broker.

### `GithubSessionStore`

المخزن الحالي يعزل البيانات بواسطة hash لمعرف المشروع، ويستخدم `ConnectorAuthManager` للتخزين المشفر محليًا. لكنه يخزن داخل `GithubAuthorizationSession`:

- `accessToken`
- `refreshToken`
- `projectId`
- معلومات GitHub والمستودعات

هذا النموذج مناسب لطبقة محلية مؤقتة، لكنه ليس النموذج المفضل لمنتج متعدد المستخدمين؛ إذ لا يحتوي صراحة على `airiUserId` أو `githubAccountId`، ووجود رموز GitHub على الجهاز يزيد أثر اختراق الجهاز ويصعّب الإبطال المركزي والتدقيق والعزل بين الأجهزة.

### `ConnectorAuthManager`

`ConnectorAuthManager` يستخدم `EncryptedSharedPreferences` ويقدم تخزينًا مشفرًا داخل الجهاز، لكنه مدير عام للموصلات وليس مخزن هوية متعدد المستأجرين. لا ينبغي استخدام مفتاح connector عام وحده لتمثيل اتصال GitHub في منتج يكون فيه عدة مستخدمين ومشاريع وأجهزة.

سيبقى `ConnectorAuthManager` مناسبًا لتخزين:

- `airiConnectionId` opaque.
- حالة عرض محلية غير حساسة.
- metadata قصيرة العمر غير سرية.

ولا ينبغي أن يخزن installation access token أو GitHub refresh token في التصميم النهائي.

### `ServiceLocator`

الحقن الحالي يربط `GithubAuthorizationCoordinator` افتراضيًا بـ`NotConfiguredGithubBrokerClient`. هذا fail-closed صحيح، ويجب استبداله لاحقًا بعميل HTTP فقط بعد اعتماد العقد وتشغيل Broker staging.

## نموذج الهوية والعزل

يجب فصل الكيانات التالية وعدم اختزالها في `projectId`:

```text
AIRI User
  └── AIRI Project
        └── GitHub Connection
              ├── GitHub account/organization identity
              ├── GitHub App installation ID
              └── selected repository grants by numeric repository ID
```

### هوية AIRI User

هي الهوية الموثقة من نظام حساب AIRI، مثل `airiUserId` أو subject موثق من جلسة AIRI. لا يرسل Android قيمة user ID يختارها بنفسه؛ يستخرجها Broker من bearer session أو JWT الموثق.

### مشروع AIRI

`projectId` نطاق مستقل داخل مستخدم AIRI. يجب أن يتحقق Broker من أن المشروع مملوك للمستخدم الحالي قبل إنشاء أو قراءة أي GitHub connection. لا يقبل Broker `projectId` من جسم الطلب كدليل ملكية.

### هوية GitHub

تُحفظ كـnumeric GitHub account/user/organization ID، مع login وname للعرض فقط. لا تستخدم login أو `owner/name` كمعرف أمان.

### جلسة التفويض

التفويض المؤقت (`oauthTransaction`) كيان قصير العمر، مربوط بـ:

- AIRI authenticated subject.
- project ID.
- device/client transaction nonce.
- PKCE challenge إن استُخدم في handoff flow.
- redirect/callback transaction.
- TTL وحالة الاستهلاك.

بعد الإكمال، لا يعاد access token إلى Android. يعاد handoff code أحادي الاستخدام، ثم `airiConnectionId` opaque.

### اتصال GitHub

`githubConnectionId` معرف عشوائي لا يكشف `installationId`. يخزن Broker خلفه:

- airiUserId.
- projectId.
- githubAccountId.
- installationId.
- الحالة: active/suspended/revoked.
- granted app permissions.
- selected repository IDs.
- createdAt/lastValidatedAt/revokedAt.

كل استعلام أو mutation يجب أن يحل الاتصال من هوية الجلسة الحالية، لا من معرف يقدمه العميل وحده.

## ما يجهزه مالك المنتج مرة واحدة

لا يحتاج مالك المنتج إلى إعطاء AIRI بيانات حساب GitHub الشخصية أو PAT الخاص به.

يجهز مالك المنتج مرة واحدة:

1. تسجيل GitHub App باسم المنتج.
2. اختيار homepage وsetup/callback URLs للبيئات، مثل staging وproduction، بعد امتلاك نطاقات فعلية.
3. تفعيل أقل repository/account permissions.
4. اختيار أن تكون repository access الافتراضية `Only select repositories`، لا `All repositories`.
5. حفظ App ID وClient ID وأي private key في Secret Manager خادمي، وليس Android أو Git.
6. نشر Broker خلف HTTPS مع نطاق ثابت.
7. إنشاء مخزن جلسات ومعاملات قصير العمر.
8. ربط Broker بمزود هوية AIRI للتحقق من المستخدم والمشروع.
9. إعداد redacted audit logs، metrics، rate limits، alerting، وrotation للمفاتيح.
10. تجهيز بيئات منفصلة: local fake broker، staging GitHub App، production GitHub App أو إعداد معزول حسب سياسة المنتج.

لا يلزم مالك المنتج تثبيت التطبيق على مستودعاته الشخصية لكي يعمل المنتج للمستخدمين. كل مستخدم أو مسؤول مؤسسة ينفذ تثبيتًا مستقلًا لحسابه أو مؤسسته.

## ما يفعله كل مستخدم

عند الضغط على `Connect GitHub`:

1. يسجل المستخدم الدخول إلى AIRI، إن لم يكن مسجلًا.
2. ينشئ AIRI/Broker معاملة تفويض مرتبطة بمستخدم AIRI ومشروعه.
3. يفتح التطبيق صفحة GitHub App installation عبر Custom Tab أو المتصفح.
4. يختار المستخدم حسابه أو المؤسسة التي يملك صلاحية تثبيت التطبيق فيها.
5. يختار `Only select repositories` والمستودعات التي يسمح للوكيل بالوصول إليها.
6. يعيد GitHub المستخدم إلى Broker setup callback.
7. يتحقق Broker من state وtransaction وinstallation ownership ويستقبل `installation_id`.
8. يعيد Broker handoff code قصير العمر إلى deep link آمن في AIRI.
9. يستبدل Android handoff code عبر قناة AIRI الموثقة.
10. يعرض AIRI الحساب والمستودعات والمنح، ثم يربط المستودعات بالمشروع.

يمكن للمستخدم تعديل أو إلغاء تثبيت GitHub App لاحقًا من GitHub. يجب أن يتعامل Broker مع webhook أو فحص دوري لحالة التثبيت ويحوّل الاتصال إلى revoked/suspended عند فقدان الوصول. توضح وثائق GitHub أن المستخدم يستطيع تعديل قائمة المستودعات أو تعليق/إلغاء تثبيت التطبيق. [4]

## لماذا installation flow هو الاختيار الأول

### GitHub App installation

يناسب AIRI لأن الوصول يمكن تقييده على مستوى الحساب أو المؤسسة وعلى مستوى المستودعات. يستطيع Broker إنشاء installation token مع `repository_ids` محددة، وبصلاحيات أقل من الحد الأقصى للتطبيق، وينتهي token بعد ساعة. هذا يحقق قراءة المستودعات دون نشر token طويل العمر إلى Android. [1] [2]

### GitHub App user authorization

يظل خيارًا إضافيًا إذا احتاج AIRI أن تنسب نشاطًا محددًا إلى المستخدم نفسه، أو إذا احتجنا بيانات حساب user-to-server لا يوفرها installation identity. هذا التدفق يتطلب حفظ رموز المستخدم وتجديدها خلف Broker، ويدخل في مسار `client_secret` وrefresh rotation. لا ينبغي خلطه مع installation session في نفس `GithubAuthorizationSession`.

### OAuth App التقليدي

ليس الاختيار الأول؛ فهو يمنح وصولًا واسعًا نسبيًا عبر scopes ويصعّب فصل repository grants بدقة مقارنة بـGitHub App. كما أن shared OAuth/PAT model لا يطابق متطلبات AIRI التجارية متعددة المستخدمين.

## Broker المقترح

### الاستضافة

خدمة HTTPS عديمة الحالة قدر الإمكان، تعمل كحاوية صغيرة على منصة تدعم scale-to-zero، مثل Cloud Run أو بديل مكافئ. لا أختار مزودًا نهائيًا قبل معرفة بلد الاستضافة، متطلبات الخصوصية، ومزود هوية AIRI.

المكونات الدنيا:

- Broker API.
- PostgreSQL أو مخزن علائقي مُدار للاتصالات والمعاملات والتدقيق غير السري.
- Redis أو TTL store لمعاملات state/handoff، ويمكن استخدام جدول TTL في PostgreSQL في الحجم الأولي.
- Secret Manager للمفتاح الخاص وclient credentials.
- GitHub webhook endpoint.
- Observability مع redaction.

GitHub لا يفرض على AIRI PAT مشتركًا أو حسابًا مركزيًا. تكاليف التشغيل المتوقعة تأتي من استضافة Broker، قاعدة البيانات، مخزن TTL، السجلات، الشبكة، وأي مزود هوية. يمكن أن تكون البداية منخفضة التكلفة باستخدام خدمة scale-to-zero ومخزن صغير مُدار، لكن الأسعار والحصص المجانية تتغير ويجب تثبيتها قبل اختيار المزود.

### تخزين الأسرار

في الخادم فقط:

- GitHub App private key.
- GitHub App client secret إذا فُعّل user authorization.
- مفاتيح تشفير بيانات الجلسات.
- مفاتيح توقيع جلسات AIRI إن لم تكن لدى مزود الهوية.

لا تدخل هذه القيم APK أو Git أو logs أو رسائل المستخدم.

### تخزين الجلسات

يفضل النموذج التالي:

- لا تخزن installation access token طويلًا؛ أنشئه عند الحاجة أو خزنه مؤقتًا في الذاكرة/secret cache مع expiry.
- إذا فُعّل user access token، خزّن reference مشفرًا أو ciphertext داخل Secret Manager/DB مع key encryption key منفصلة.
- لا تخزن raw token في جدول عادي أو telemetry.
- خزّن repository IDs وpermissions والحالة كبيانات grants قابلة للتدقيق.

## عقد API المقترح

هذا عقد تصميمي فقط، وليس endpoint منشورًا أو منفذًا. لا يجوز تحويله إلى Android HTTP adapter قبل اعتماده وتشغيل staging.

### بدء التثبيت

```text
POST /v1/github/connections/install
Authorization: Bearer <AIRI session>
```

Request:

```json
{
  "projectId": "project-public-id",
  "clientNonce": "random-client-nonce"
}
```

يستخرج Broker `airiUserId` من Authorization، ويتحقق من ملكية `projectId`. لا يقبل user ID من body.

Response:

```json
{
  "transactionId": "opaque-transaction-id",
  "authorizationUrl": "https://github.com/apps/<app-slug>/installations/new?...",
  "expiresAt": "2026-10-20T00:30:00Z"
}
```

لا تحتوي الاستجابة على private key أو access token.

### GitHub setup callback

```text
GET /v1/github/callback/setup
```

هذه endpoint خادمية يستقبل parameters من GitHub، ويتحقق من transaction/state وTTL وreplay، ومن `installation_id` و`setup_action`، ثم يربط التثبيت بالمستخدم والمشروع. لا يعيد بيانات سرية في query redirect إلى Android.

### handoff إلى Android

```text
GET /v1/github/callback/mobile
```

يعاد deep link يحتوي handoff code أحادي الاستخدام فقط، أو تتم إعادة النتيجة عبر universal/app link محمي. يجب ألا يوضع authorization code أو token في الرابط.

### استبدال handoff

```text
POST /v1/github/connections/exchange
Authorization: Bearer <AIRI session>
```

Request:

```json
{
  "transactionId": "opaque-transaction-id",
  "handoffCode": "one-time-code",
  "projectId": "project-public-id"
}
```

يمنع Broker استخدام handoff مرتين، ويتحقق من user/project/transaction binding.

Response:

```json
{
  "connectionId": "opaque-connection-id",
  "githubAccount": {
    "id": 123456,
    "login": "display-only-login",
    "type": "User"
  },
  "status": "repository_selection_required"
}
```

### قائمة المستودعات المتاحة

```text
GET /v1/github/connections/{connectionId}/repositories
Authorization: Bearer <AIRI session>
```

يعيد Broker فقط المستودعات التي يملك installation access حق الوصول إليها، مع numeric repository IDs وowner/name للعرض. يجب ألا يعيد tokens.

### حفظ اختيار المشروع

```text
PUT /v1/github/connections/{connectionId}/projects/{projectId}/repositories
Authorization: Bearer <AIRI session>
```

Request:

```json
{
  "repositoryIds": [123456, 789012]
}
```

Broker يتحقق من أن كل ID ضمن installation grant، ثم يحفظ grants المرتبطة بالمستخدم والمشروع والاتصال. تكرار الطلب idempotent.

### قراءة محتوى مستودع

```text
GET /v1/github/projects/{projectId}/repositories/{repositoryId}/contents?path=...
Authorization: Bearer <AIRI session>
```

يتحقق Broker من AIRI user وproject وconnection وrepository grant وread permission، ثم ينفذ طلب GitHub server-side. يعيد بيانات القراءة المنقحة دون token.

### التجديد والإبطال

```text
POST /v1/github/connections/{connectionId}/refresh
DELETE /v1/github/connections/{connectionId}
```

في installation model لا يحتاج Android إلى refresh token؛ يعيد Broker إنشاء installation token عند انتهاء token ذي الساعة الواحدة. الإبطال يمسح connection/grants محليًا ويوقف استعمال installation، مع التعامل مع uninstall webhook.

### الكتابة لاحقًا

```text
POST /v1/github/projects/{projectId}/repositories/{repositoryId}/mutations
Authorization: Bearer <AIRI session>
Idempotency-Key: <unique-key>
```

لا تُفعل في النسخة الأولى. عند تفعيلها يجب أن تتطلب:

- task approval durable.
- approval continuation المرتبط بنفس user/project/repository/action.
- SHA وbranch تم جلبهما والتحقق منهما بعد الموافقة أو ضمن سياسة freshness محددة.
- رفض `.github/workflows` ما لم تعتمد صلاحية Workflows صراحة.
- serial execution وعدم retry التلقائي لنتيجة غير محسومة.
- تصنيف 409 conflict وإجبار إعادة قراءة/مراجعة قبل إعادة المحاولة.

توثق GitHub أن تحديث الملف يتطلب SHA عند الاستبدال وأن 409 يعني تعارضًا، لذلك لا يجوز اختصار التحقق إلى repository name فقط. [5]

## التغييرات المطلوبة في Android لاحقًا

بعد اعتماد Broker contract فقط:

1. استبدال `GithubAuthorizationSession` بنموذج client-safe يحتوي `airiConnectionId` و`githubAccountSummary` وrepository grants وexpiry، دون access/refresh tokens.
2. إضافة `airiUserId` إلى طبقة domain فقط إذا كان مصدره هوية موثقة، وعدم قبوله من UI كقيمة ثقة.
3. تغيير `GithubBrokerClient` ليعيد authorization URL وhandoff exchange وopaque connection.
4. جعل `GithubSessionStore` يخزن opaque connection والـprojection فقط.
5. إبقاء `ConnectorAuthManager` مخزنًا محليًا مشفرًا للمرجع غير السري، لا مخزن GitHub token.
6. ربط `IntegrationsViewModel` باختيار repository IDs وبعرض الحساب والمشروع.
7. توحيد `GitHubConnector` و`ToolRegistry` خلف repository/session gate نفسه، وإزالة مسار القراءة الذي يعتمد على PAT كبديل صامت.
8. إضافة feature flag للقراءة عبر Broker، مع بقاء الكتابة disabled.

## الاختبارات المطلوبة

### اختبارات Broker

- user A لا يستطيع قراءة connection أو project للمستخدم B.
- project A لا يستطيع استخدام connection المرتبط بـproject B إلا بسياسة مشاركة صريحة موثقة.
- state mismatch.
- PKCE mismatch عند استخدام user authorization أو handoff PKCE.
- TTL expiry.
- handoff replay.
- handoff مع user/project mismatch.
- installation ID لا ينتمي إلى الحساب المتوقع.
- repository ID خارج installation grant.
- repository ID صحيح لكن permission ناقص.
- uninstall/suspend webhook.
- token expiry وتجديد installation token.
- rate limit و5xx دون تسريب response body.
- redaction للرموز وauthorization codes وrequest bodies.

### اختبارات Android

- زر Connect لا يعرض نجاحًا إذا عاد `BROKER_NOT_CONFIGURED`.
- callback لا يستهلك handoff مرتين.
- session projection لا يحتوي access/refresh token.
- project switching لا يخلط repository grants.
- إعادة تشغيل التطبيق تبقي connection opaque فقط.
- logout يمسح projection المحلي ولا يدعي remote revoke دون نتيجة Broker.
- القراءة تمر عبر Broker gate ولا تصل إلى legacy PAT fallback.
- الكتابة تظل مرفوضة دون approval continuation.

### اختبارات staging end-to-end

لا تعتبر FakeBroker نجاحًا provider integration. يلزم تشغيل حقيقي مرتبط بإصدار Broker وSHA:

1. مستخدم AIRI تجريبي.
2. GitHub account أو organization تجريبية.
3. GitHub App staging.
4. مستودع تجريبي غير حساس.
5. تثبيت `Only select repositories`.
6. Connect من Android.
7. قراءة repository metadata وfile contents.
8. إزالة المستودع من التثبيت والتحقق من الرفض.
9. إلغاء التثبيت والتحقق من revoke.
10. إثبات عدم وجود tokens في Android logs أو artifacts.

## ما يمكن تنفيذه الآن وما يحتاج إعدادًا خارجيًا

يمكن تنفيذه الآن محليًا، دون أسرار أو Broker:

- تحديث نماذج domain إلى opaque connection projection.
- إضافة fake contract tests لعزل AIRI user/project/connection/repository.
- إضافة اختبارات عدم تسريب token في `toString` وlogs.
- إعداد interface واضح لـrepository selection وread gateway.
- إبقاء `NotConfiguredGithubBrokerClient` افتراضيًا.
- إعداد feature flag مغلق للـBroker.

يتطلب إعدادًا خارجيًا:

- تسجيل GitHub App.
- App ID وslug وclient ID.
- private key وSecret Manager.
- نطاق callback HTTPS فعلي.
- Broker مستضاف.
- قاعدة بيانات/TTL store.
- تكامل هوية AIRI.
- staging account/organization/repository.
- قرار قانوني/تشغيلي حول retention والخصوصية والتدقيق.

لا يمكن إثبات التفويض الحقيقي أو القراءة الحقيقية قبل هذه العناصر.

## موقف التنفيذ الآن

لا أوصي بإضافة HTTP adapter في هذا الدور، لأن Broker وstaging وwire contract الفعلي غير موجودة، ولأن تغيير النموذج من token-bearing session إلى opaque multi-tenant session يحتاج اعتماد هذا القرار أولًا. كما أن `main` المحلي متقدم بخمسة commits عن `origin/main`، ولم يتم تنفيذ `push`.

بعد اعتماد هذا التصميم، تكون أول دفعة تنفيذ آمنة هي نماذج opaque وfake contract tests وrepository selection projection فقط. بعدها يُعتمد wire contract staging، ثم ينفذ HTTP adapter خلف feature flag، ثم يختبر read-only E2E. الكتابة تبقى مغلقة حتى مراجعة مستقلة واختبار approval/SHA.

## المراجع

[1]: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app "Generating an installation access token for a GitHub App"

[2]: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation "Authenticating as a GitHub App installation"

[3]: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/differences-between-github-apps-and-oauth-apps "Differences between GitHub Apps and OAuth apps"

[4]: https://docs.github.com/en/apps/using-github-apps/reviewing-and-modifying-installed-github-apps "Reviewing and modifying installed GitHub Apps"

[5]: https://docs.github.com/en/rest/repos/contents "REST API endpoints for repository contents"
