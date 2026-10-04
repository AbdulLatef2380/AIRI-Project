# خطة توحيد موصلات AIRI ومسارات المصادقة

## فهم الصورة والتوجيه

التصميم المطلوب هو شاشة تفاصيل بسيطة ومركزة لكل موصل:

- شعار واسم الموصل.
- وصف مختصر لما يفعله.
- الجهة المالكة والموقع وسياسة الخصوصية.
- الصلاحيات التي سيطلبها AIRI.
- حالة حقيقية، لا تتغير إلى Connected بمجرد ضغط الزر.
- زر اتصال كبير في الأسفل.
- الضغط على الاتصال يختار طريقة المصادقة المعلنة للموصل.
- بعد اكتمال المصادقة والتخزين الآمن وفحص الصحة فقط يظهر الموصل كـ Connected / Healthy.

العقد الأمني هو:

```text
Connect
  -> Resolve AuthStrategy
  -> Official provider authorization
  -> state + PKCE validation when OAuth native flow applies
  -> token / credential exchange
  -> encrypted per-user storage
  -> capability verification
  -> healthy connector
  -> tool exposure
  -> real execution
```

لا توجد حسابات أو tokens خاصة بالمطور داخل AIRI، ولا يجب وضع Client Secret داخل APK.

## الجرد الحالي

يوجد في `OfficialConnectorCatalog` حالياً **35 تعريف خدمة**، إضافة إلى **9 معرفات runtime خارج الكتالوج** (قدرات محلية/نظامية وAPI وأتمتة) التي يثبتها `ConnectorBootstrap`. النطاق المعتمد في [جرد الموصلات وخطة الإغلاق](CONNECTOR_INVENTORY_AND_3_PHASE_PLAN_AR.md) هو **44 مدخلاً تشغيلياً**؛ وهي ليست كلها تطبيقات OAuth مستقلة.

الحالات الحالية في الكتالوج:

- 11 تعريفاً `PARTIAL`: Google Gmail/Calendar/Drive، Microsoft Outlook/Calendar/OneDrive/Teams، GitHub، Telegram، Notion، Zapier.
- 24 تعريفاً `COMING_SOON`: معلومات وصفية فقط ولا يجوز أن تعرض اتصالاً تنفيذياً.
- الموصلات المحلية/النظامية لا تستخدم OAuth؛ تستخدم أذونات Android أو لا تحتاج مصادقة.

## المرحلة الأولى — تم البدء بها الآن: عقد موحد ومنع الاتصال الوهمي

### المنجز

1. إضافة `ConnectorAuthStrategy` و`ConnectorAuthStrategies` كمصدر قرار مركزي.
2. دعم الاستراتيجيات التالية:
   - OAuth 2 native + PKCE.
   - API Key.
   - Personal Access Token.
   - Device Code.
   - Android local permission.
   - MCP configuration/authorization.
   - Webhook.
   - No authentication.
   - Coming Soon.
3. إزالة قوائم المزودين الثابتة من `ConnectorsScreen` و`ConnectorDetailsScreen`.
4. جعل زر الاتصال يعتمد على الاستراتيجية المعلنة، لا على اسم `github` أو `telegram` أو `google`.
5. إظهار وصف مسار المصادقة والصلاحيات المطلوبة في شاشة التفاصيل.
6. منع العناصر `COMING_SOON` من بدء مصادقة أو إظهار اتصال وهمي.
7. إضافة اختبار يغطي جميع تعريفات الكتالوج ويثبت عدم وجود استراتيجية مجهولة.
8. الحفاظ على مسارات Google وGitHub وTelegram الحالية كمنفذات provider-specific تحت العقد الموحد، إلى أن يتم نقلها إلى مدير التفويض العام.

### معيار نجاح المرحلة الأولى

- لا يوجد `if connector == Provider` داخل قرار الاتصال في واجهة الموصلات.
- كل تعريف يملك AuthStrategy قابلة للتفسير.
- لا تظهر `Connected` إلا من حالة connector runtime الفعلية.
- الموصل الوصفي لا يمكنه الوصول إلى `ConnectorRegistry.connect`.

## ربط الأفعال بنطاقات المزود — منفذ جزئياً على `main`

يُعلن كل فعل في `ConnectorAgentAction.providerGrants`. قيمة `OAUTH_SCOPE` وحدها تدخل في OAuth request؛ بقية الأنواع تصف إذن PAT أو قدرة تكامل أو صلاحية bot/webhook ولا تُمرر كسلاسل OAuth. يقوم `ConnectorOAuthScopeResolver` بجمع النطاقات من الأفعال المسموح بها بملفات السطح المختارة؛ إذا لم يوجد فعل قراءة ممنوح، لا يبدأ OAuth. لا تُضاف scopes أفعال الكتابة التي ما زالت تتطلب تأكيداً. ينفذ كل من جسر الأدوات وruntime enforcement ملف الوصول؛ وربط provider scope ليس بديلاً عن هذا الإنفاذ.

| السطح/الفعل | متطلب المزود | حد التنفيذ |
|---|---|---|
| `google_gmail`: `gmail_list`, `gmail_read` | `gmail.readonly` | طلب النطاق من Google Identity فقط عندما يكون فعل Gmail مسموحاً؛ token/نطاقاته تبقى بذاكرة العملية ولا تحفظ على القرص. |
| `google_calendar`: `calendar_list` | `calendar.events.owned.readonly` | قراءة أحداث تقاويم المستخدم المملوكة؛ scopes من العقد الحالي لا تتيح كتابة التقويم. |
| `google_drive`: `drive_search` | `drive.metadata.readonly` | البحث في metadata فقط؛ لا تنزيل محتوى الملفات. |
| Microsoft Outlook / Calendar / OneDrive / Teams | `Mail.ReadBasic`, `Calendars.ReadBasic`, `Files.Read`, `Team.ReadBasic.All`; مع OIDC و`User.Read` للـbootstrap | تُضمّن فقط scopes الأفعال التي تسمح بها profiles الفعلية في authorization URL والـtoken exchange؛ لا نطلب `Mail.Read` أو `Calendars.Read` الأوسع. Teams الحالي metadata للفرق المنضم إليها فقط، وحساب Microsoft الشخصي غير مدعوم لنداء joined teams. |
| GitHub | `Metadata: read`, `Issues: read`, `Contents: read`, `Pull requests: read` حيث تنطبق | هذه fine-grained PAT permissions وليست OAuth scopes؛ الفعل `search_code` موسوم endpoint-specific. يذكر GitHub ترويسة `X-Accepted-GitHub-Permissions` للمساعدة على التشخيص، لكن AIRI لا يقرأها أو يثبت أذونات PAT المسجلة لدى المزود بعد؛ يلزم تحقق مزود فعلي. |
| Notion | `Read content` للأدوات القرائية؛ `Insert content` لـ`create_page`، مع مشاركة الصفحة/قاعدة البيانات مع التكامل | ليست OAuth scopes؛ إنشاء الصفحة يبقى تحت confirmation منفصل. |
| Telegram | Bot token صالح وقدرة البوت على الوصول إلى المحادثة/التحديث | ليست OAuth scopes؛ polling عبر `getUpdates` لا يجتمع مع webhook لنفس البوت. |
| Zapier | OAuth `zap` لقائمة Zaps؛ REST Hook URL لا يُعامل كـscope | `trigger_zap` غير مكشوف للوكيل حتى يتوفر سجل آمن لعناوين المزود المصرح بها؛ استدعاؤه مباشرة يفشل مغلقاً. `send_webhook` مخصص لاختبار يدوي بدأه المستخدم ومقيد بـHTTPS على `hooks.zapier.com`. `pause_zap` و`resume_zap` غير معلنين لعدم وجود تنفيذ حقيقي. |

مصادر التحقق: [Google Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)، [Google Calendar auth](https://developers.google.com/workspace/calendar/api/auth)، [Google Drive auth](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)، [Microsoft messages](https://learn.microsoft.com/en-us/graph/api/user-list-messages?view=graph-rest-1.0)، [Microsoft calendar events](https://learn.microsoft.com/en-us/graph/api/user-list-events?view=graph-rest-1.0)، [Microsoft OneDrive list children](https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0)، [Microsoft joined teams](https://learn.microsoft.com/en-us/graph/api/user-list-joinedteams?view=graph-rest-1.0)، [GitHub PAT permissions](https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens)، [Notion capabilities](https://developers.notion.com/reference/capabilities)، [Telegram Bot API](https://core.telegram.org/bots/api)، [Zapier OAuth scopes](https://docs.zapier.com/powered-by-zapier/api-reference/oauth-scopes).

هذا mapping ليس إغلاقاً للمرحلة الثانية: يلزم اختبار مزود فعلي (consent، callback، رفض، refresh/revoke، توثيق app registration) وتغطية كل runtimes والأخطاء؛ لم تُغيّر حالة catalog إلى `READY`.

## المرحلة الثانية — تفعيل التدفقات الرسمية على دفعات

### الدفعة A: الموصلات الحية الحالية

- Google: فصل هوية تسجيل الدخول عن Data Authorization، ثم توحيد callback والتحقق والتخزين ضمن Authorization Manager.
- GitHub: الانتقال اختيارياً من PAT إلى OAuth + PKCE إذا كان نموذج GitHub native client مناسباً، مع إبقاء PAT كمسار صريح مؤقتاً.
- Telegram: شاشة API key/Bot Token آمنة، فحص `getMe`، ثم تخزين مشفر وعدم اعتبار الإدخال اتصالاً قبل نجاح الفحص.
- Notion: توثيق هل المسار API token أم OAuth، ثم عدم خلط MCP transport مع credential strategy.
- Zapier: إكمال Authorization Code + PKCE إن كان مدعوماً، والتحقق من callback وtoken exchange وrevoke.

### الدفعة B: مزودو OAuth ذوو الأولوية

- Microsoft Outlook / Calendar / OneDrive / Teams / SharePoint / To Do عبر Microsoft identity platform.
- Slack وDiscord.
- Dropbox وBox.
- Trello وAsana.

كل موصل في هذه الدفعة يحتاج `ProviderAuthorizationAdapter` حقيقياً، redirect مسجلاً، scopes موثقة، اختبار cancel/reject/replay/revoke، وhealth check read-only.

### الدفعة C: API/PAT/MCP/Webhook

- GitLab، Bitbucket، Jira، Linear.
- ClickUp، Monday، Todoist، Airtable.
- MCP servers وN8n وIFTTT.
- أي موصل API Key أو Webhook يجب أن يستخدم نموذج إدخال آمن موحداً، لا حقلاً مكشوفاً في شاشة عامة.

## عقد التنفيذ المستهدف

```text
ConnectorDefinition
  -> ConnectorAuthStrategy
  -> ConnectorAuthorizationManager.start()
  -> AuthorizationEffect (browser / intent / secure form / device code)
  -> ConnectorAuthorizationManager.complete()
  -> Secure Credential Store
  -> ConnectorHealthVerifier
  -> ConnectorRegistry.connect()
  -> Capability Registry
```

`ConnectorAuthorizationManager` يجب أن يعيد نتائج typed مثل:

- `Started`.
- `AwaitingProviderCallback`.
- `AwaitingUserCredential`.
- `Authorized`.
- `Cancelled`.
- `Denied`.
- `StateMismatch`.
- `TokenExchangeFailed`.
- `SecureStorageUnavailable`.
- `HealthCheckFailed`.

## اختبارات إلزامية لكل موصل خارجي

1. بدء الاتصال.
2. إلغاء المستخدم.
3. رفض الصلاحية.
4. `state` غير صحيح.
5. إعادة استخدام callback قديم.
6. PKCE verifier مفقود أو غير مطابق.
7. فشل token exchange.
8. فشل التخزين الآمن.
9. token منتهي أو revoke.
10. health check ناجح وفاشل.
11. عدم عرض الأدوات قبل الجاهزية.
12. عدم تسريب token في logs أو telemetry أو UI.

## ملاحظة تنفيذية

لا يمكن تفعيل OAuth حقيقي لكل الموصلات الوصفية دفعة واحدة دون تسجيل تطبيقات العميل لدى المزودين وتحديد redirect URIs وسياسات scopes. لذلك المرحلة الأولى توحد القرار والواجهة والعقد، والمرحلة الثانية تفعّل المزودين تدريجياً مع evidence حقيقية، بدلاً من رفع كل العناصر إلى Connected شكلياً.
