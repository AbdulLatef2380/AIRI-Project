# مراجعة الموصلات المتبقية تحت Coming Soon

**التاريخ:** 2026-10-06  
**الفرع:** `main`  
**مصدر الحقيقة:** `OfficialConnectorCatalog` و`ConnectorRolloutRegistry` في الكود الحالي

## 1. الخلاصة التنفيذية

الكود الحالي يحتوي على **11 موصلاً فعلياً** تحت حالة `COMING_SOON`، وليس 26 كما تذكر بعض الوثائق القديمة. كما يحتوي `RemainingProviderAdapterContracts` على **13 عقداً وصفياً**؛ العقدان الإضافيان هما `microsoft_onedrive` و`microsoft_teams`، وهما أسطح `PARTIAL`/مفعّلة ضمن Microsoft vertical slice وليسا من قائمة Coming Soon الحالية.

جميع الموصلات الإحدى عشرة الحالية:

- `CATALOG_ONLY` في `ConnectorRolloutRegistry`.
- لا يمكنها بدء المصادقة أو فتح متصفح المزود.
- لا تملك runtime adapter قابلاً للتنفيذ.
- تملك عقداً وصفياً غير قابل للتنفيذ (`isExecutable = false`).
- يجب ألا تتحول مباشرة إلى `LIVE` أو `PARTIAL` بمجرد إضافة اسمها إلى registry.

## 2. مصفوفة الحالة الحالية

| الموصل | الدفعة | المصادقة المخططة | health endpoint في العقد | سبب الحجب الحالي | مسار الإغلاق المقترح | الأولوية |
|---|---|---|---|---|---|---|
| Google Meet | Design & Meetings | OAuth2 + PKCE | `meet.googleapis.com/v2/spaces` | لا يوجد Google Meet adapter؛ الـscope الحالي يحتاج تدقيقاً لأنه لا يثبت قراءة اجتماعات قائمة | تثبيت نموذج القراءة والـscopes أولاً، ثم adapter Google مستقل أو امتداد مضبوط للـruntime | P2 — يحتاج قرار API قبل التنفيذ |
| Microsoft SharePoint | Microsoft | OAuth2 + PKCE | Graph `/v1.0/sites/root` | Microsoft runtime موجود، لكن لا توجد أفعال SharePoint أو surface grants في adapter الحالي | إعادة استخدام Microsoft Graph بعد إضافة أفعال المواقع/الملفات read-only وربط scopes بالسطح | P0 — أقل مخاطرة نسبياً |
| Jira | Development | OAuth2 + PKCE | `api.atlassian.com/me` | لا يوجد Atlassian adapter؛ يحتاج 3LO/client/redirect وعقود resource واضحة | بدء health ثم قراءة المشاريع والقضايا بحدود workspace/project | P1 |
| Trello | Productivity | OAuth2 + PKCE | `api.trello.com/1/members/me` | لا يوجد adapter؛ صيغة OAuth في العقد تحتاج تحققاً خاصاً من Trello قبل اعتماد PKCE | تثبيت auth contract الفعلي ثم قراءة boards/lists/cards | P2 — تحقق مصادقة أولاً |
| ClickUp | Productivity | OAuth2 + PKCE | `api.clickup.com/api/v2/user` | لا يوجد adapter أو client configuration | health ثم teams/spaces/lists read-only مع user-selected workspace | P1 |
| Monday.com | Productivity | OAuth2 + PKCE | GraphQL `me` | لا يوجد adapter؛ GraphQL endpoint يحتاج request body ومعالجة أخطاء provider-specific | health `me` ثم boards/items read-only مع حدود pagination | P1 |
| Dropbox | Files | OAuth2 + PKCE | `api.dropboxapi.com/2/users/get_current_account` | لا يوجد adapter؛ Dropbox يعتمد POST endpoints وmetadata/resource selection | health ثم `list_folder` read-only مع cursor وroot selection | P1 |
| Box | Files | OAuth2 + PKCE | `api.box.com/2.0/users/me` | لا يوجد adapter؛ يحتاج root folder scope وpagination وprovider error mapping | health ثم folder listing/read metadata مع root boundary | P1 |
| Canva | Design & Meetings | OAuth2 + PKCE | `api.canva.com/rest/v1/user` | لا يوجد adapter؛ endpoint/scopes ومنتج Connect يحتاج تحققاً قبل تثبيت العقد | health ثم assets/design metadata read-only | P2 — API contract يحتاج تثبيت |
| Airtable | Automation | OAuth2 + PKCE | `api.airtable.com/v0/meta/whoami` | لا يوجد adapter؛ القراءة تتطلب base/table/resource selection، وليست identity فقط | health ثم metadata ثم records read-only بحدود base/table يحددها المستخدم | P1 |
| Zoom | Design & Meetings | OAuth2 + PKCE | `api.zoom.us/v2/users/me` | لا يوجد adapter؛ يحتاج user/meeting scopes وقراءة محدودة | health ثم meetings/user profile read-only | P1 |

> `P0/P1/P2` هنا ترتيب تنفيذ هندسي، وليس حكماً على أهمية المنتج. معيار الترتيب هو إعادة استخدام runtime قائم، وضوح auth contract، وحجم حدود القراءة المطلوبة.

## 3. ملاحظات مهمة على العقود الحالية

### Google Meet

العقد يحدد `meetings.space.created`. هذا لا يكفي وحده لإثبات قراءة كل مساحات/اجتماعات المستخدم. قبل أي تفعيل يجب تحديد ما إذا كان AIRI سيقرأ:

- المساحات التي أنشأها AIRI فقط، أو
- مساحات مرتبطة بالمستخدم/التقويم، أو
- معلومات اجتماع يزوّدها المستخدم صراحةً.

لا يجوز تحويله إلى `LIVE` قبل تثبيت أقل scope صحيح ونموذج بيانات قابل للتنفيذ.

### Trello

العقد يصف Trello على أنه `OAUTH2_PKCE`، لكن يجب إجراء تحقق provider-specific من تدفق OAuth الفعلي، client key، redirect، وطريقة token exchange قبل بناء adapter. لا ينبغي افتراض أن كل OAuth2 provider يطابق قالب PKCE العام في AIRI.

### Microsoft SharePoint

هو أفضل مرشح للدفعة التالية لأنه يشترك مع `microsoft_graph`، لكن مشاركة runtime وحدها لا تعني أن surface منفذ. يجب إضافة أفعال SharePoint وت grants خاصة به، مع إبقاء `Sites.Read.All` خلف موافقة واضحة وعدم توسيعها إلى كتابة أو إدارة.

### Dropbox وMonday.com

كلاهما يحتاج request shapes خاصة بالمزود، وليسا مجرد `GET health + GET read`. Dropbox يستخدم endpoints POST وpagination cursor، وMonday يستخدم GraphQL. لذلك لا يصح توسيعهما عبر `ProviderTokenConnector` البسيط المستخدم للموصلات السبعة السابقة.

## 4. سياسة التفعيل المستقبلية

### المرحلة A — قبل كتابة adapter

لكل موصل:

1. تثبيت provider contract من الوثائق الرسمية: auth, redirect, scopes, health, read endpoint.
2. تحديد `catalogId` و`runtimeId` وsurface grants بوضوح.
3. تحديد أقل صلاحية قراءة ممكنة، وعدم استخدام scope شامل دون ضرورة.
4. تحديد endpoint قراءة أولي bounded ومحدود الحجم.
5. تحديد شكل أخطاء المزود: `401`, `403`, `429`, `5xx`, timeout، انتهاء token.
6. تسجيل client/redirect/configuration كمتطلبات منفصلة عن الكود.

### المرحلة B — vertical slice قراءة فقط

لا يُغلق الموصل إلا بعد اكتمال المسار التالي:

`permission preview → access profile → provider auth → secure storage → health check → read action → healthy tool exposure`

ويجب أن يتضمن:

- adapter مسجلاً في `ConnectorBootstrap`.
- mapping صريحاً من الكتالوج إلى runtime.
- مصادقة typed، مع state/PKCE/replay protection عند OAuth.
- تخزيناً مشفراً وعدم وضع credential في logs أو prompts.
- health check حقيقياً، لا مجرد وجود token.
- أفعالاً typed في `ConnectorToolBridge` ومربوطة بمنحة السطح.
- `disconnect()` يمسح token/profile/callback state.
- اختبارات unit بعزل HTTP transport، واختبارات lifecycle وpermission.
- دليل provider/CI على commit المطابق قبل إعلان `LIVE`.

### المرحلة C — الكتابة لاحقاً وبشكل منفصل

إذا احتاج الموصل إلى كتابة:

1. لا تُضاف الكتابة إلى أول vertical slice تلقائياً.
2. يضاف capability مستقل بصلاحية `WRITE` أو `DESTRUCTIVE`.
3. يبقى خلف confirmation مستقل حتى مع `FULL_ACCESS`.
4. يملك idempotency key، typed payload، audit trace، وerror recovery.
5. يضاف `READ_WRITE` فقط إذا كان provider scope وadapter يدعمانه فعلياً.
6. لا تتحول القراءة إلى كتابة لمجرد أن المزود يوفر endpoint كتابة.

## 5. بوابة الإغلاق الموحدة

لا يُسمح بتغيير `soon(...)` إلى `partial(...)` أو إضافة المعرف إلى `liveCatalogIds` إلا إذا تحققت كل النقاط التالية:

- adapter حقيقي مسجل وقابل للاستدعاء.
- `ConnectorRolloutRegistry.requiredAdapter` يطابق class/runtime فعلياً.
- auth flow مكتمل ومختبر، أو credential flow واضح ومشفّر.
- health check ناجح بحساب اختبار أو provider sandbox.
- action read واحدة على الأقل تعمل وتعيد نتيجة bounded.
- grants وaccess profile تمنع cross-surface access.
- حالات الرفض والانتهاء والحدود الشبكية typed وقابلة للتشخيص.
- disconnect/revoke يمسح كل الأسرار والحالة المرحلية.
- اختبارات replay/cancel/timeout/401/403/429/5xx موجودة.
- Android unit/build/CI evidence مرتبط بنفس SHA.
- توثيق الموصل والعقد والقيود محدث مع الكود.

## 6. ما يجب إصلاحه في الحوكمة والوثائق

- تحديث الوثائق التي تقول إن المتبقي 26؛ الرقم الصحيح في الكود الحالي هو 11 `COMING_SOON` و13 عقداً وصفياً تشمل سطحين `PARTIAL`.
- تقوية `ProviderAdapterContractTest` ليؤكد أن **كل** `COMING_SOON` في الكتالوج له عقد واحد بالضبط، لا أن يختبر تقاطع القائمتين فقط.
- منع ازدواجية القوائم مستقبلاً عبر اشتقاق readiness من سجل عقود/adapter واحد مع إبقاء التنفيذ محمياً افتراضياً.
- عدم استخدام `PARTIAL` كبديل عن دليل provider فعلي؛ `PARTIAL` تعني vertical slice محدوداً ومعلن القيود، وليست `LIVE` كاملة.
- إبقاء `CATALOG_ONLY` هو الوضع الافتراضي لأي موصل جديد.

## القرار

الموصلات المتبقية يجب التعامل معها كدفعات vertical slices صغيرة، لا كإغلاق جماعي. الترتيب المقترح هو:

1. **Microsoft SharePoint** لإعادة استخدام Microsoft Graph.
2. **ClickUp / Monday / Dropbox / Box / Airtable / Zoom** بحسب توفر client configuration وحسابات sandbox.
3. **Jira** بعد تثبيت Atlassian 3LO ونطاقات القراءة.
4. **Google Meet / Trello / Canva** بعد حسم عقود API وscopes الخاصة بها.

حتى ذلك الحين، إبقاؤها `COMING_SOON` و`CATALOG_ONLY` هو السلوك الصحيح والآمن، وليس نقصاً في الواجهة.
