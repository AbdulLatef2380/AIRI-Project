# Box connector

## الحالة

تم إنشاء adapter فعلي لـBox وربطه بالكتالوج وBootstrap ومدير التفويض ومسار AgentLoop. الحالة الحالية **قراءة metadata والبحث فقط**.

Box ليس مثل Dropbox في المصادقة. وثائق Box الرسمية تنص على أن تبادل authorization code يحتاج `client_secret`. لذلك لا يضع AIRI السر داخل APK ولا ينفذ التبادل من Android. يجب أن يتم التبادل عبر HTTPS broker مملوك لـAIRI ويحفظ Box client secret على الخادم.

## الصلاحية الحالية

يطلب AIRI نطاق Box الرسمي:

```text
root_readonly
```

وتستخدم الأفعال التالية واجهات Box الرسمية:

- `box_account_read` / `status` → `GET /2.0/users/me`
- `box_files_list` → `GET /2.0/folders/{folder_id}/items`
- `box_files_search` → `GET /2.0/search`
- `box_file_metadata` → `GET /2.0/files/{file_id}`

لم أضف download أو upload أو delete أو move. توضح وثائق Box أن تنزيل المحتوى يحتاج `root_readwrite`، وهو نطاق كتابة أوسع من المطلوب للقراءة؛ لذلك لا يتم طلبه أو الإعلان عنه للوكيل.

## دورة المصادقة

1. ينشئ AIRI `state` أحادي الاستخدام ويربطه بموصل Box والنطاق `root_readonly`.
2. يفتح صفحة Box الرسمية باستخدام `client_id` و`redirect_uri` و`state`.
3. يوافق المستخدم بحسابه هو على الصلاحية المطلوبة.
4. يعيد Box callback إلى `airi://oauth/callback`.
5. يتحقق AIRI من `state` مرة واحدة ومن النطاق المتوقع.
6. يرسل AIRI authorization code إلى broker عبر HTTPS فقط.
7. ينفذ broker تبادل Box server-to-server باستخدام `client_secret` المخزن على الخادم.
8. يعيد broker access token وrefresh token وscope إلى التطبيق عبر عقد موثق ومقيد.
9. يخزن AIRI التوكنات والنطاقات في `EncryptedSharedPreferences`.
10. لا يعلن runtime حالة الاتصال إلا بعد نجاح `GET /users/me`.
11. قبل كل فعل، يتحقق runtime من وجود `root_readonly` في grants المحفوظة.

## عقد broker المطلوب

يستخدم التطبيق:

```text
POST {BOX_OAUTH_BROKER_URL}/v1/connectors/box/oauth/callback
POST {BOX_OAUTH_BROKER_URL}/v1/connectors/box/oauth/refresh
```

والـcallback يرسل form fields:

```text
provider=box
code=<short-lived Box authorization code>
redirect_uri=airi://oauth/callback
requested_scope=root_readonly
```

يجب ألا يسجل broker code أو tokens في السجلات، ويجب أن يعيد JSON محدوداً مثل:

```json
{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 3600,
  "scope": "root_readonly"
}
```

## إعداد Android

يُمرر فقط `client_id` العام وعنوان broker الآمن:

```bash
BOX_CLIENT_ID=<Box-Client-ID> \
BOX_OAUTH_BROKER_URL=https://broker.example.com \
./gradlew assembleDebug
```

أو باستخدام Gradle properties:

```bash
./gradlew assembleDebug \
  -PboxClientId=<Box-Client-ID> \
  -PboxOAuthBrokerUrl=https://broker.example.com
```

لا تضع `client_secret` في `gradle.properties` أو APK أو مستودع Android.

## المصادر الرسمية

- [Box OAuth 2.0 authentication](https://developer.box.com/guides/authentication/oauth2)
- [Box OAuth 2.0 without SDKs](https://developer.box.com/guides/authentication/oauth2/without-sdk)
- [Box OAuth setup](https://developer.box.com/guides/authentication/oauth2/oauth2-setup)
- [Box token endpoint](https://developer.box.com/reference/post-oauth2-token)
- [Box API scopes](https://developer.box.com/guides/api-calls/permissions-and-errors/scopes)
- [List folder items](https://developer.box.com/reference/get-folders-id-items)
- [Search content](https://developer.box.com/reference/get-search)

## الاختبار

اختبارات JVM تغطي catalog scope وOAuth state أحادي الاستخدام وتسجيل adapter. الاختبار الحي لا يمكن اعتباره مكتملاً حتى يتوفر Box App مسجل وbroker HTTPS فعلي؛ لأن Box يفرض client secret في token exchange وفق وثائقه الرسمية.
