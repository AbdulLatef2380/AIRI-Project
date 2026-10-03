# Microsoft Vertical Slice في AIRI

## الحالة الدقيقة

تم تنفيذ أول مسار Microsoft فعلي داخل runtime لخدمتي **Outlook Mail** و**Outlook Calendar** فقط. هذا لا يعني أن Microsoft أو بقية الموصلات أصبحت جاهزة للإنتاج تلقائياً؛ المسار يبقى `Disconnected` ويفشل قبل فتح المتصفح ما لم يوفّر build إعدادات Entra الصحيحة.

الموصلان catalog IDs هما `microsoft_outlook` و`microsoft_calendar`، وكلاهما يستخدم descriptor واحداً بمعرف runtime canonical هو `microsoft_graph`. لا توجد aliases جديدة مثل `microsoft-outlook` أو `outlook_connector`.

## المسار المنفذ

```text
ConnectorDefinition
  -> ConnectorRuntimeDescriptor(microsoft_graph)
  -> ConnectorAuthStrategy(OAUTH2_PKCE)
  -> ConnectorAuthorizationManager
  -> OAuthConfiguration typed gate
  -> OAuthStateRegistry + PKCE S256
  -> Microsoft authorize endpoint
  -> airi://oauth/callback
  -> state consume + code verifier
  -> Microsoft token endpoint
  -> EncryptedSharedPreferences
  -> MicrosoftGraphTokenService
  -> MicrosoftGraphConnector
  -> /v1.0/me health check
  -> connected && healthy
  -> Outlook mail/calendar read tools
```

## الإعدادات

يقرأ البناء القيم التالية من Gradle property أو environment variable:

| القيمة | الغرض |
|---|---|
| `MICROSOFT_CLIENT_ID` أو `-PmicrosoftClientId` | Application/client ID العام من Entra؛ ليس secretاً | 
| `MICROSOFT_TENANT` أو `-PmicrosoftTenant` | الافتراضي `common` | 
| `MICROSOFT_OAUTH_ENABLED` أو `-PmicrosoftOAuthEnabled=true` | بوابة تفعيل المسار | 

لا يوجد `client_secret` داخل التطبيق، ولا يقبل الكود تشغيل OAuth عند وجود placeholder أو تعطيل البناء.

قبل الاختبار الحقيقي يجب تسجيل redirect URI المطابق تماماً لـ`airi://oauth/callback` في App Registration، ثم اعتماد delegated permissions الأقل صلاحية: `openid profile email offline_access User.Read Mail.Read Calendars.Read`. لا يتم طلب الكتابة أو صلاحيات المؤسسة الواسعة في هذه الشريحة.

## دورة token lifecycle

يُحفظ access token وrefresh token ووقت الانتهاء داخل `ConnectorAuthManager` الذي يستخدم `EncryptedSharedPreferences`. عند انتهاء access token، يستخدم `MicrosoftGraphTokenService` refresh token ويستبدل refresh token القديم بالجديد إذا أعاده المزود. عند `invalid_grant` يتم حذف الاعتمادين ويعود الموصل إلى حالة disconnected، بدلاً من إعادة محاولة غير منتهية.

عند `disconnect` أو logout تُحذف access/refresh tokens. لا يتم تسجيل token أو تمريره إلى واجهة المستخدم أو النموذج. إذا أعاد Graph حالة 401، يتم مسح الاعتماد وإرجاع `authorization_expired`.

## الأدوات المكشوفة للوكيل

- `microsoft.outlook_mail_read`: قراءة آخر الرسائل من الحساب الموقع.
- `microsoft.outlook_calendar_read`: قراءة الأحداث القادمة.
- `microsoft.status`: فحص حالة الاتصال.

كلها قراءة فقط في هذه الشريحة. عمليات الإرسال أو الإنشاء أو الحذف غير موجودة في `agentActions`، لذلك لا يمكن للنموذج طلبها عن طريق الخطأ.

## مصفوفة حالات الفشل

| الحالة | السلوك المتوقع |
|---|---|
| client ID مفقود | `oauth_missing_client_id` قبل فتح المتصفح |
| OAuth معطل للبناء | `oauth_disabled_for_build` |
| redirect غير صالح | `oauth_invalid_redirect_uri` |
| إلغاء تسجيل الدخول | callback غير مكتمل ولا يتم حفظ token |
| state مفقود/منتهي/معاد استخدامه | رفض callback عبر `oauth_state_invalid` أو `oauth_state_missing` |
| PKCE verifier غير صحيح | يفشل token exchange ولا يتم الاتصال |
| رفض الموافقة | فشل token exchange ولا يتم إعلان Connected |
| token منتهٍ | refresh rotation؛ وعند `invalid_grant` reconnect مطلوب |
| Graph 401 | حذف الاعتماد وإرجاع `authorization_expired` |
| Graph 5xx/network | `provider_error` قابل لإعادة المحاولة |
| missing scope | health check أو الطلب يفشل دون Connected + Healthy |
| adapter غير مسجل | `not_registered` |
| disconnect | حذف الاعتمادات وإغلاق الأدوات |

## ما تم التحقق منه

- `final_runtime_integration_audit.py`: ناجح.
- `verify_core_changes.py`: 96/96 ناجحة.
- `security_scan.py`: ناجح ولا توجد findings سرية.
- فحص عقد Microsoft الساكن: ناجح.
- Android compile: لم ينفذ في Sandbox الحالي لأن Android SDK غير مثبت؛ يجب تشغيله في CI أو جهاز تطوير Android.

## المصادر الرسمية

- [Microsoft authorization code flow with PKCE](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)
- [Microsoft Graph permissions reference](https://learn.microsoft.com/en-us/graph/permissions-reference)
- [Microsoft refresh tokens](https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens)
