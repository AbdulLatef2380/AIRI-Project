# عقد GitHub Broker متعدد المستخدمين — 2026-10-20

هذا المستند يثبت العقد المنطقي بين تطبيق AIRI Android وBroker الخادمي. لا يمثل Fake Broker خدمة إنتاج، ولا ينشئ endpoint حقيقيًا أو يثبت جاهزية GitHub App production.

## حدود الثقة والهوية

يحصل Broker على `airiUserId` من جلسة AIRI موثقة أو bearer token/JWT تم التحقق منه خادميًا. لا يرسل Android `airiUserId` كحقل ثقة، ولا يسمح Broker بتغييره من جسم الطلب. كذلك يتحقق Broker من ملكية `projectId` للمستخدم الموثق في كل عملية.

تظل القيم التالية منفصلة:

| القيمة | المعنى | المصدر/القاعدة |
|---|---|---|
| `airiUserId` | مستخدم AIRI الموثق | يستخرجها Broker من جلسة المصادقة، وليست من body |
| `projectId` | مشروع AIRI داخل نطاق المستخدم | يرسله التطبيق كسياق، ويتحقق Broker من الملكية |
| `airiConnectionId` | مرجع opaque للاتصال | ينشئه Broker ولا يكشف installation ID |
| `githubAccountId` | المعرّف الرقمي لحساب GitHub | يعيده Broker بعد التحقق من التثبيت |
| `repositoryId` | المعرّف الرقمي للمستودع | لا يقبل Broker إلا ID ضمن grant التثبيت |

لا يرسل Android `installationId` أو installation access token أو refresh token أو App private key. لا يحفظ Android أيًا منها.

## العقد المنطقي في Android

العقد موجود في `GithubBrokerClient.kt`، وأنواعه لا تحمل أسرارًا:

- `beginAuthorization`: يبدأ معاملة GitHub App Installation مرتبطة بالمستخدم الموثق والمشروع، ويعيد `transactionId` و`authorizationUrl` ووقت الانتهاء.
- `exchangeAuthorizationCode`: يستبدل handoff code قصير العمر لمرة واحدة بجلسة opaque. القيمة المسماة `handoffCode` ليست GitHub token.
- `listConnections`: يعيد الاتصالات التي يحق للمستخدم الموثق رؤيتها، اختياريًا ضمن مشروع محدد.
- `refreshSession`: يعيد إسقاط اتصال صالح بعد انتهاء المدة المحلية عبر Broker، دون refresh token على الجهاز.
- `listRepositories`: يعيد فقط grants التي يملكها الاتصال والتثبيت.
- `selectRepositories`: يثبت اختيارًا ضمن IDs التي أعادها Broker؛ لا يوسّع صلاحيات التثبيت.
- `revokeSession`: يلغي الاتصال خادميًا عند الإمكان، ويمسح projection المحلي حتى عند فشل الإبطال البعيد.

## Wire mapping المقترح للإنتاج

كل endpoint يستخدم HTTPS و`Authorization: Bearer <AIRI session>`، ويستخرج Broker المستخدم من الجلسة. لا تعتبر أمثلة URLs التالية تنفيذًا موجودًا في Android؛ هي contract يجب أن يطبقه Broker الحقيقي لاحقًا.

### بدء التثبيت

`POST /v1/github/authorization/start`

```json
{
  "projectId": "project-a",
  "clientNonce": "opaque-client-nonce",
  "codeChallenge": "S256-challenge",
  "requestedScopes": ["repo:read"]
}
```

الاستجابة:

```json
{
  "transactionId": "opaque-transaction",
  "authorizationUrl": "https://github.com/apps/airi/installations/new?...",
  "expiresAtMs": 1770000000000
}
```

يحتفظ Broker بالـtransaction ويربطها بالمستخدم الموثق والمشروع، ويضع redirect إلى callback الخادمي. لا يضع installation ID أو token في redirect إلى Android.

### callback وhandoff

`GET /v1/github/authorization/callback` endpoint خادمي يستقبل GitHub `installation_id` و`setup_action`، ويتحقق من transaction وstate وTTL وملكية التثبيت. بعد النجاح يعيد deep link آمنًا يحوي state وhandoff code قصير العمر فقط.

`POST /v1/github/authorization/exchange`

```json
{
  "projectId": "project-a",
  "transactionId": "opaque-transaction",
  "handoffCode": "one-time-handoff"
}
```

الاستجابة تعرض فقط projection:

```json
{
  "airiConnectionId": "opaque-connection",
  "projectId": "project-a",
  "githubAccountId": 12345,
  "accountLogin": "user-a",
  "expiresAtMs": 1770000000000,
  "grantedScopes": ["repo:read"],
  "repositories": []
}
```

يجب رفض replay أو project mismatch أو handoff من مستخدم آخر.

### الاتصالات والمستودعات

- `GET /v1/github/connections?projectId=project-a` يعيد اتصالات المستخدم الموثق فقط.
- `GET /v1/github/connections/{connectionId}/repositories` يتحقق من user/project/connection قبل الإرجاع.
- `PUT /v1/github/connections/{connectionId}/projects/{projectId}/repositories` يستقبل `{ "repositoryIds": [11, 22] }`، ويرفض أي ID خارج installation grant، ويكون idempotent.
- `POST /v1/github/connections/{connectionId}/refresh` يعيد projection جديدًا بعد أن ينشئ Broker installation token مؤقتًا خادميًا.
- `DELETE /v1/github/connections/{connectionId}` يلغي الاتصال والمنح، ويتعامل مع uninstall أو suspend كحالة revoked.

## الأخطاء

يحوّل adapter الأخطاء إلى `GithubAuthorizationFailure` دون تمرير body أو رسائل provider إلى UI أو logs. الحالات الأساسية تشمل: `BROKER_NOT_CONFIGURED`, `PROJECT_MISMATCH`, `HTTP_UNAUTHORIZED`, `HTTP_FORBIDDEN`, `HTTP_NOT_FOUND`, `INVALID_STATE`, `SESSION_EXPIRED`, `SESSION_REVOKED`, `RATE_LIMITED`, و`PROVIDER_UNAVAILABLE`.

فشل HTTP أو غياب الإعداد لا يتحول إلى نجاح، ولا يفعّل Fake Broker، ولا يعيد PAT قديمًا. انتهاء الاتصال يوقف القراءة والكتابة حتى نجاح refresh خادمي صريح.

## Fake Broker والاختبارات

`MultiTenantGithubFakeBroker` موجود داخل `app/src/test` فقط. كل instance يمثل مستخدم AIRI موثقًا واحدًا وتُحقن له fixtures اصطناعية. لا يوجد له binding في `ServiceLocator` أو `src/main`، ولا يمكن تفعيله تلقائيًا عند فشل HTTP. السلوك الافتراضي للإنتاج ما زال `NotConfiguredGithubBrokerClient`.

تثبت اختبارات Fake Broker أن:

- المستخدم A لا يرى اتصال B.
- تغيير `projectId` لا ينقل ملكية الاتصال.
- تغيير `airiConnectionId` إلى اتصال غير معروف يرفض الطلب.
- تغيير `repositoryId` إلى ID خارج grant يرفض الاختيار.
- handoff لمرة واحدة ولا يقبل replay.
- revoke يمنع القراءة اللاحقة.

هذه الاختبارات تثبت عقد Android وFake Broker فقط، ولا تعتبر دليلًا على عزل Broker production أو صحة GitHub App حتى تُنفذ الخدمة الخادمية وتُختبر في staging.
