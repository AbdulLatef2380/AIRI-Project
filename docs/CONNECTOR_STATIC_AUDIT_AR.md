# تقرير التدقيق الساكن للموصلات — AIRI

> **نطاق التقرير:** هذا التقرير هو تجميع للنتائج المنظمة المعطاة فقط. لم تُعد قراءة المصدر، ولم تُجرَ اختبارات تشغيلية أو اختبارات مزود/جهاز/شبكة، ولا يجوز تفسيره كإغلاق للنتائج أو كدليل على الجاهزية الإنتاجية.

## 1. مرجعية التدقيق وحدوده

- **الفرع المدقَّق:** `main`.
- **الالتزام المدقَّق:** `2f6fb6d978bca8f970e2b6d44a299f953b5e18db` (الاختصار `2f6fb6d9`).
- **مرجع الجرد التاريخي:** الجرد يذكر `d87da8dd`؛ هذا ليس الالتزام الذي فُحصت عليه النتائج الحالية.
- **نوع العمل:** اكتشاف ومراجعة ساكنة للمصدر والعقود ومسارات التسجيل والتوجيه والاختبارات الموجودة في النتائج المدخلة.
- **ما لم يُنفَّذ:** لا مزودات حقيقية، لا OAuth/PKCE حي، لا حسابات اختبار، لا جهاز Android أو محاكي، لا شبكة/اتصال API، لا Gradle/CI، لا instrumentation، لا APK موقّع، ولا اختبار إنتاجي.
- **قاعدة القراءة:** `LIVE` أو وجود runtime/adapter في المصدر يعني مساراً أو تسجيلاً ساكناً، وليس تحققاً من مزود أو جهاز أو جاهزية إنتاج.

## 2. تحقق الاكتشاف والعدد

تم التحقق من **44 بند عمل مشتق من المصدر**:

- **35** سطحاً من `OfficialConnectorCatalog`.
- **9** معرّفات runtime غير كتالوجية: `remote_llm`, `android_intent`, `voice_mtmd`, `clipboard`, `device_apps`, `contacts`, `system_info`, `ifttt`, `n8n`.
- جرد الكتالوج يذكر **11 PARTIAL** و**24 COMING_SOON**؛ المجموع **35** ومتسق مع مجموعة التعريفات.
- لا توجد نتائج فاشلة من الوكلاء في المدخل المنظم (`failures: []`)، ولذلك لا توجد حالات فشل وكيل معلنة؛ تبقى فجوات التنفيذ/الأدلة الواردة أدناه فجوات تدقيق لا نجاحاً تشغيلياً.

### فروقات العدّ/المرجعية

1. الجرد يذكر المراجعة `d87da8dd` بينما المستودع المفحوص هو `main` عند `2f6fb6d978bca8f970e2b6d44a299f953b5e18db`؛ مجموعة `35+9` ما زالت مطابقة.
2. تقسيم `11 PARTIAL + 24 COMING_SOON` متسق مع 35 تعريف كتالوج.
3. البنود التسعة الإضافية غير الكتالوجية هي معرّفات runtime المذكورة أعلاه، ولا ينبغي عدّها خدمات كتالوج.
4. لا ينبغي عدّ `remote_llm` سطح خدمة كتالوج؛ هو runtime فقط.
5. أي اختلاف إملائي في مسار Zapier هو خطأ تقرير: المسار الصحيح المعطى هو `app/src/main/java/com/airi/assistant/connector/app/ZapierConnector.kt`.

## 3. ملخص الحالة لكل بند

الحالة التالية هي **الحالة الساكنة في النتائج المعطاة**، وليست حالة إغلاق.

| # | المعرّف | النوع | الحالة الساكنة | الخطورة H/M/L/I | خلاصة النطاق |
|---:|---|---|---|---|---|
| 1 | `google_gmail` | كتالوج | `DEFECTS_FOUND` | 3/5/2/0 | Google runtime مشترك؛ OAuth/PKCE/revoke/refresh، routing Gmail، 429، الإلغاء، وعدم تطابق `max`/`max_results`. |
| 2 | `google_calendar` | كتالوج | `DEFECTS_FOUND` | 3/4/1/0 | نافذة الأيام والترقيم، الإلغاء، revoke، refresh/health، scope binding. |
| 3 | `google_drive` | كتالوج | `DEFECTS_FOUND` | 2/4/1/0 | OAuth state، revoke/refresh، query escaping، pagination/الحجم، أخطاء Google. |
| 4 | `google_docs` | كتالوج | `DEFECTS_FOUND` | 0/2/1/0 | عقد declarative غير قابل للتنفيذ؛ health endpoint و`google_workspace` غير محسومين. |
| 5 | `google_sheets` | كتالوج | `DEFECTS_FOUND` | 0/2/2/0 | نص الواجهة يوحي بالتحديث رغم `spreadsheets.readonly`؛ عقد health/rollout واختبارات ناقصة. |
| 6 | `google_contacts` | كتالوج | `DEFECTS_FOUND` | 0/1/1/0 | لا People adapter؛ token-only health خطر عند التفعيل، واختبارات مخصصة ناقصة. |
| 7 | `google_tasks` | كتالوج | `DEFECTS_FOUND` | 0/0/1/1 | COMING_SOON مضبوط؛ stale audit metadata وفجوة اختبار مقصودة قبل التنفيذ. |
| 8 | `google_meet` | كتالوج | `DEFECTS_FOUND` | 0/3/1/0 | health URL غير آمن كـGET collection، حدود capability/lifecycle غير محددة. |
| 9 | `microsoft_outlook` | كتالوج | `DEFECTS_FOUND` | 4/2/1/0 | LIVE مستنتج من shared runtime، pagination، refresh scope، Graph status/errors، وثائق متعارضة. |
| 10 | `microsoft_calendar` | كتالوج | `DEFECTS_FOUND` | 2/5/2/0 | calendarView/filter/pagination، 401 refresh، 429/timeout، scope refresh، docs/tests. |
| 11 | `microsoft_onedrive` | كتالوج | `DEFECTS_FOUND` | 2/3/1/1 | readiness/health المشترك، path/pagination، 429/timeout، وثائق stale. |
| 12 | `microsoft_teams` | كتالوج | `DEFECTS_FOUND` | 2/4/2/0 | LIVE غير مبرر، joinedTeams pagination، scope admission، الحساب الشخصي، revoke. |
| 13 | `microsoft_sharepoint` | كتالوج | `DEFECTS_FOUND` | 0/1/1/0 | contract غير كافٍ لتحديد site/drive/action/tenant/error boundaries. |
| 14 | `microsoft_todo` | كتالوج | `DEFECTS_FOUND` | 0/1/1/0 | UI يوحي بإضافة مهمة رغم COMING_SOON؛ اختبارات هوية/readiness ناقصة. |
| 15 | `github` | كتالوج | `DEFECTS_FOUND` | 5/6/2/0 | retry غير آمن للـPOST، rate limits، URL/link host، split-brain legacy/canonical، revoke/approval. |
| 16 | `gitlab` | كتالوج | `DEFECTS_FOUND` | 0/0/3/0 | catalog-only صحيح؛ `GitLabGitLabConnector` ونسخ UI غير المتسقة واختبارات ناقصة. |
| 17 | `bitbucket` | كتالوج | `DEFECTS_FOUND` | 0/0/1/0 | catalog-only صحيح؛ اختبار خاص غير موجود. |
| 18 | `jira` | كتالوج | `DEFECTS_FOUND` | 0/3/3/0 | adapter identity/auth/provider/site metadata وcloudId غير محسومة. |
| 19 | `linear` | كتالوج | `DEFECTS_FOUND` | 0/1/2/0 | contract API-key/GraphQL read-only غير كافٍ؛ adapter غير موجود. |
| 20 | `slack` | كتالوج | `DEFECTS_FOUND` | 0/0/3/0 | scope flat لا يفصل read عن `chat:write`؛ copy/tests قبل adapter ناقصة. |
| 21 | `discord` | كتالوج | `DEFECTS_FOUND` | 0/1/1/0 | rollout adapter ID مزدوج؛ contract-specific tests ناقصة. |
| 22 | `telegram` | كتالوج | `DEFECTS_FOUND` | 3/3/2/0 | send action غير معروض، legacy confirmation bypass، retries/HTTP/URL/state. |
| 23 | `notion` | كتالوج | `DEFECTS_FOUND` | 4/5/1/0 | filter صامت، cache token، UUID، pagination/response limits، cancellation، health/schema. |
| 24 | `trello` | كتالوج | `DEFECTS_FOUND` | 0/1/2/0 | catalog-only؛ disconnect يرجع نجاحاً كاذباً وcopy يوحي بالقراءة/التعديل. |
| 25 | `asana` | كتالوج | `NO_STATIC_DEFECT_FOUND` | 0/0/0/1 | catalog-only مضبوط؛ لا عيب ساكن مثبت، لكن الأدلة التشغيلية غائبة. |
| 26 | `clickup` | كتالوج | `DEFECTS_FOUND` | 1/3/1/0 | auth PKCE/client-secret غير متسق، scope `read` placeholder، workspace boundary. |
| 27 | `monday` | كتالوج | `DEFECTS_FOUND` | 0/1/3/0 | disconnect كاذب، copy وrequiredAdapter مضللان، اختبارات ناقصة. |
| 28 | `todoist` | كتالوج | `DEFECTS_FOUND` | 0/0/2/0 | adapter identity مولّد بشكل مضلل واختبارات خاصة ناقصة. |
| 29 | `dropbox` | كتالوج | `DEFECTS_FOUND` | 0/0/1/1 | copy مضلل وفجوة اختبار؛ التنفيذ غائب عمداً. |
| 30 | `box` | كتالوج | `DEFECTS_FOUND` | 0/1/1/1 | scope stranded خارج canonical definition؛ copy واختبارات قبل التنفيذ. |
| 31 | `figma` | كتالوج | `NO_STATIC_DEFECT_FOUND` | 0/0/0/0 | COMING_SOON/non-executable مضبوط؛ لا تنفيذ يمكن اختباره. |
| 32 | `canva` | كتالوج | `DEFECTS_FOUND` | 0/2/1/1 | disconnect/identity، tests، عقد pagination/revoke/write غير كافٍ. |
| 33 | `airtable` | كتالوج | `DEFECTS_FOUND` | 0/2/2/0 | disconnect صامت، copy/auth contract غير متسق، tests ناقصة. |
| 34 | `zapier` | كتالوج | `DEFECTS_FOUND` | 3/2/1/0 | client ID placeholder، HTTP/webhook false success، refresh/revoke، bounds/tests. |
| 35 | `zoom` | كتالوج | `DEFECTS_FOUND` | 0/1/1/0 | scope metadata منفصل عن catalog؛ اختبار خاص غائب. |
| 36 | `remote_llm` | runtime | `DEFECTS_FOUND` | 5/2/1/0 | custom-key race، endpoint/key leakage، cancellation، provider fallback/health. |
| 37 | `android_intent` | runtime | `DEFECTS_FOUND` | 0/3/2/1 | URL/private-host، action contract، raw URL disclosure، lifecycle/exceptions. |
| 38 | `voice_mtmd` | runtime | `DEFECTS_FOUND` | 5/4/0/0 | false readiness، action dead، lifecycle/cancellation/native resources/audio. |
| 39 | `clipboard` | runtime | `DEFECTS_FOUND` | 2/3/1/0 | actions غير معلنة، PII/size، health/lifecycle، exceptions. |
| 40 | `device_apps` | runtime | `DEFECTS_FOUND` | 2/2/2/0 | package visibility، dead routing، URL normalization، exceptions/lifecycle. |
| 41 | `contacts` | runtime | `DEFECTS_FOUND` | 3/3/1/0 | profile bypass، dead actions، `contacts_local` drift، query/PII/errors. |
| 42 | `system_info` | runtime | `DEFECTS_FOUND` | 1/4/2/0 | `ACCESS_NETWORK_STATE` مفقود، false success/health، actions غير معروضة. |
| 43 | `ifttt` | runtime | `DEFECTS_FOUND` | 4/2/1/0 | false success، health، duplicate instance، retry/idempotency، URL key. |
| 44 | `n8n` | runtime | `DEFECTS_FOUND` | 3/4/0/1 | action/config dead، redirect/cancellation، payload/idempotency، health/errors. |

> **ملاحظة على العد:** الأرقام في العمود الأخير هي عدد findings المدخلة لكل بند بحسب `HIGH/MEDIUM/LOW/INFO`. البنود `cross_*` أدناه ليست من الـ44، بل نتائج تدقيق عابر.

## 4. counts حسب الشدة

### بنود الاكتشاف الـ44 فقط

| الشدة | العدد |
|---|---:|
| `CRITICAL` | 0 |
| `HIGH` | 59 |
| `MEDIUM` | 96 |
| `LOW` | 61 |
| `INFO` | 8 |
| **المجموع** | **224** |

### النتائج العابرة الإضافية

| النتيجة العابرة | H | M | L | I | المجموع |
|---|---:|---:|---:|---:|---:|
| `cross_contracts_access` | 4 | 3 | 0 | 1 | 8 |
| `cross_lifecycle_dispatch` | 2 | 5 | 0 | 0 | 7 |
| `cross_oauth_provider` | 1 | 4 | 1 | 0 | 6 |
| `cross_catalog_ui` | 1 | 1 | 2 | 0 | 4 |
| `cross_tests_failure_security` | 2 | 2 | 0 | 0 | 4 |
| **إجمالي العابر** | **10** | **15** | **3** | **1** | **29** |

**الإجمالي مع النتائج العابرة:** `CRITICAL=0`, `HIGH=69`, `MEDIUM=111`, `LOW=64`, `INFO=9`، أي **253 finding**.

## 5. سجل النتائج التفصيلي

يستخدم كل سطر الحقول المطلوبة: **الفئة — الملف:الموقع — الدليل — الأثر — الإصلاح**. النص أدناه لا يضيف ادعاءات خارج المدخل المنظم.

### Google: Gmail / Calendar / Drive

#### `google_gmail`

- **HIGH — auth/PKCE/state contract mismatch.** الملف: `app/src/main/java/com/airi/assistant/integrations/google/GoogleAuthService.kt`، المواقع `34-40,77-94,97-113`؛ العقد العام في `app/src/main/java/com/airi/assistant/connector/ConnectorAuthStrategy.kt:56-63`، و`IntegrationsViewModel.kt:394-400` no-op. **الدليل:** GoogleSignIn/Identity لا ينشئ verifier/challenge أو state مربوطاً بالعميل، رغم إعلان `OAUTH2_PKCE`. **الأثر:** ضمان PKCE/state غير قابل للإثبات وربط callback العام لا يكمل Google. **الإصلاح:** تنفيذ flow حقيقي one-time state + PKCE والتحقق والاختبارات، أو نمذجة GIS كآلية خاصة موثقة وعدم إبقاء claim عام غير مثبت.
- **HIGH — revoke missing.** الملف نفسه، `55-65`. **الدليل:** `disconnect()` يمسح الذاكرة ويستدعي `signOut()` ويبتلع الاستثناء، بلا `revokeAccess`. **الأثر:** disconnect محلي لا يلغي grant Gmail عند Google. **الإصلاح:** provider revoke منفصل، نتيجة نجاح/فشل ظاهرة، واختبارات cleanup/revoke.
- **HIGH — token refresh/expiry.** `app/src/main/java/com/airi/assistant/connector/app/GoogleConnector.kt:280-298`. **الدليل:** 401/403 يمسح token ويعيد `authorization_required` بلا silent refresh أو retry. **الأثر:** expiry transient يتحول إلى reauthorization. **الإصلاح:** renewal قابل للإلغاء، retry قراءة واحد، والمحو فقط بعد فشل التجديد.
- **MEDIUM — health/error mapping.** `GoogleConnector.kt:95-118,280-298`. **الدليل:** healthy يعني email + token غير فارغ؛ لا provider health، و403 يساوى 401 ولا يحدّث `_state` فوراً. **الأثر:** healthy كاذب وpermission/quota misclassification. **الإصلاح:** health request محدود، فصل 401 عن 403، وتحديث state ذرّياً.
- **MEDIUM — rate limit.** `GoogleConnector.kt:267-299` و`ConnectorRuntimeManager.kt:157-162`. **الدليل:** 429 يصبح `api_error` غير قابل لإعادة المحاولة بلا `Retry-After`. **الأثر:** quota يفشل كخطأ دائم. **الإصلاح:** `rate_limited` مع backoff محدود وjitter وإلغاء.
- **MEDIUM — action schema mismatch.** `app/src/main/java/com/airi/assistant/ai/tools/ToolRegistry.kt:196-205` مقابل `GoogleConnector.kt:160-164`. **الدليل:** legacy يرسل `max` بينما التنفيذ يقرأ `max_results`. **الأثر:** حد المستخدم يُهمل ويستخدم 10. **الإصلاح:** اسم canonical أو normalization واختبار routing.
- **MEDIUM — mail-read misrouting.** `app/src/main/java/com/airi/assistant/ai/skills/impl/GmailAssistantSkill.kt:11-17,45-55` و`GoogleConnector.kt:24-28,160-175`. **الدليل:** skill تقول read/summarize لكنها تنفذ `gmail_list` فقط وتعيد IDs. **الأثر:** لا محتوى ولا تلخيص. **الإصلاح:** list ثم read بـ`message_id` typed واختبار المسارين.
- **MEDIUM — cancellation safety.** `GoogleConnector.kt:90-93,126-156,265-277`. **الدليل:** blocking `Call.execute()` غير محتفظ به ولا يُلغى عند coroutine cancellation. **الأثر:** الطلب يستمر بعد timeout. **الإصلاح:** suspend bridge و`Call.cancel()` واختبار blocked call.
- **LOW — unsafe URL/input.** `GoogleConnector.kt:183-188`. **الدليل:** `message_id` من text أو param، blank فقط، interpolation مباشر. **الأثر:** malformed path/URL وتجاوز schema. **الإصلاح:** typed ID، format validation، path-segment encoding.
- **LOW — tests gap.** `app/src/test/java/com/airi/assistant/connector/app/GoogleConnectorActionPolicyTest.kt:7-31`. **الدليل:** لا GoogleConnector/Gmail execution test. **الأثر:** defects غير محمية. **الإصلاح:** injectable HTTP/auth tests للـactions، status، 429، refresh، revoke، cancellation.

#### `google_calendar`

- **HIGH — query semantics/pagination.** `GoogleConnector.kt:77-81,208-228`. **الدليل:** action تعلن `days` لكن التنفيذ يقرأ `max_results` ولا يضع `timeMax` أو `pageToken` ولا يتبع `nextPageToken`. **الأثر:** window ignored ونتائج الصفحة الأولى فقط. **الإصلاح:** `timeMin/timeMax`، limits، pagination bounded واختبارات زمنية.
- **HIGH — cancellation.** `GoogleConnector.kt:208-233,280-299`. **الدليل:** `catch(Exception)` يحول CancellationException إلى `network_error` retryable، مع blocking call. **الأثر:** الإلغاء يصبح retryable failure. **الإصلاح:** إعادة رمي CancellationException وCall.cancel.
- **MEDIUM — 429.** `GoogleConnector.kt:267-292`. **الدليل:** 429 generic non-retryable بلا Retry-After. **الإصلاح:** mapping/backoff واختبار.
- **HIGH — revoke.** `GoogleAuthService.kt:55-65`. **الدليل:** signOut/local clear دون remote revoke. **الإصلاح:** revoke provider وظهور الفشل.
- **MEDIUM — refresh/health.** `GoogleConnector.kt:95-118,126-138` و`GoogleAuthService.kt:20-32,116-124`. **الدليل:** token presence = healthy، لا refresh. **الإصلاح:** health حقيقي وتجديد قابل للإلغاء وتحديث فوري.
- **MEDIUM — OAuth PKCE mismatch.** `ConnectorAuthStrategy.kt:8-10,56-63`; `GoogleAuthService.kt:34-40,77-95`; `ConnectorAuthorizationManager.kt:188-208`. **الدليل:** generic PKCE لا يطابق Google GIS، و`completeOAuth` يقبل Microsoft/Zapier فقط. **الإصلاح:** mode موثق خاص أو flow AIRI-owned.
- **MEDIUM — scope binding race.** `IntegrationsViewModel.kt:325-337,351-366`; `GoogleAuthService.kt:82-95,126-149`. **الدليل:** success لا يقارن pending scopes ولا request nonce. **الأثر:** stale/overlapping consent قد يثبت token قديم. **الإصلاح:** request record/nonce/consume ومقارنة scope الحالية.
- **LOW — regression coverage.** `GoogleConnectorActionPolicyTest.kt:7-32`. **الدليل:** لا execution/calendar lifecycle tests. **الإصلاح:** fake HTTP/auth tests.

#### `google_drive`

- **HIGH — OAuth state/PKCE binding.** `GoogleAuthService.kt:77-109,126-149`، contract `PrimaryConnectorContract.kt:14-16`. **الدليل:** process-wide pending scopes بلا nonce/verifier/request/account binding. **الأثر:** stale/concurrent result قد يكتب token على account/request آخر. **الإصلاح:** immutable per-request record وatomic consume واختبارات replay/profile.
- **HIGH — revoke.** `GoogleAuthService.kt:55-65`. **الأثر:** local signout لا يلغي Drive grant. **الإصلاح:** provider revoke وتقرير الفشل.
- **MEDIUM — refresh/health.** `GoogleConnector.kt:95-118,126-138`. **الدليل:** token nonblank = healthy، لا provider probe أو refresh. **الإصلاح:** health/refresh/state invalidation.
- **MEDIUM — Drive query escaping.** `GoogleConnector.kt:238-243`. **الدليل:** `name contains '$query'` دون escaping apostrophe؛ blank غير مرفوض. **الإصلاح:** trim/length/reject blank وescape literal ثم URL encode.
- **MEDIUM — pagination/result limits.** `GoogleConnector.kt:82-86,238-257`. **الدليل:** `max_results` غير معلن، one page، لا nextPageToken ولا byte cap. **الإصلاح:** limit معلن ومحدود، continuation/truncation.
- **MEDIUM — provider error mapping.** `GoogleConnector.kt:267-299`. **الدليل:** body discarded، كل 403 يمسح token، 429 غير retryable، malformed JSON network retryable. **الإصلاح:** parse reason safely وفصل auth/quota/rate/invalid query/server.
- **LOW — tests.** `GoogleConnectorActionPolicyTest.kt:7-31`؛ لا Drive HTTP/health/revoke tests. **الإصلاح:** fake transport coverage.

### بقية Google والكتالوجات غير المنفذة

- **`google_docs` — MEDIUM:** `ProviderAdapterContract.kt:23` يعلن `https://docs.googleapis.com/v1/documents` كـhealth دون document ID أو `documents.get`; الأثر health غير قابل للتنفيذ وقد يفشل/يضلل؛ الإصلاح resource-level probe آمن أو non-HTTP readiness واختبار يرفض collection URL. — **LOW:** العقد يستخدم `adapterId=google_workspace` بينما runtime المسجل `google` وfallback `google_docs` (`ProviderAdapterContract.kt:23`, `ConnectorRuntimeDescriptor.kt:10-25`, `ConnectorBootstrap.kt:96-101`)؛ أصلح هوية future adapter. — **MEDIUM:** لا tests خاصة للـscope/endpoint/fallback/adapter_not_installed؛ أضفها.
- **`google_sheets` — MEDIUM:** `app/src/main/java/com/airi/assistant/ui/screens/ConnectorCatalog.kt:38` يقول قراءة وتحديث بينما العقد `spreadsheets.readonly` وentry COMING_SOON؛ عدّل النص إلى read-only/planned. — **MEDIUM:** `ProviderAdapterContract.kt:24` health collection بلا spreadsheetId؛ عرّف health آمن. — **LOW:** `ConnectorRolloutRegistry.kt:84-91` يولّد `GoogleGoogleSheetsConnector`; استخدم ID صريح. — **LOW:** `connectors/google_sheets/CONNECTOR.md:1-8` provider/auth metadata لا يطابق canonical؛ وحّد الوثائق.
- **`google_contacts` — MEDIUM:** `GoogleConnector.kt:95-116` يعلن healthy من token؛ contract People health غير تنفيذي (`ProviderAdapterContract.kt:3-20,25`)؛ لا تستخدم ذلك عند إضافة adapter. — **LOW:** لا Google Contacts/People tests؛ أضف no-mapping/no-action/scope وfuture endpoint tests.
- **`google_tasks` — LOW:** `docs/CONNECTOR_INVENTORY_AND_3_PHASE_PLAN_AR.md:3-4` stale `d87da8dd` مقابل commit الحالي؛ حدّث marker أو سمّه تاريخياً. — **INFO:** `ProviderAdapterContractTest.kt:26-35,39-42` لا يثبت exact scope/endpoint/pagination/error؛ فجوة متعمدة قبل التفعيل.
- **`google_meet` — MEDIUM:** `ProviderAdapterContract.kt:27` يعلن `/v2/spaces` كـGET health رغم أن العمليات create/get/patch؛ قد يسبب health false أو side effect. — **MEDIUM:** scope `meetings.space.created` والقدرات/العمليات غير محددة (`ProviderAdapterContract.kt:4-15,21-28`; `ConnectorDefinition.kt:132-136`). — **MEDIUM:** لا lifecycle/error/revoke fields في العقد. — **LOW:** `ConnectorCatalogTest.kt:69-75` stale/لا test خاص؛ أصلح الاختبار وأضف exact Meet boundary.

### Microsoft Graph surfaces

- **`microsoft_outlook` — HIGH:** `ConnectorRolloutRegistry.kt:48-50,65-82` يجعل كل `microsoft_graph` LIVE حتى surface PARTIAL؛ الأثر authorization قبل provider/device evidence؛ الإصلاح readiness surface-specific. — **HIGH:** `MicrosoftGraphConnector.kt:137-162` hard-coded `$top=10` بلا `@odata.nextLink`؛ أصلح continuation/limit. — **HIGH:** `MicrosoftGraphTokenService.kt:35-39` refresh يستخدم `.default offline_access` بدل exact delegated set؛ أصلح scope lifecycle. — **HIGH:** `MicrosoftGraphConnector.kt:154-160,189-192` يميز فقط 401؛ 403/429/5xx/Retry-After غير typed؛ أصلح mapping. — **MEDIUM:** `connectors/microsoft_outlook/CONNECTOR.md:1-18` يقول COMING_SOON بينما source PARTIAL/registered؛ وحّد الحقيقة. — **MEDIUM:** لا MicrosoftGraphConnector tests لـURL/callback/refresh/pagination/status؛ أضف fake HTTP. — **LOW:** `MicrosoftGraphConnector.kt:155-158` 401 يغير statusLine دون `errorMessage`؛ استخدم state update موحد.
- **`microsoft_calendar` — HIGH:** `MicrosoftGraphConnector.kt:137-149,164-170` لا time range/filter/order/pagination؛ استخدم `calendarView` bounded. — **MEDIUM:** `/me` health لا يثبت Calendars.ReadBasic (`110-123`). — **HIGH:** 401 يمسح refresh token بلا forced refresh (`131-160`). — **MEDIUM:** transport/408/429/Retry-After (`154-160,189-192`) غير typed. — **MEDIUM:** refresh `.default` (`MicrosoftGraphTokenService.kt:35-39`) يضعف least privilege. — **MEDIUM:** docs `connectors/microsoft_calendar/CONNECTOR.md:7,16-18` stale. — **MEDIUM:** `ConnectorCatalogTest.kt:69-75` يتوقع Outlook COMING_SOON خلاف PARTIAL. — **LOW:** `MicrosoftGraphTokenService.kt:69-71` local clear لا provider revoke؛ وثّق limitation أو نفّذ المتاح. — **LOW:** لا connector-level tests.
- **`microsoft_onedrive` — HIGH:** rollout LIVE من shared runtime (`ConnectorRolloutRegistry.kt:48-82`) رغم PARTIAL؛ افصل installed عن validated. — **HIGH:** `/me` health (`MicrosoftGraphConnector.kt:110-123`) لا يثبت `/me/drive` أو Files.Read. — **MEDIUM:** one `$top` بلا nextLink/size/truncation (`140-162`). — **MEDIUM:** `folder_path` بلا caps أو رفض `.`/`..` (`141-148,201-203`). — **MEDIUM:** status/timeout/429 (`154-160,189-192`) generic. — **LOW:** docs provider/status stale (`connectors/microsoft_onedrive/CONNECTOR.md:2-7,16-18`). — **INFO:** لا MicrosoftGraphConnectorTest.
- **`microsoft_teams` — HIGH:** shared runtime يرفع surface إلى LIVE (`ConnectorRolloutRegistry.kt:48-82`)؛ ابقِه CONFIGURATION_REQUIRED. — **MEDIUM:** joinedTeams one page (`MicrosoftGraphConnector.kt:150,154-161,189-192`). — **HIGH:** Graph exceptions و429 generic (`116-123,154-160,189-192`). — **MEDIUM:** `User.Read` غير admissible في `buildAuthUrl` عند scope union (`52-65,184-186`). — **MEDIUM:** `common` و`/me` لا يرفضان personal account (`OAuthConfiguration.kt:24-34`). — **MEDIUM:** `MicrosoftGraphTokenService.kt:69-71` يبتلع نتيجة revoke المحلي. — **LOW:** لا tests خاصة. — **LOW:** `docs/MICROSOFT_VERTICAL_SLICE_AR.md:3-7,25-27,50-56` stale بشأن Teams.
- **`microsoft_sharepoint` — MEDIUM:** `ProviderAdapterContract.kt:4-15,30` لا يحدد sites/drives/actions/pagination/tenant/errors رغم `Sites.Read.All`; وسّع contract قبل التنفيذ. — **LOW:** لا tests exact contract/no mapping/CATALOG_ONLY.
- **`microsoft_todo` — MEDIUM:** `ConnectorCatalog.kt:47` يوحي بإضافة مهمة رغم `soon`؛ أصلح copy. — **LOW:** `ConnectorRolloutRegistryTest.kt:29-43` لا يثبت fallback `microsoft_todo` أو no action؛ أضف test.

### GitHub وGitLab وAtlassian/communication surfaces

- **`github` — HIGH:** `GitHubConnector.kt:244-263,277` كل exception retryable، و`createIssue` بلا idempotency؛ قد تتكرر issue بعد response loss. — **HIGH:** `GitHubConnector.kt:334-337,261-263` لا responseCode/errorStream/Retry-After؛ 401/403/404/422/429 indistinguishable. — **HIGH:** cached health لا يتغير بعد PAT revocation (`78-87,245-263`). — **HIGH:** `apiGetAllPages` يثق `Link` off-host ويضيف Bearer (`289-315,321-331`)؛ allowlist `api.github.com`. — **MEDIUM:** issues/PRs/repos pagination silently capped (`266-318`). — **MEDIUM:** repo/path interpolation بلا path encoding (`276-280`; legacy `GithubService.kt:166-170,181-186,210,222,252`). — **HIGH:** legacy ToolRegistry/GithubService bypass canonical (`ToolRegistry.kt:19-29,142-171`; `ToolDispatcher.kt:360-374`). — **MEDIUM:** disconnect يمسح auth manager لا legacy SecureStorage (`GitHubConnector.kt:90-93`; `ConnectorAuthorizationManager.kt:155-177`). — **MEDIUM:** project secret لا revoke (`GitHubConnector.kt:194-229,90-93`). — **MEDIUM:** `create_issue` غير موجود في `agentActions` رغم dispatch/policy (`50-75,249-257`). — **MEDIUM:** `GitHubMutationPolicy.kt:15-20` fail-open لأي action غير create_issue. — **LOW:** issues endpoint يخلط PRs (`276`). — **LOW:** لا tests HTTP/ambiguity/URL/lifecycle (`GitHubMutationPolicyTest.kt:8-20`).
- **`gitlab` — LOW:** `ConnectorRolloutRegistry.kt:84-91` يصنع `GitLabGitLabConnector` بدل contract `adapterId=gitlab`; أصلح canonical ID. — **LOW:** `ConnectorCatalog.kt:49` يطلب connect/token رغم COMING_SOON وdetails disabled. — **LOW:** لا GitLab-specific tests لـscope/endpoint/no registration/readiness.
- **`bitbucket` — LOW:** `ConnectorRolloutRegistryTest.kt:18-43` و`ProviderAdapterContractTest.kt:11-42` لا يثبتان exact Bitbucket CATALOG_ONLY/runtime/scopes/endpoint؛ أضف assertions، مع إبقاء absence of adapter intentional.
- **`jira` — MEDIUM:** `ProviderAdapterContract.kt:34` `adapterId=atlassian` لا يطابق rollout `AtlassianJiraConnector` (`ConnectorRolloutRegistry.kt:84-91`). — **LOW:** catalog/docs `OAUTH2_AND_API` مقابل contract `OAUTH2_PKCE` (`ConnectorDefinition.kt:146`, `ProviderAdapterContract.kt:34`). — **LOW:** docs provider Jira مقابل canonical Atlassian (`connectors/jira/CONNECTOR.md:2-9`). — **LOW:** UI copy توحي بإدارة/تعديل رغم COMING_SOON (`ConnectorCatalog.kt:51`). — **MEDIUM:** لا cloudId/accessible-resources/site boundary (`ProviderAdapterContract.kt:4-15,34`; `docs/integration-research-2026-09.md:17`). — **MEDIUM:** لا Jira-specific tests للـPKCE/site/pagination/errors.
- **`linear` — LOW:** rollout يصنع `LinearLinearConnector` (`ConnectorRolloutRegistry.kt:65-92`) بدل `linear`. — **MEDIUM:** contract API key/GraphQL لا يحدد vault، query allowlist، cost/cursor/error/timeout (`ProviderAdapterContract.kt:3-19,35`). — **LOW:** لا tests خاصة للـcatalog-only/no runtime/future GraphQL.
- **`slack` — LOW:** flat `requiredScopes=[channels:read,chat:write,users:read]` في `ProviderAdapterContract.kt:3-19` لا يفصل read عن write؛ صمّم per-action grant/confirmation. — **LOW:** لا Slack-specific no-tool/no-auth tests. — **LOW:** `ConnectorCatalog.kt:53` يوحي باتصال وإرسال رغم COMING_SOON.
- **`discord` — MEDIUM:** `ConnectorRolloutRegistry.kt:84-91` ينتج `DiscordDiscordConnector` مقابل `ProviderAdapterContract.kt:37` `discord`; أصلح canonical ID. — **LOW:** `ProviderAdapterContractTest.kt:11-35,40-42` لا يثبت exact identify/guilds/health/no registration.
- **`telegram` — HIGH:** `TelegramConnector.kt:59-76,140-151` لا يعلن `send_message` رغم dispatch؛ أضفه WRITE/confirmation. — **HIGH:** legacy `ToolRegistry.kt:24-31,174-191` و`TelegramMessengerSkill.kt:43-68` يرسلان بلا typed approval. — **HIGH:** `AdaptiveGraphEngine.kt:233-245` و`TaskPlanner.kt:49-55` يعيدان إرسال mutation بعد timeout؛ لا retry غامض. — **MEDIUM:** clients بلا callTimeout/body bound/status/close (`TelegramConnector.kt:79-82,178-193,237-242`; `TelegramService.kt:16-19,54-91`). — **MEDIUM:** token raw في URL وerror redaction ناقص (`TelegramService.kt:23-31,47-49,66-90`). — **MEDIUM:** state لا يكتب SecureStorage عند runtime connect (`TelegramConnector.kt:94-111`). — **LOW:** `ConnectorRolloutRegistry.kt:84-91` ينتج TelegramTelegramConnector. — **LOW:** لا tests connector/service.
- **`notion` — HIGH:** invalid `filter_json` يتحول إلى `{}` (`NotionMcpConnector.kt:271-274`)؛ ارفض بدل unfiltered read. — **HIGH:** cache token لا يمسح في teardown (`67-82,116-119`). — **HIGH:** runCatching يبتلع cancellation (`97-113,315-344`). — **HIGH:** page/block/database IDs بلا UUID validation وURL segment encoding (`187-204,266-278,294-313`). — **MEDIUM:** لا cursor pagination (`166-184,203-214,280-289`). — **MEDIUM:** body/raw_json/result بلا caps (`171-182,190-198,281-287,320-325`). — **MEDIUM:** handshake يختزل 401/403/429/5xx (`91-113`). — **MEDIUM:** `notion` و`notion_mcp` كلاهما LIVE (`ConnectorRolloutRegistry.kt:48-50`; `ConnectorRuntimeDescriptor.kt:19`). — **MEDIUM:** schema requiredness مفقودة (`McpConnector.kt:48-62`; Notion dispatch `143-159`). — **LOW:** لا NotionMcpConnector tests.
- **`trello` — MEDIUM:** `ConnectorAuthorizationManager.kt:245-255` يهمل false من registry ويعيد true لruntime غير مسجل. — **LOW:** `ConnectorCatalog.kt:57` يوحي connect/read/edit رغم `connectors/trello/CONNECTOR.md:18`. — **LOW:** لا Trello-specific tests للـabsence/disconnect/copy.
- **`asana` — INFO:** لا عيب ساكن مثبت؛ الموجود intentional COMING_SOON/non-executable. الفجوة الوحيدة `ConnectorAuthStrategyTest.kt:20-28` و`ProviderAdapterContractTest.kt:11-42` لا تختبر Asana بالاسم، ويجب إضافة tests قبل التفعيل.
- **`clickup` — HIGH:** `ProviderAdapterContract.kt:40` يعلن OAUTH2_PKCE/public client بينما docs ClickUp في النتائج تتطلب Authorization Code/client_secret؛ لا تفعل adapter قبل التحقق. — **MEDIUM:** scope `read` placeholder ولا workspace boundary (`ProviderAdapterContract.kt:40`). — **MEDIUM:** rollout `ClickUpClickUpConnector` مقابل `clickup`. — **MEDIUM:** لا tests exact auth/scope/workspace. — **LOW:** `ConnectorCatalog.kt:59` copy actionable رغم COMING_SOON.
- **`monday` — MEDIUM:** disconnect manager يهمل registry false (`ConnectorAuthorizationManager.kt:245-255`). — **LOW:** `ConnectorCatalog.kt:57-61` copy يوحي connect/manage. — **LOW:** rollout identity `Monday.comMonday.comConnector` (`ConnectorRolloutRegistry.kt:84-92`) بدل `monday`. — **LOW:** لا tests خاصة للـbegin/disconnect/identity.
- **`todoist` — LOW:** rollout يصنع `TodoistTodoistConnector` بدل contract `todoist` (`ConnectorRolloutRegistry.kt:89`; `ProviderAdapterContract.kt:42`). — **LOW:** لا tests exact data:read/user endpoint/no registry.
- **`dropbox` — LOW:** `ConnectorCatalog.kt:62` يوحي connect/search/share/delete رغم COMING_SOON. — **INFO:** generic tests لا تغطي exact scopes/list_continue/path/429؛ فجوة قبل adapter.
- **`box` — MEDIUM:** scope `root_readonly` موجود فقط في non-executable contract (`ProviderAdapterContract.kt:21-44`) بينما catalog `soon` بلا requiredScopes؛ قد يصبح promotion underscoped. — **LOW:** copy actionable (`ConnectorCatalog.kt:61-64`). — **INFO:** لا Box-specific regression.
- **`figma` — لا findings:** كل مسار Figma COMING_SOON/non-executable: `ConnectorDefinition.kt:159`, `ProviderAdapterContract.kt:45`, `ConnectorAuthStrategy.kt:99-105`, `ConnectorBootstrap.kt:71-102`. **لا يعني ذلك production readiness.**
- **`canva` — MEDIUM:** manager disconnect يرجع true لـruntime غير مسجل (`ConnectorAuthorizationManager.kt:245-255`). — **LOW:** rollout `CanvaCanvaConnector` مقابل `adapterId=canva` (`ConnectorRolloutRegistry.kt:84-92`; `ProviderAdapterContract.kt:46`). — **MEDIUM:** لا Canva-specific tests لـscope/health/no registry/disconnect (`ProviderAdapterContractTest.kt:9-43`). — **INFO:** العقد لا يفرض pagination/size/rate/revoke/write confirmation (`ProviderAdapterContract.kt:4-15,46-47`).
- **`airtable` — MEDIUM:** manager disconnect false swallowed (`ConnectorAuthorizationManager.kt:245-255`; `ConnectorRegistry.kt:127-133`). — **MEDIUM:** copy يوحي بإدارة وإنشاء/تعديل رغم COMING_SOON (`ConnectorCatalog.kt:66`). — **LOW:** catalog/docs `OAUTH2_AND_API` مقابل contract `OAUTH2_PKCE` (`ConnectorDefinition.kt:161`, `ProviderAdapterContract.kt:47`). — **LOW:** لا Airtable-specific tests لـscope/health/readiness/disconnect/copy.
- **`zapier` — HIGH:** `ZapierConnector.kt:58-61,99-102,110-125` يستخدم `ZAPIER_CLIENT_ID_PLACEHOLDER`؛ OAuth unreachable. — **HIGH:** `ZapierConnector.kt:201-223,272-283,313-322` لا status check/response close ويحوّل error JSON إلى healthy/empty. — **HIGH:** `sendWebhook()` يعيد string failure ثم `execute()` يلفه Success (`232-267,297-310`). — **MEDIUM:** no callTimeout/body bound؛ UI direct execute bypass runtime (`71-76`; `ZapierIftttScreen.kt:261-271`). — **MEDIUM:** refresh غائب وdisconnect local only (`172-182,225-227`; `ConnectorAuthManager.kt:65-76`). — **LOW:** no dedicated ZapierConnector tests.
- **`zoom` — MEDIUM:** `ConnectorDefinition.kt:74-95,163` empty soon metadata بينما contract `ProviderAdapterContract.kt:48` يقول `user:read` و`/v2/users/me`; مصدران غير متزامنين. — **LOW:** لا Zoom-specific contract/no-registration tests.

### Runtime-only/local/LLM surfaces

- **`remote_llm` — HIGH:** `CloudAdapterFactory.kt:185-198,156-163` يكتب custom key في slot واحد `CUSTOM`؛ concurrent endpoint requests قد ترسل credential خاطئاً. — **HIGH:** `ModelSettingsScreen.kt:2403-2415` يحفظ أي nonblank serverUrl بلا `LocalEndpointPolicy`؛ key قد يرسل عبر HTTP. — **HIGH:** `RemoteLlmConnector.kt:100-118` يعرض raw provider body ويجعل كل Throwable retryable. — **HIGH:** `GeminiProvider.kt:18-23,93-100` يضع key في URL. — **HIGH:** `OpenAiProvider.kt:51-78,98-101` blocking call بلا cancellation bridge؛ نفس النمط Gemini/Anthropic. — **MEDIUM:** `CloudBackend.kt:371-375` يfallback لمزود مختلف عند requested ID مجهول. — **MEDIUM:** `RemoteLlmConnector.kt:36-64,129-144` يساوي configured بـhealthy بلا network probe. — **LOW:** لا tests implementation-boundary لـHTTP/key isolation/cancel/failover (`RemoteLlmConnectorStabilityTest.kt:10-58`).
- **`android_intent` — MEDIUM:** `BrowserNavigationPolicy.kt:56-72` لا canonical IP/DNS private-address validation؛ قد تمر صيغ private غير معالجة. — **MEDIUM:** `AndroidIntentConnector.kt:54-75` لا `agentActions`؛ params/url لا تتطابق مع text؛ dead canonical action. — **MEDIUM:** `AndroidIntentConnector.kt:107-121` يعيد URL الكامل مع query/fragment، ما قد يكشف OAuth state/codes. — **LOW:** state يبقى Ready بعد disconnect (`33-52`). — **LOW:** `openUrl` يساوي كل Throwable بـno_handler و`openSettings` بلا catch (`113-129`). — **INFO:** لا connector-level tests (`DeviceActionPolicyTest.kt:6-32`).
- **`voice_mtmd` — HIGH:** `VoiceConnector.kt:34-40,49-71` يعلن healthy بمجرد backend non-null قبل model/permission/device readiness. — **HIGH:** `execute("transcribe")` بلا `agentActions`، bridge لا يعرضه (`VoiceConnector.kt:83-108`; `Connector.kt:66-71`). — **HIGH:** بعد disconnect execute لا يفحص state؛ backend يعيد success sentinel (`VoiceConnector.kt:74-80`; `VoskVoiceBackend.kt:50-53`). — **HIGH:** `runCatching` يبتلع cancellation وbackend يحوّل errors إلى String success (`VoiceConnector.kt:94-101`; `VoskVoiceBackend.kt:64-67`). — **HIGH:** `VoskVoiceBackend.kt:23-43,50-75` race model/release وRecognizer leak. — **MEDIUM:** empty/odd PCM وregex JSON (`VoskVoiceBackend.kt:17,47-63`). — **MEDIUM:** `VoskModelManager.kt:279-370` يبتلع cancellation. — **MEDIUM:** الاسم mtmd/audio-understanding بينما فعلياً Vosk STT (`ConnectorBootstrap.kt:49-63`; `VoiceManager.kt:590-595`). — **MEDIUM:** لا tests لـVoiceConnector/backend lifecycle.
- **`clipboard` — HIGH:** بلا `agentActions`، RuntimeManager يمرر undeclared read/write/clear بلا profile/confirmation (`ClipboardConnector.kt:27-53`; `ConnectorRuntimeManager.kt:46-74`). — **HIGH:** raw PII/unbounded text (`ClipboardConnector.kt:60-74`). — **MEDIUM:** healthy دائماً (`34-36,45-56`). — **MEDIUM:** disconnect no-op يخالف not_connected (`45-51`; `Connector.kt:19-29`). — **MEDIUM:** platform exceptions غير ممسوكة وgeneric runtime retry (`ClipboardConnector.kt:53-82`). — **LOW:** multi-item/non-text وpre-P clear semantics (`59-64,76-82`).
- **`device_apps` — HIGH:** manifest بلا `<queries>`/`QUERY_ALL_PACKAGES` مع `getInstalledApplications`؛ Android 11+ قد يعطي subset (`AndroidManifest.xml:1-35`; `DeviceAppsConnector.kt:73-78`). — **HIGH:** actions comments فقط ولا `agentActions`، و`open_app` shadowed بـAndroidIntent (`20-29`; `ConnectorToolBridge.kt:63-77`; `AgentRouter.kt:150-176`). — **MEDIUM:** normalized URL من policy لا يستخدم؛ raw `Uri.parse` (`DeviceAppsConnector.kt:118-126`). — **MEDIUM:** package/startActivity exceptions تهرب (`51-132`; contract `Connector.kt:62-64`). — **LOW:** Ready/disconnect stale (`38-49`; registry barrier منفصل). — **LOW:** policy-only test ولا DeviceAppsConnector test.
- **`contacts` — HIGH:** بلا agentActions؛ direct runtime read يتجاوز profile NOT_CONFIGURED (`ContactsConnector.kt:68-102`; `ConnectorRuntimeManager.kt:46-71`). — **HIGH:** actions dead في bridge (`23-25`; `ConnectorToolBridge.kt:63-79`). — **HIGH:** UI يستخدم `contacts_local` بينما canonical `contacts` (`ConnectorsScreen.kt:119-128,346-364`; `ContactsConnector.kt:31-59`). — **MEDIUM:** search لا يبحث NUMBER وblank/wildcards توسّع query (`75-83,105-117`). — **MEDIUM:** null cursor/exception تتحول empty/success وpermission state stale (`47-61,68-73,111-128`). — **MEDIUM:** bulk raw names/numbers في output (`84-97,111-126`). — **LOW:** لا tests Contacts/READ_CONTACTS.
- **`system_info` — HIGH:** `ACCESS_NETWORK_STATE` مفقود بينما code يستدعي ConnectivityManager (`AndroidManifest.xml:5-10`; `SystemInfoConnector.kt:92-101`). — **MEDIUM:** battery invalid يصبح `Battery: -1%` success (`76-89`). — **MEDIUM:** `systemSnapshot` يبتلع Failure ويعيد Success (`115-123`). — **MEDIUM:** initial/connect healthy دائماً (`36-54`). — **LOW:** disconnect no-op مقابل contract (`SystemInfoConnector.kt:56`; `Connector.kt:19-28`). — **MEDIUM:** لا agentActions و`network_status` dead في AgentRouter (`58-67`; `AgentRouter.kt:162-175`). — **LOW:** لا tests SystemInfo.
- **`ifttt` — HIGH:** non-2xx string wrapped Success (`IftttConnector.kt:149-165,230-237`). — **HIGH:** key presence = healthy؛ `IFTTT_STATUS` dead (`53-55,84-105,240-243`). — **HIGH:** duplicate instances ServiceLocator vs Bootstrap (`ServiceLocator.kt:597-606`; `ConnectorBootstrap.kt:71-76`). — **HIGH:** exceptions retryable وbare POST بلا idempotency (`149-165`; `ConnectorRuntimeManager.kt:140-160`). — **MEDIUM:** لا agentActions/confirmation (`126-174,217-229`; `Connector.kt:66-71`). — **MEDIUM:** raw key in URL وformat/length/redaction ناقصة (`188-193,217-229`). — **LOW:** policy-only test ولا IftttConnector tests.
- **`n8n` — HIGH:** لا agentActions، action unbounded وundeclared path bypass (`N8nConnector.kt:20-195`; `ConnectorRuntimeManager.kt:46-73`; `AgentRouter.kt:65-80`). — **HIGH:** metadata لا يعلن WEBHOOK و`configureWebhookUrl` لا caller (`N8nConnector.kt:42-67`; `ConnectorAuthStrategy.kt:107-125`; UI/ViewModel). — **HIGH:** execution client بلا redirect policy/timeout/cancellation bridge (`N8nIntegration.kt:15-18,77-91`; `N8nConnector.kt:166-181`). — **MEDIUM:** payload/response بلا bounds (`N8nConnector.kt:167-176`; `N8nIntegration.kt:20-45,64-85`). — **MEDIUM:** HTTPS أي host/query credential boundary (`N8nWebhookUrlPolicy.kt:15-32`; `N8nConnector.kt:51-60`). — **MEDIUM:** idempotencyKey ignored وAgentRouter timeout retryable (`N8nConnector.kt:166-186`; `AgentRouter.kt:87-95`). — **MEDIUM:** health transport-only وerrors null (`N8nConnector.kt:105-121`; `N8nIntegration.kt:82-90`). — **INFO:** policy tests فقط؛ لا N8nConnector/Integration tests.

## 6. النتائج العابرة للموصلات

### `cross_contracts_access`

- **HIGH — undeclared-action grant bypass:** `ConnectorRuntimeManager.kt:46-74` و`AgentRouter.kt:65-88` يسمحان بالمرور عند غياب `authorizationActionId`/matching action؛ أصلح بـdefault-deny وdeclared action لكل side effect.
- **HIGH — GitHub mutation identity missing:** `GitHubConnector.kt:50-75,95-177,249-257` يقبل `create_issue` في dispatch/policy دون `ConnectorAgentAction` أو grant؛ أضفه WRITE/confirmation أو ارفضه.
- **HIGH — deletion cancellation swallowed:** `DataDeletionCoordinator.kt:391-410` يستخدم `runCatching` الذي يلتقط CancellationException؛ أعد رميها ولا تنفذ الخطوات التالية.
- **HIGH — remote deletion exception escapes:** `DataDeletionCoordinator.kt:204-219` يستدعي `deleteOwnedData` بلا boundary؛ أعد `RemoteDataDeletionFailed` مع الحفاظ على cancellation.
- **MEDIUM — stale access profiles:** `DataDeletionCoordinator.kt:273-281` يحذف ملفات prefs دون `ConnectorAccessProfileStore.clear`؛ استخدم store الحي واختبر same-process.
- **MEDIUM — silent revoke failure:** `ConnectorAuthorizationManager.kt:245-255` يهمل Boolean من registry/auth manager؛ ارجع failure typed ولا تعلن disconnected كاذباً.
- **MEDIUM — IFTTT UI side-effect bypass:** `ZapierIftttScreen.kt:391-402` يستدعي execute مباشرة؛ مرره عبر declared confirmation path.
- **INFO — missing tests:** لا DataDeletionCoordinator أو SharedPreferences store tests للـcancel/remote exception/clear/revoke.

### `cross_lifecycle_dispatch`

- **HIGH — legacy tool bypass:** `ToolRegistry.kt:24-38,152-190` و`TaskExecutor.kt:25,40-42` و`AgentWorker.kt:73,82-98` تتجاوز registry/runtime/profile، خاصة Telegram send؛ وحّدها مع canonical bridge.
- **HIGH — disconnect race:** `ConnectorRuntimeManager.kt:89-101,140-148` يفحص generation ثم ينفذ بلا lock/post-check؛ استخدم lifecycle mutex وgeneration قبل كل retry.
- **MEDIUM — AgentRouter direct execute:** `AgentRouter.kt:59-88` لا يستدعي RuntimeManager ولا health/operation tracking؛ أزل المسار أو اربطه بالcanonical.
- **MEDIUM — canonical bridge wiring unproven:** `ToolDispatcher.kt:33-43,360-362` و`ServiceLocator.kt:272-274` لا يبرهنان production injection؛ أضف integration wiring test.
- **MEDIUM — Google parameter misrouting:** `ToolRegistry.kt:196-230` يرسل `max`/`count` بينما connector يقرأ `max_results`؛ وحّد/طبّع.
- **MEDIUM — registry replacement cleanup:** `ConnectorRegistry.kt:82-100` يستبدل instance وunregister cleanup async ويبتلع failure؛ اجعل العملية atomic/awaited.
- **MEDIUM — synthetic healthy/misleading disconnect:** `ConnectorAuthorizationManager.kt:113-123,245-255` يصطنع Google healthy عند غياب runtime ويعيد disconnect true؛ استخدم actual state وpropagate failure.

### `cross_oauth_provider`

- **HIGH — Microsoft User.Read rejected:** `MicrosoftGraphConnector.kt:52-65,164-186` لا يضع User.Read ضمن declared action scopes رغم status action؛ أضفه أو افصل bootstrap scopes، واختبر Outlook profile.
- **MEDIUM — Zapier health/error:** `ZapierConnector.kt:201-222,313-321` لا يفحص status؛ 401/403 قد تصبح Connected/No Zaps. استخدم `response.use` وtyped mapping.
- **MEDIUM — scope/token cache mismatch:** `ConnectorAuthorizationManager.kt:188-223` و`MicrosoftGraphConnector.kt:101-123` لا يخزنان scope fingerprint؛ اربط grant بالـprofile الحالي.
- **MEDIUM — Google expiry health:** `GoogleConnector.kt:95-118,126-149` healthy من token memory بلا expiry؛ track expiry/probe.
- **MEDIUM — Zapier non-2xx success:** `ZapierConnector.kt:232-267,297-310` يعيد Success لرسالة failure؛ أعد Failure typed.
- **LOW — dead legacy callback:** `IntegrationsViewModel.kt:388-419` no-op، بينما `MainActivity.kt:150-159` يكمل مباشرة؛ وحّد callback route واختبر cold/warm deep-link.

### `cross_catalog_ui`

- **HIGH — shared alias navigation:** `ConnectorsScreen.kt:359-363` يمرر catalog ID، و`IntegrationsScreen.kt:152-169` يعالج runtime ID فقط؛ استخدم `row.meta.runtimeId`/descriptor واختبر Google/Microsoft surfaces.
- **MEDIUM — Contacts permission orphan ID:** `ConnectorsScreen.kt:119-128,346-364` يفحص `contacts_local` بينما runtime `contacts`؛ وحّد constant وأعد connect بعد grant.
- **LOW — localization fallback:** `ConnectorCatalog.kt:32-83` Arabic-only، `ConnectorDetailsScreen.kt:115-237` نصوص hardcoded، و`values-es/values-zh/strings.xml:1185-1188` English tabs؛ انقل النصوص للموارد وترجم.
- **LOW — duplicate registry IDs:** `ConnectorRegistry.kt:82-90` يكتب `store[id]=connector` بصمت؛ ارفض duplicate أو اجعل replacement explicit واختبر.

### `cross_tests_failure_security`

- **HIGH — SystemInfo false success:** `SystemInfoConnector.kt:115-122` يسقط component failures ويعيد Success؛ استخدم partial typed result أو Failure.
- **HIGH — Zapier false HTTP success:** `ZapierConnector.kt:255-263,272-275,307-321` لا يميز non-2xx/error JSON؛ أضف mocked status tests.
- **MEDIUM — Registry cancellation swallowed:** `ConnectorRegistry.kt:93-99,113-132` `runCatching` يلتقط cancellation في register/unregister/connect/disconnect؛ أعد رميها.
- **MEDIUM — scanner blind spot:** `tools/security_scan.py:19-43` يفحص regexes/امتدادات محدودة ولا يغطي logs/prompts/telemetry/dynamic URL/response data؛ وسّع scan وصرّح بالاستثناءات.

## 7. الحدود المقصودة التي لا ينبغي عدّها كعيوب تنفيذية قائمة

1. كل `COMING_SOON` هو catalog-only/non-executable: لا adapter، لا OAuth، لا provider calls، وConnect/Manage يجب أن يظلّا محجوبين حتى وجود adapter واختبارات وأدلة.
2. `google_docs`, `google_sheets`, `google_contacts`, `google_tasks`, `google_meet` ليست مثبتة كتنفيذ لمجرد وجود `google` runtime؛ لا phantom adapter يجوز استنتاجه.
3. Microsoft shared runtime (`microsoft_graph`) لا يساوي readiness لكل surface؛ هذا تحديداً موضع defect في rollout لا ترخيص لاعتبار كل السطوح جاهزة.
4. `remote_llm`, `android_intent`, `voice_mtmd`, `clipboard`, `device_apps`, `contacts`, `system_info`, `ifttt`, `n8n` خارج OfficialConnectorCatalog، ولا يجب عدّها خدمات provider.
5. Zapier `trigger_zap`, `pause_zap`, `resume_zap` وIFTTT/external automation gating في `ReleaseScopePolicy` حدود مقصودة؛ لكنها لا تلغي defects في paths المنفذة إن تم تفعيلها.
6. Android app/settings/browser takeover وvoice/device tests تتطلب جهازاً؛ غيابها دليل غير متحقق، لا نجاح.
7. Google writes (`gmail_send`, `calendar_create`) وGitHub mutation approval limits مقصودة، لكن GitHub `create_issue` ما زال يحتاج declaration canonical قبل اعتباره مساراً صحيحاً.

## 8. فجوات الأدلة وما لم يُتحقق منه

- لا provider credentials أو sandbox accounts لأي Google/Microsoft/GitHub/Telegram/Notion/Zapier/IFTTT/n8n أو الخدمات القادمة.
- لا تحقق من consent screens، registered redirect URIs، GIS/Entra/Zapier PKCE callbacks، refresh rotation، remote revoke، quota/rate-limit headers، أو provider response envelopes.
- لا Android emulator/physical device: لا permissions، package visibility، deep links، Activity handlers، ClipboardManager، ContactsProvider، microphone/Vosk model، battery/network capabilities، keystore، process death، أو lifecycle races.
- لا network execution: لا 401/403/404/408/429/5xx، `Retry-After`، timeout، redirects، malformed bodies، multi-page `nextLink/cursor`.
- لا build/Gradle/CI/instrumentation/R8/release-signed APK/store/privacy evidence.
- لا evidence لنتيجة provider-side idempotency أو billing/duplicate behavior بعد retries.
- `failures: []`: لم يقدم المدخل أي نتيجة وكيل فاشلة؛ لذلك لا توجد failed-agent results محددة للإبلاغ، لكن عدم وجود فشل مُبلغ عنه لا يعوض الأدلة التشغيلية الغائبة.

## 9. خطة الإصلاح ذات الأولوية

### P0 — قبل أي ادعاء Ready أو تفعيل side effects

1. **أوقف false success والمسارات غير المحكومة:** أصلح Zapier/IFTTT/SystemInfo، ورفض undeclared actions في `ConnectorRuntimeManager`/`AgentRouter`، وأضف `create_issue`, `send_message`, clipboard/system/local actions بتعريف typed أو امنعها.
2. **أصلح lifecycle/cancellation:** لا تبتلع `CancellationException` في registry/deletion/Notion/voice/HTTP؛ نفّذ cancellable OkHttp bridge، lifecycle mutex، ومنع invocation بعد disconnect.
3. **اقفل الأسرار والـURLs:** أزل Gemini key من URL، امنع custom endpoint HTTP، validate exact host/path وredirect، امنع GitHub off-host `Link` من حمل Bearer، وredact query/body/error.
4. **صحح readiness:** لا تجعل shared runtime أو token presence = provider health؛ افصل `installed/configured/authenticated/healthy/resource-authorized`، واجعل PARTIAL/COMING_SOON غير قابلة للبدء قبل evidence.
5. **ثبت deletion/revoke:** provider revoke حيث متاح، local cleanup result propagation، profile-store clear، وremote deletion typed failure/cancel.

### P1 — صحة العقود والبيانات

1. أصلح Google calendar `days/timeMax/nextPageToken` وGmail `max`/`max_results` وmail-read flow.
2. أصلح Graph pagination، calendarView، 401 refresh، scope fingerprint، 429/Retry-After، account type وsurface-specific health.
3. أصلح GitHub mutation ambiguity/idempotency، pagination/truncation indicator، repository/path encoding، issues-vs-PR filtering، وlegacy split brain.
4. أصلح Notion filter/UUID/cursor/size/schema، Telegram HTTP/body/confirmation، n8n allowlist/config/redirect/idempotency.
5. أصلح Android Contacts/Device Apps/System/Voice/Clipboard contracts: permissions، action schemas، PII/size، package visibility، model/audio validation، lifecycle.

### P2 — metadata/UI/اختبارات

1. وحّد canonical adapter IDs بدلاً من `ProviderNameNameConnector`، وأضف mapping tests لكل alias.
2. حدّث `CONNECTOR.md` والنسخ العمودية المتعارضة، وامنَع copy actionable لعنصر COMING_SOON.
3. أصلح shared catalog-to-runtime navigation و`contacts_local` drift، وترجم النصوص غير العربية وانقل hardcoded labels إلى resources.
4. أضف tests مخصصة لكل surface: exact scopes/endpoints/status، no registration، `adapter_not_installed`، action schemas، pagination/limits، 401/403/429/5xx، refresh/revoke، cancellation، lifecycle، redaction، وambiguous side effects.
5. وسّع static scanners لتفحص credential flow إلى URL/header/log/prompt/telemetry/exception وصرّح بحدودها.

### P3 — إثبات خارجي مستقل لكل سطح

بعد P0–P2 فقط: شغّل Gradle/CI، provider sandbox/account tests، Android emulator/physical tests، deep-link/cold-start/permission/lifecycle tests، release/R8/signed APK inspection، ثم أعد تدقيقاً مستقلاً للـscope/revoke/rate limits/secret redaction. لا تغيّر الحالة إلى Ready قبل تسجيل هذه الأدلة لكل surface على حدة.

## 10. الفصل الحاسم: المراجعة الساكنة مقابل الإغلاق والجاهزية

- **المراجعة الساكنة:** ما سبق يصف ما تشير إليه الملفات والعقود ومسارات التنفيذ والاختبارات الموجودة في الالتزام المحدد. `DEFECTS_FOUND` يعني وجود finding ساكن واحد أو أكثر في المدخل؛ `NO_STATIC_DEFECT_FOUND` يعني عدم العثور على finding ساكن في النتائج، لا أنه Ready.
- **إغلاق finding:** يتطلب إصلاحاً في source، اختباراً مناسباً، نتيجة build/CI، ثم إعادة تدقيق للـdiff والمسار المعني. لا يوجد في المدخل ما يثبت إغلاق أي finding.
- **Production readiness:** تتطلب بالإضافة إلى ذلك provider/device/network/OAuth/secret-storage/release evidence، وهي كلها غير متاحة هنا. لذلك **لا يوجد سطح يمكن إعلان جاهزيته الإنتاجية من هذا التقرير وحده**.

**الخلاصة التنفيذية:** الاكتشاف العددي صحيح (`35+9=44`)، لكن صورة التدقيق الساكن هي **224 finding على البنود الـ44**، إضافة إلى **29 finding عابر**. الأولوية ليست إضافة موصلات جديدة؛ بل إغلاق false success، authorization/lifecycle bypass، credential/URL exposure، readiness misclassification، ثم بناء اختبارات provider/device/CI التي ما زالت غائبة.
