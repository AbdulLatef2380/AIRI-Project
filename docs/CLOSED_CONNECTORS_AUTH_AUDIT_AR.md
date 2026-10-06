# تدقيق مطابقة المصادقة للموصلات المغلقة

**التاريخ:** 2026-10-06  
**الفرع:** `main`  
**النطاق:** الموصلات التي تحولت من `COMING_SOON` إلى `PARTIAL` أو أضيفت إلى `liveCatalogIds`، مع مراجعة مسارات OAuth وPAT/API key/MCP والتخزين والإلغاء ومنح الصلاحيات.

## النتيجة التنفيذية

الحالة العامة هي **مطابقة جزئية مع حواجز صحيحة، وليست دليلاً على اعتماد إنتاجي حي لكل الموصلات**.

الفحوص الساكنة لدورة الحياة نجحت بالكامل: **13/13**. كما أن المسار المركزي يفرض access profile قبل بدء المصادقة، ويمنع `COMING_SOON` من فتح المتصفح، ويمرر الاتصال عبر health check قبل إعلان `Ready`، ويستخدم تخزيناً مشفراً لـMicrosoft وZapier وProviderTokenConnector.

لكن لا يمكن إعلان جميع الموصلات المغلقة مطابقة بالكامل لمعايير المصادقة للأسباب التالية:

1. **Zapier ليس جاهزاً فعلياً لهذا البناء:** `CLIENT_ID` ما زال `ZAPIER_CLIENT_ID_PLACEHOLDER`، و`ReleaseScopePolicy.externalAutomationIntegrationsEnabled` يمنع بدء OAuth والتنفيذ في الإصدار الحالي. لذلك هو موثق كـ`PARTIAL` لكنه ليس مسار نشر فعلياً حتى يتم حقن إعداد OAuth حقيقي واختبار callback حي.
2. **الاختبارات المتاحة تثبت البنية، لا الاعتماد الحي:** Gradle لم يبدأ الاختبارات لأن Android SDK غير موجود في Sandbox (`SDK location not found`). لذلك لم يتم إثبات compilation أو unit tests على هذا الجهاز.
3. توجد فجوة تغطية: الاختبارات الحالية تثبت عقود المصادقة للمسارات الرئيسية وموصلات التوكن السبعة، لكنها لا توفر اختبارات HTTP معزولة لكل adapter لإثبات `401/403/429/5xx`, token expiry, callback replay, scope change, disconnect cleanup وhealth response.

## المصفوفة

| المجموعة | الأسطح | نمط المصادقة | التخزين/الإلغاء | الحواجز الموجودة | الحكم |
|---|---|---|---|---|---|
| Google | Gmail، Calendar، Drive، Docs، Sheets، Contacts، Tasks | Google Identity/OAuth2 | `GoogleAuthService`، وdisconnect يمسح data token وحالة التكامل | scope resolver، surface grants، consent قبل البيانات، منع أفعال الكتابة الحساسة | **مطابق بنيوياً؛ يحتاج اختبار provider حي/health أقوى** |
| Microsoft Graph | Outlook، Calendar، OneDrive، Teams، SharePoint، To Do | OAuth2 PKCE | `ConnectorAuthManager` عبر `EncryptedSharedPreferences`؛ refresh rotation؛ revoke يمسح access/refresh/expiry/scopes | state + PKCE، exact scope set، scope-change rejection، surface grants، Graph health | **مطابق بنيوياً؛ SharePoint يستخدم `Sites.Read.All` الواسع ويحتاج موافقة واضحة واختبار tenant** |
| GitHub | GitHub | PAT | `ConnectorAuthManager`/secure storage، disconnect يمسح PAT | health `/user`، write خلف durable approval وcontinuation/idempotency | **مطابق بنيوياً؛ يلزم اختبار صلاحيات PAT الفعلية و401/403** |
| Telegram | Telegram | Bot API token/API key | `SecureStorage`، disconnect يمسح token وحالة الاتصال | health `getMe`، write action معلنة كـWRITE، حدود chat/text | **مطابق بنيوياً؛ يلزم اختبار token rejection/rate limit وعدم تسريب token في logs** |
| Notion | Notion | Integration token/MCP configuration | `SecureStorage`، manager يمسح integration token عند disconnect | handshake `/v1/users/me`، read/write tools مفصولة في MCP | **مطابق جزئياً؛ يلزم اختبار teardown لمسح cache والـtoken، وحدود الكتابة/confirmation** |
| Zapier | Zapier | OAuth2 PKCE | `ConnectorAuthManager` مشفر وrevoke موجود | state + PKCE، exact scope set، fixed HTTPS، redirects disabled، release gate | **غير جاهز للنشر: client ID placeholder وOAuth معطل فعلياً** |
| Provider token batch | GitLab، Linear، Slack، Discord، Asana، Todoist، Figma | PAT/API key/OAuth access token حسب المزود | `ConnectorAuthManager` المشفر، disconnect يمسح credential ويضع explicit disconnect | identity health قبل connected، bounded reads، provider-specific headers، 401/403/429 mapping | **مطابق بنيوياً؛ يحتاج HTTP contract tests وprovider evidence** |
| SharePoint | SharePoint | OAuth2 PKCE عبر Microsoft Graph | نفس Microsoft Graph | surface مستقل، `Sites.Read.All`، root site/files read-only، `site_id` إلزامي وحدود 1..50 | **مطابق بنيوياً كـread-only slice؛ ليس دعماً كاملاً لـSharePoint** |

## معايير الفحص ونتيجتها

| المعيار | النتيجة | الدليل |
|---|---|---|
| عدم بدء مصادقة لـComing Soon | ناجح | `ConnectorAuthStrategy` يعيد `COMING_SOON` غير قابل للتنفيذ، و`ConnectorAuthorizationManager` يعيد typed failure |
| access profile قبل consent/credential | ناجح بنيوياً | `authorizationSurfaceIds` و`AccessProfileStore` في المدير المركزي |
| OAuth state وPKCE | موجود لـMicrosoft وZapier | `OAuthStateRegistry.issuePkce`، واستهلاك state مرة واحدة في callback |
| منع replay وتغير scopes | موجود | `consumeRequest`، و`oauth_scope_set_changed` لـMicrosoft/Zapier |
| secure token storage | موجود لـMicrosoft/Zapier/ProviderToken | `EncryptedSharedPreferences` عبر `ConnectorAuthManager` |
| health قبل Ready | موجود بنيوياً | `connectAndVerify` لا يعيد Ready إلا مع `connected && healthy` |
| الإلغاء والتنظيف | موجود جزئياً حسب adapter | Microsoft/Zapier/ProviderToken واضح؛ Google/GitHub/Telegram/Notion عبر خدمات التخزين الخاصة |
| فصل القراءة والكتابة | موجود | access profiles، `WRITE`/`DESTRUCTIVE`، وconfirmation للعمليات الحساسة |
| منع تسريب الأسرار | جيد في المواضع المفحوصة | لا توجد سجلات token مباشرة؛ Telegram يستبدل token في رسالة الخطأ |
| اختبار build/unit | غير متاح في Sandbox | Android SDK غير مثبت، وGradle توقف أثناء configuration |
| اختبار provider حي | غير منفذ | لا توجد credentials/tenant/sandbox في نطاق المراجعة |

## ملاحظات تصحيحية مهمة

### Zapier

هذا هو العائق الصريح. وجود adapter وOAuth code لا يكفي؛ `CLIENT_ID` placeholder يجعل `isOAuthConfigured()` يعيد false، كما أن release policy تمنع `begin` و`completeOAuth` و`connect` و`execute`. يجب عدم تقديم Zapier للمستخدم كـconnected أو deployable قبل توفير client ID حقيقي عبر BuildConfig/secret backend، تثبيت redirect URI، واختبار consent وtoken exchange وhealth `/user`.

### Microsoft Graph وSharePoint

المسار قوي من ناحية state/PKCE وrefresh rotation، لكن SharePoint يستخدم `Sites.Read.All`، وهي صلاحية واسعة على مستوى المؤسسة. يجب إبقاؤها خلف permission preview واضح، وعدم إضافة `Sites.ReadWrite.All` أو أفعال كتابة. كما أن نجاح health `/me` لا يثبت أن tenant يسمح فعلياً بـSharePoint؛ يجب اختبار `/sites/root` وقراءة drive في tenant اختبار.

### Google

المسار يعتمد على Google Identity وdata authorization، ويفصل consent عن مجرد تسجيل الدخول. لكن `GoogleConnector.connect()` يتحقق من وجود data access token وحالة الحساب أكثر من تحققه من endpoint provider فعلي. يلزم اختبار health/expired token لكل surface قبل اعتبار الاعتماد production evidence.

### Notion

المصادقة تعتمد على Integration Token وليس OAuth browser flow. handshake يتحقق من `/v1/users/me`، لكن مسار cache داخل `NotionMcpConnector` يحتاج اختباراً صريحاً يثبت أن `disconnect` لا يترك token صالحاً في الذاكرة بعد teardown، وأن عمليات الكتابة تبقى خلف access profile وconfirmation.

## قرار التدقيق

- **Pass بنيوي:** Google، Microsoft Graph بما فيه SharePoint، GitHub، Telegram، ProviderToken batch.
- **Pass مشروط:** Notion، بسبب الحاجة لاختبار cache/teardown والكتابة.
- **Fail للنشر الحالي:** Zapier، بسبب placeholder client ID وrelease gate.
- **لم يتم تغيير كود عشوائياً في هذه المراجعة**؛ التقرير يسجل الفجوات التي يجب إغلاقها في دفعات منفصلة.

## الخطوات المطلوبة قبل إعلان المطابقة الكاملة

1. تشغيل `:app:testDebugUnitTest --tests 'com.airi.assistant.connector.*'` على CI أو جهاز يحوي Android SDK.
2. إضافة HTTP fake-server tests لكل adapter، مع تغطية success و401/403/429/5xx وtimeouts وmalformed responses.
3. إضافة OAuth tests لـMicrosoft/Zapier: state replay، state mismatch، code missing، scope mutation، token expiry، refresh rotation، invalid_grant.
4. توفير Zapier OAuth client حقيقي من secret backend، ثم تشغيل اختبار consent وcallback وhealth وعدم اعتماد placeholder.
5. إضافة اختبار Notion يثبت مسح cached token عند teardown/disconnect.
6. تنفيذ provider sandbox/tenant smoke tests قبل أي إعلان إنتاجي نهائي.
