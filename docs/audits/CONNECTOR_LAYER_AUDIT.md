# تدقيق طبقة الموصلات — `connector-detail-redesign`

## نطاق التدقيق

تمت مراجعة الفرع مقارنةً بـ`origin/main`، مع التركيز على مسارات التسجيل والكتالوج والمصادقة والفصل والوصول إلى Runtime. المعيار هنا هو **سلامة العقد والتدفق**، وليس عدد ملفات Compose. وجود Composable مشتركة ليس عيباً بذاته؛ العيب هو أن تتجاوز الواجهة تدفق مصادقة الموصل.

المصادر الرسمية التي استُخدمت للتحقق من نماذج المصادقة:

- Google OAuth: طلب scopes حسب الميزة ومقارنة scopes الممنوحة قبل تفعيلها: [1]
- GitHub OAuth: `state` وPKCE والتحقق من هوية المستخدم بعد تسجيل الدخول: [2]
- Microsoft Entra: redirect URI مسجل وPKCE، ومنع الأسرار في native/public clients: [3]
- Notion: اختيار الصفحات، redirect، code exchange، access/refresh token المرتبط بالمورد: [4]
- RFC 7636: حماية تطبيقات native من اعتراض authorization code بواسطة PKCE: [5]

## المسار الصحيح المثبت في الكود

`OfficialConnectorCatalog` يعرّف السطح وخصائصه، ثم `ConnectorRegistry.catalogMeta()` يحافظ على `catalogId` و`runtimeId` والـcapabilities. من شاشة التفاصيل يمر الإجراء غير المتصل عبر `AiriRoute.connectorAuthorization(connectorId)` إلى `IntegrationsScreen(initialConnectorId)`. يقوم `IntegrationsViewModel.beginConnectorAuthorization()` باستدعاء `ConnectorAuthorizationManager.begin(id)`، الذي يختار النتيجة الخاصة بالمزود: Google identity/consent، OAuth browser، credential dialog، MCP configuration، أو access profile.

أما `registry.connect()` فهو مرحلة تنفيذ/health بعد اكتمال التفويض، وليس بديلاً عن التفويض. كان في إعادة التصميم السابقة استدعاء مباشر من شاشة التفاصيل، وتمت إزالته. كما أن زر إعادة التحقق يمر الآن من مسار authorization الموجّه للمعرف بدلاً من اتصال عام عشوائي.

## مصفوفة الأسطح الـ43

> `PASS (contract)` يعني أن العقد والمسار المحلي مثبتان باختبارات/فحص ثابت. لا يعني نجاح Provider حي أو جهاز Android فعلي. `UNVERIFIED` يعني أن التحقق الخارجي لم يُنفذ في هذه البيئة.

| Connector ID | Type | Auth Strategy | Registration | Connect Route | Disconnect/Refresh | Runtime/Adapter | Regression Tests | Final State |
|---|---|---|---|---|---|---|---|---|
| google_gmail | APP / OAuth | Google OAuth + surface scope | Catalog → google | Targeted Google identity/consent | Google surface disconnect / reauth | google adapter | Contract + Google scope tests | PASS (contract); live UNVERIFIED |
| google_calendar | APP / OAuth | Google OAuth + calendar scope | Catalog → google | Targeted Google identity/consent | Surface disconnect / reauth | google adapter | Contract + Google scope tests | PASS (contract); live UNVERIFIED |
| google_drive | APP / OAuth | Google OAuth + Drive scope | Catalog → google | Targeted Google identity/consent | Surface disconnect / reauth | google adapter | Contract + Google scope tests | PASS (contract); live UNVERIFIED |
| google_docs | APP / OAuth | Google OAuth + Docs scope | Catalog → google | Targeted Google identity/consent | Surface disconnect / reauth | google adapter | Catalog contract | PASS (contract); live UNVERIFIED |
| google_sheets | APP / OAuth | Google OAuth + Sheets scope | Catalog → google | Targeted Google identity/consent | Surface disconnect / reauth | google adapter | Catalog contract | PASS (contract); live UNVERIFIED |
| google_contacts | APP / OAuth | Google OAuth + People scope | Catalog → google | Targeted Google identity/consent | Surface disconnect / reauth | google adapter | Catalog contract | PASS (contract); live UNVERIFIED |
| google_tasks | APP / OAuth | Google OAuth + Tasks scope | Catalog → google | Targeted Google identity/consent | Surface disconnect / reauth | google adapter | Catalog contract | PASS (contract); live UNVERIFIED |
| google_meet | APP / OAuth | OAuth declared, adapter coming soon | Catalog | Blocked by rollout/readiness | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| microsoft_outlook | APP / OAuth | Entra OAuth + action scopes + PKCE | Catalog → microsoft_graph | Targeted OAuth manager | Token rotation / revoke / reauth | Microsoft Graph adapter | OAuth lifecycle + provider contract | PASS (contract); live UNVERIFIED |
| microsoft_calendar | APP / OAuth | Entra OAuth + calendar scope + PKCE | Catalog → microsoft_graph | Targeted OAuth manager | Token rotation / revoke / reauth | Microsoft Graph adapter | OAuth lifecycle + provider contract | PASS (contract); live UNVERIFIED |
| microsoft_onedrive | APP / OAuth | Entra OAuth + Files.Read + PKCE | Catalog → microsoft_graph | Targeted OAuth manager | Token rotation / revoke / reauth | Microsoft Graph adapter | Provider contract | PASS (contract); live UNVERIFIED |
| microsoft_teams | APP / OAuth | Entra OAuth + Team.ReadBasic.All + PKCE | Catalog → microsoft_graph | Targeted OAuth manager | Token rotation / revoke / reauth | Microsoft Graph adapter | Provider contract | PASS (contract); live UNVERIFIED |
| microsoft_sharepoint | APP / OAuth | Entra OAuth + Sites.Read + PKCE | Catalog → microsoft_graph | Targeted OAuth manager | Token rotation / revoke / reauth | Contract-only rollout | Provider contract | PASS (blocked until adapter) |
| microsoft_todo | APP / OAuth | Entra OAuth + Tasks.Read + PKCE | Catalog → microsoft_graph | Targeted OAuth manager | Token rotation / revoke / reauth | Microsoft Graph adapter/contract | Provider contract | PASS (contract); live UNVERIFIED |
| github | APP / token | Personal access token | Catalog + Bootstrap | Targeted credential flow | Credential clear + runtime invalidation / reauth | GitHub adapter | GitHub credential/mutation tests | PASS (contract); live UNVERIFIED |
| gitlab | APP / token | Personal access token | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| figma | APP / token | Personal access token | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| jira | APP / OAuth | Atlassian OAuth + API declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| linear | APP / token | API key | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| slack | APP / token | Provider access token/API key | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| discord | APP / token | Bot/API token | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| telegram | APP / token | Bot API token | Catalog + Bootstrap | Targeted Telegram credential flow | Secure token clear + runtime invalidation | Telegram adapter | Telegram connector contract | PASS (contract); live UNVERIFIED |
| notion | APP / OAuth/MCP | Notion public OAuth or MCP config | Catalog → notion runtime | Targeted Notion/MCP flow | Token/config clear + session invalidation | Notion adapter | Provider contract + MCP tests | PASS (contract); live UNVERIFIED |
| trello | APP / OAuth | OAuth declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| asana | APP / token | Personal access token | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| clickup | APP / OAuth | OAuth + API declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| monday | APP / OAuth | OAuth + API declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| todoist | APP / token | API token | Catalog | Targeted provider-token flow | Credential clear via auth manager | Contract-only rollout | Provider adapter contract | PASS (blocked until adapter) |
| dropbox | APP / OAuth | OAuth declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| box | APP / OAuth | OAuth declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| canva | APP / OAuth | OAuth declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| airtable | APP / OAuth | OAuth + API declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| zapier | AUTOMATION / OAuth | Confidential server-side OAuth broker | Catalog + Bootstrap | Targeted Zapier manager with explicit scopes | Revoke/clear + reauth; external automation remains gated | Zapier adapter | Zapier policy/contract tests | PASS (contract); live UNVERIFIED |
| zoom | APP / OAuth | OAuth declared | Catalog | Blocked as coming soon | No connection advertised | No executable adapter | Rollout contract | PASS (blocked honestly) |
| remote_llm | API / internal runtime | Provider key/config | Bootstrap | Provider-specific config | Credential clear / provider refresh | Remote LLM adapter | Stability/contract tests | PASS (contract); live UNVERIFIED |
| android_intent | LOCAL_DEVICE_CAPABILITY | Android intent, no OAuth | Bootstrap | Local runtime gate | Local state reset | Android Intent adapter | Device action policy | PASS (contract); device UNVERIFIED |
| voice_mtmd | LOCAL_DEVICE_CAPABILITY | Local backend/permission | Bootstrap | Local runtime gate | Local backend release | Voice adapter | Contract coverage | PASS (contract); device UNVERIFIED |
| clipboard | LOCAL_DEVICE_CAPABILITY | Local Android surface, no OAuth | Bootstrap | Local runtime gate | Local state | Clipboard adapter | Contract coverage | PASS (contract); device UNVERIFIED |
| device_apps | LOCAL_DEVICE_CAPABILITY | Local Android surface, no OAuth | Bootstrap | Local runtime gate | Local state | Device apps adapter | Contract coverage | PASS (contract); device UNVERIFIED |
| contacts | LOCAL_DEVICE_CAPABILITY | Android READ_CONTACTS permission | Bootstrap | Permission request then connect | Permission recheck / local state | Contacts adapter | Permission contract | PASS (contract); device UNVERIFIED |
| system_info | INTERNAL_RUNTIME_ADAPTER | No user auth | Bootstrap | Ready/health state | No external revoke | System info adapter | Runtime contract | PASS (contract); device UNVERIFIED |
| ifttt | AUTOMATION / token | Webhook/API key | Bootstrap | Targeted credential/config flow | Credential clear + runtime invalidation | IFTTT adapter | IFTTT admission policy | PASS (contract); live UNVERIFIED |
| n8n | AUTOMATION / webhook | Webhook URL + health check | Bootstrap | Targeted webhook configuration | Clear URL + disable session | n8n adapter | Webhook policy/contract | PASS (contract); live UNVERIFIED |

## العيوب المثبتة والإصلاحات

| المشكلة | الملف والسطر | الدليل | السبب الجذري | الإصلاح | الاختبار والنتيجة | المتبقي |
|---|---|---|---|---|---|---|
| زر شاشة التفاصيل كان يستدعي `viewModel.connect()` مباشرة | `ConnectorDetailsScreen.kt`، نسخة الالتزام `6160cc6f` | Android CI وDeep Audit أظهرا أيضاً أخطاء ترجمة في نفس الملف؛ المراجعة الثابتة أثبتت أن المسار يتجاوز `ConnectorAuthorizationManager` | خلط تنفيذ lifecycle مع بدء authorization في طبقة UI | استبدال المسار بـ`onManageAuthorization(connectorId)`؛ يمر إلى `IntegrationsScreen(initialConnectorId)` ثم manager الخاص بالمزود | أضيف فحص regression في `final_runtime_integration_audit.py`; آخر ترجمة تحتاج نتيجة CI للالتزام النهائي | لا يوجد تحقق Provider حي |
| زر Refresh كان يعيد الاتصال العام بدلاً من إعادة التحقق الموجّه | `ConnectorDetailsScreen.kt`، نسخة `6160cc6f` | كان ينفذ `viewModel.connect(connectorId)` | اعتبار refresh عملية registry عامة | يمر الآن إلى authorization route الموجّه للمعرف؛ أما OAuth token refresh فيبقى داخلياً في مزوده | فحص static + Architecture Audit PASS | يلزم اختبار UI على جهاز |
| أخطاء ترجمة بسبب تمرير `Set<String>` حيث يتطلب المكوّن `List<String>` ثم بسبب `supportedPlatforms` | `ConnectorDetailsScreen.kt:234,388` في CI السابق | Android CI وDeep Audit سجلا الخطأ صراحة | عدم مطابقة أنواع metadata عند بناء الواجهة | تحويل scopes إلى List، وتمرير منصة واحدة من مجموعة المنصات | تم دفع الالتزام `f6da9e45` لإعادة CI | بانتظار النتيجة النهائية |

## إجابات بوابة الإغلاق

1. **هل بقيت كل الموصلات مسجلة؟** نعم، لم تُحذف أي تعريفات أو تسجيلات. توجد 34 surface في الكتالوج و9 أسطح إضافية محلية/أتمتة في Bootstrap، ليصبح المجموع 43 سطحاً.
2. **هل توجد مصادقة عامة تتجاوز تدفقاً خاصاً بمزود؟** تم العثور على bypass في نسخة إعادة التصميم الأولى لشاشة التفاصيل، وتمت إزالته. لا تثبت هذه المراجعة نجاح كل provider live.
3. **هل يعمل Connect عبر المسار الصحيح؟** الأسطح التي تحتاج provider auth تمر عبر `ConnectorAuthorizationManager` الموجّه. الأسطح المحلية فقط تستخدم مسار local permission/runtime.
4. **هل يعمل Disconnect بطريقة صحيحة؟** `ConnectorsViewModel.disconnect()` يمر عبر `ConnectorAuthorizationManager.disconnect()`، بما في ذلك Google surface-specific disconnect؛ الحالة الخارجية تعتمد على دعم المزود ولا يُدّعى revoke حي بلا اختبار.
5. **هل UI state تطابق Runtime؟** العقد تفرض health gate (`connected && healthy`) قبل إعلان الجاهزية. تحقق الجهاز/المزود الحي غير منفذ هنا.
6. **هل توجد بيانات اعتماد أو جلسات غير معزولة؟** لم يثبت التدقيق الحالي تسريباً؛ المسارات تستخدم auth manager وsurface/runtime IDs. يلزم اختبار مستخدمين فعليين وEmulator/Provider للتحقق الكامل.
7. **ما الاختبارات المنفذة؟** `git diff --check`، Architecture Audit نجح، وفشل CI السابق حُدد وأُصلح. Android CI وDeep Audit للالتزام `f6da9e45` كانا قيد التنفيذ عند إعداد التقرير.
8. **ما الذي لم يمكن التحقق منه؟** Provider live OAuth، إلغاء المستخدم، redirect على جهاز Android، refresh/revoke الخارجي، Android permissions الفعلية، وUser A/User B على جهاز/Emulator.
9. **هل توجد عيوب تمنع الدمج؟** لا يوجد عيب ترجمة معروف بعد إصلاح `f6da9e45`، لكن لا أوصي بالإغلاق النهائي قبل اكتمال Android CI وDeep Audit، ثم اختبار جهاز/Provider.

## References

[1]: https://developers.google.com/identity/protocols/oauth2 "Google OAuth 2.0 documentation"
[2]: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps "GitHub OAuth app authorization"
[3]: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow "Microsoft identity platform OAuth authorization code flow"
[4]: https://developers.notion.com/docs/authorization "Notion public connection authorization"
[5]: https://www.rfc-editor.org/rfc/rfc7636 "RFC 7636 Proof Key for Code Exchange"
