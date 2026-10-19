# حالة المرحلة الثالثة — بوابة الجاهزية 3A

**التاريخ:** 2026-10-19
**المستودع:** `AbdulLatef2380/AIRI-Project`
**الفرع المسموح:** `main` فقط
**SHA الذي تمت مراجعته:** `67b83cbd` (`docs: define GitHub App broker phase three plan`)

## الحكم

المرحلة الثالثة **بدأت ولم تُغلق**. الموجود في Android هو أساس تفويض وجلسة typed، وليس تكامل GitHub OAuth حقيقيًا.

الحالة الحالية الصادقة:

```text
ANDROID_AUTHORIZATION_FOUNDATION = PRESENT
BROKER_WIRE_CONTRACT = NOT_APPROVED
BROKER_SERVICE = NOT_PRESENT
GITHUB_APP_STAGING = NOT_PROVEN
OAUTH_PROVIDER_INTEGRATION = BLOCKED
GITHUB_READ_PATH_VIA_BROKER = NOT_ENABLED
GITHUB_WRITE_PATH_VIA_BROKER = NOT_ENABLED
```

## الأدلة الموجودة في الشجرة

- `GithubBrokerClient` عقد مجال typed وليس HTTP contract.
- `NotConfiguredGithubBrokerClient` هو التنفيذ الافتراضي، ويفشل مغلقًا بحالة `BROKER_NOT_CONFIGURED`.
- `GithubAuthorizationCoordinator` يعالج state وPKCE والاستهلاك الأحادي وعزل المشروع.
- `GithubSessionStore` يستخدم التخزين المشفر الموجود، ويعزل namespace حسب المشروع.
- `IntegrationsScreen` يعرض حالات التفويض ولا ينشئ اتصالًا وهميًا.
- `GitHubConnector` ما زال يحتوي على مسار PAT/credential القديم؛ لم تتم إزالته أو توحيده مع Broker.

## الأدلة غير الموجودة

لم يثبت الفحص وجود:

- خدمة Broker أو Backend مستقل.
- GitHub App مسجلة للبيئة المستهدفة.
- callback HTTPS قابل للاختبار.
- تبادل authorization code server-side.
- handoff code فعلي أحادي الاستخدام.
- مخزن جلسات خادمي أو Secret Manager مربوط بالـBroker.
- contract/integration test مشترك بين Broker وAndroid.
- اختبار GitHub staging حقيقي.

## قرارات الحماية

1. لا يُضاف HTTP adapter إلى Android قبل اعتماد wire contract فعلي.
2. لا تُضاف endpoint paths أو JSON schemas أو أسماء حقول غير موثقة.
3. لا تُنقل client secret أو private key إلى APK أو Git أو السجلات.
4. لا تُفعّل أدوات GitHub القراءة أو الكتابة عبر OAuth قبل وجود جلسة Broker حقيقية.
5. لا يُستخدم PAT كـfallback تلقائي عند فشل OAuth.
6. لا تُعتبر اختبارات fake broker دليلًا على اتصال GitHub الحقيقي.
7. لا يُنفذ `push` أو `merge` ضمن هذه المرحلة دون موافقة مستقلة.

## بوابة الانتقال التالية

لا تنتقل AIRI إلى تنفيذ Broker أو HTTP adapter إلا بعد توفير واعتماد القيم العامة التالية خارج القيم الحساسة:

- Broker staging base URL.
- البيئة المستهدفة.
- GitHub App client ID أو slug.
- callback URI المسجل بدقة.
- wire contract المنشور أو مراجعة Broker التي تعتمد العقد.
- نوع handoff والـTTL وسياسة replay.
- هوية AIRI user/project التي يلتزم بها Broker.

لا تُطلب في هذه الوثيقة أي أسرار أو access tokens أو refresh tokens أو private keys.

## نتيجة المرحلة 3A

تم تثبيت الحالة والحدود على `main` دون تغيير سلوك Android ودون ادعاء نجاح OAuth. تبقى المرحلة محجوبة خارجيًا بحالة:

```text
BLOCKED_EXTERNAL_BROKER
```
