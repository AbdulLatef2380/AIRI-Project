# Dropbox connector

## الحالة

Dropbox أصبح موصلاً قابلاً للتنفيذ في AIRI عبر OAuth 2 authorization-code مع PKCE (`S256`). لا يطلب AIRI من المستخدم إدخال token أو client secret؛ المستخدم يوافق على صلاحيات تطبيق AIRI من صفحة Dropbox.

النسخة الحالية **قراءة فقط**:

| AIRI action | Dropbox endpoint | Scope |
|---|---|---|
| `dropbox_account_read` / `status` | `users/get_current_account` | `account_info.read` |
| `dropbox_files_list` | `files/list_folder` | `files.metadata.read` |
| `dropbox_files_search` | `files/search_v2` | `files.metadata.read` |
| `dropbox_file_metadata` | `files/get_metadata` | `files.metadata.read` |
| `dropbox_file_temporary_link` | `files/get_temporary_link` | `files.content.read` |

لا توجد أفعال حذف أو نقل أو رفع أو تعديل، ولا تُعلن أفعال كتابة للوكيل.

## إعداد Dropbox App مرة واحدة

1. أنشئ تطبيقاً واحداً في [Dropbox App Console](https://www.dropbox.com/developers/apps). لا يحتاج المستخدمون إلى إنشاء تطبيقات خاصة بهم.
2. اختر صلاحيات scoped access.
3. فعّل الصلاحيات التالية في Permissions:
   - `account_info.read`
   - `files.metadata.read`
   - `files.content.read`
4. أضف redirect URI المطابق تماماً:

   `airi://oauth/callback`

5. مرّر App key العام أثناء بناء AIRI، دون وضع أي client secret:

   ```bash
   DROPBOX_CLIENT_ID=<Dropbox-App-Key> ./gradlew assembleDebug
   ```

   أو:

   ```bash
   ./gradlew assembleDebug -PdropboxClientId=<Dropbox-App-Key>
   ```

## الدورة التشغيلية

1. يختار المستخدم Dropbox وسطح الصلاحيات.
2. AIRI ينشئ `state` و`code_verifier` عشوائيين ويربطهما بالموصل والنطاقات المطلوبة.
3. يفتح AIRI صفحة Dropbox مع `code_challenge` و`code_challenge_method=S256` و`token_access_type=offline`.
4. يعيد Dropbox callback إلى `airi://oauth/callback`.
5. يتحقق AIRI من `state` مرة واحدة وقبل أي تبادل للرمز.
6. يرسل AIRI authorization code و`code_verifier` وApp key العام إلى Dropbox token endpoint.
7. يخزن AIRI access token وrefresh token والنطاقات الممنوحة في `EncryptedSharedPreferences`.
8. لا تُعتبر الحالة متصلة إلا بعد استدعاء `users/get_current_account` بنجاح.
9. قبل كل فعل، يعيد runtime التحقق من أن scope الفعل موجود في grant المستخدم.
10. عند انتهاء access token، يستخدم refresh token. وعند الفصل يمسح token وconnection state.

## المصادر الرسمية

- [Dropbox OAuth](https://docs.dropboxapi.com/dropbox-api/docs/oauth)
- [Dropbox PKCE](https://dropbox.tech/developers/pkce--what-and-why-)
- [Dropbox customized OAuth scopes](https://dropbox.tech/developers/customizing-scopes-in-oauth-flow)
- [Dropbox HTTP API reference](https://www.dropbox.com/developers/documentation/http/documentation)

## الاختبار

اختبارات JVM تغطي ربط Dropbox بـPKCE وstate والنطاقات. الاختبار الحي يتطلب Dropbox App key وredirect URI مسجلاً في Dropbox App Console، ولا يمكن تنفيذه في بيئة المستودع الحالية دون بيانات تطبيق حقيقية.
