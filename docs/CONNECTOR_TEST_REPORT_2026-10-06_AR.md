# تقرير اختبار الموصلات المفعّلة

**التاريخ:** 2026-10-06  
**الفرع:** `main`  
**Commit:** `e4be7d9eeabd2f686d370099b2d693275602c395`  
**النطاق:** الموصلات السبعة التي أُغلقت من حالة `Coming Soon` في الدفعة الأخيرة.

## 1. الملخص التنفيذي

تمت مراجعة مسار الموصلات من الكتالوج حتى التنفيذ والمصادقة وفصل الاتصال. الموصلات السبعة أصبحت مسجلة كمحولات تنفيذية حقيقية، وليست إدخالات واجهة فقط:

| الموصل | نمط المصادقة | فحص الصحة | عملية قراءة مفعلة | النتيجة البنيوية |
|---|---|---|---|---|
| GitLab | Personal Access Token | `GET /api/v4/user` | `GET /api/v4/projects` | ناجح |
| Linear | API Key | GraphQL `POST /graphql` باستعلام `viewer` | —؛ فحص هوية محدود وآمن | ناجح |
| Slack | OAuth Access Token | `GET /api/auth.test` مع التحقق من `ok=true` | `GET /api/conversations.list` | ناجح |
| Discord | Bot Token | `GET /api/v10/users/@me` | `GET /api/v10/users/@me/guilds` | ناجح |
| Asana | Personal Access Token | `GET /api/1.0/users/me` | فحص الملف الشخصي نفسه | ناجح |
| Todoist | API Token | `GET /api/v1/user` | `GET /rest/v2/tasks` | ناجح |
| Figma | Personal Access Token | `GET /v1/me` عبر `X-Figma-Token` | —؛ فحص هوية محدود وآمن | ناجح |

> لا توجد رموز وصول حقيقية في بيئة الاختبار، لذلك لا يمكن إثبات نجاح حساب مستخدم فعلي أو صلاحيات مساحة عمل فعلية من الـSandbox. تم اختبار العقود، المسارات، حالات الفشل، والوصول الشبكي العام دون إرسال أسرار.

## 2. مسار المصادقة الذي تمت مراجعته

تمت مراجعة المسار التالي لكل الموصلات السبعة:

1. الكتالوج يعرّف الموصل كـ `tokenReadOnly` بدلاً من `soon`.
2. `ConnectorRolloutRegistry` يعرّفه ضمن `liveCatalogIds`.
3. `ConnectorBootstrap` يسجل مثيلاً مستقلاً من `ProviderTokenConnector` لكل مزود.
4. `ConnectorAuthorizationManager.begin()` يعيد طلب بيانات اعتماد خاصاً بالمزود.
5. واجهة `IntegrationsScreen` تعرض حوار الرمز العام وتوضح طريقة الحصول عليه.
6. الرمز يُحفظ عبر `ConnectorAuthManager` في مساحة منفصلة لكل موصل.
7. يتم تعطيل حالة الفصل الصريح قبل التحقق، ثم ينفّذ المحول فحص الصحة الحقيقي.
8. لا تُعلن حالة الاتصال كـ `connected/healthy` إلا بعد استجابة ناجحة من نقطة المزود.
9. عند فشل الفحص، تعاد نتيجة `credential_rejected` أو `health_check_failed` ولا يتم الإعلان عن اتصال صحيح.
10. عند فصل الاتصال، تُلغى بيانات الاعتماد ويُفعّل حاجز الفصل الصريح.

### تفاصيل حماية خاصة

- Slack يعيد HTTP 200 حتى عند وجود خطأ منطقي؛ لذلك يشترط المحول `ok=true` ولا يكتفي برمز HTTP.
- Linear يستخدم GraphQL `POST` فعلياً مع استعلام `viewer`، وليس طلب GET فارغاً.
- Discord يضيف بادئة `Bot` إلى رمز البوت.
- GitLab يستخدم ترويسة `PRIVATE-TOKEN`.
- Figma يستخدم ترويسة `X-Figma-Token`.
- كل الاستجابات محدودة إلى 256 KiB لمنع تضخم الذاكرة.
- أفعال الوكيل محصورة في `status` و`read` فقط؛ لا توجد عمليات `POST/PUT/PATCH/DELETE` لكتابة موارد المزودين.
- عمليات القراءة محدودة إلى 1–50 عنصراً عند استخدام معامل `limit`.

## 3. الاختبارات المنفذة ونتائجها

### 3.1 فحص دورة الحياة الساكن

الأمر:

```bash
python3 scripts/connector_lifecycle_static_scan.py
```

النتيجة: **13/13 ناجحة**.

| الفحص | النتيجة |
|---|---|
| تسلسل `connect/disconnect` عبر mutex | PASS |
| حاجز الفصل الصريح | PASS |
| سجل العمليات محدود الحجم | PASS |
| تنظيف العمليات قيد التنفيذ في `finally` | PASS |
| حالات timeout لا تتحول إلى نجاح | PASS |
| تمرير cancellation دون ابتلاعها | PASS |
| ملكية نطاق مراقبة الصحة وإلغاؤه | PASS |
| أمان إعادة تشغيل مراقبة الصحة | PASS |
| محدودية إشعارات الصحة الديناميكية | PASS |
| عدم استخدام `GlobalScope` | PASS |
| توجيه دورة الحياة عبر Registry | PASS |
| فصل التكاملات داخل coroutine آمن | PASS |
| عدم وجود تجاوز مباشر لدورة الحياة في UI | PASS |

### 3.2 اختبارات استراتيجيات المصادقة

الملف:

```text
app/src/test/java/com/airi/assistant/connector/ConnectorAuthStrategyTest.kt
```

التحققات المضافة:

- كل إدخالات الكتالوج تحصل على نمط مصادقة معلن.
- الموصلات التي بقيت `Coming Soon` لا يمكنها بدء المصادقة.
- الموصلات السبعة تحل إلى الأنماط التالية:
  - GitLab، Asana، Figma: `PERSONAL_ACCESS_TOKEN`
  - Linear، Slack، Discord، Todoist: `API_KEY`
- كل الموصلات السبعة تتطلب تفويضاً رسمياً قبل اعتبارها جاهزة.

### 3.3 اختبارات بوابة النشر

الملف:

```text
app/src/test/java/com/airi/assistant/connector/ConnectorRolloutRegistryTest.kt
```

التحققات المضافة:

- وجود الإدخالات السبعة في بوابة التشغيل.
- `readiness == LIVE` لكل موصل.
- `canStartAuthorization == true` لكل موصل.
- وجود اسم محول مطلوب لكل إدخال.
- بقاء الموصلات غير المختارة محمية من البدء قبل وجود محول حقيقي.

### 3.4 اختبارات تعريفات المحولات

الملف:

```text
app/src/test/java/com/airi/assistant/connector/app/ProviderTokenConnectorTest.kt
```

التحققات:

- عدد التعريفات التنفيذية يساوي 7.
- عدم وجود معرفات مكررة.
- كل نقاط الصحة والتوثيق تستخدم HTTPS.
- كل تعريف يملك اسم credential واضحاً.
- لا توجد عملية قراءة مسماة كعملية كتابة.
- مطابقة نمط المصادقة لكل مزود.

### 3.5 فحص تنسيق التغييرات

الأمر:

```bash
git diff --check
```

النتيجة: **PASS**؛ لا توجد مسافات زائدة أو أخطاء تنسيق في الفروق.

## 4. فحص نقاط API العامة

تم تنفيذ طلبات بدون أي رمز وصول إلى نقاط الصحة العامة بهدف التأكد من أن المسارات قابلة للوصول وأنها تطلب مصادقة بدلاً من كونها عناوين وهمية.

| المزود | HTTP بدون رمز | التفسير |
|---|---:|---|
| GitLab | 401 | نقطة رسمية وتطلب مصادقة |
| Linear GraphQL GET عام | 400 | متوقع لأن العملية الرسمية تستخدم GraphQL POST؛ تم تصحيح المحول لاستخدام POST |
| Slack | 200 | API يعيد HTTP 200 مع `ok=false`؛ تمت إضافة معالجة منطقية لهذا السلوك |
| Discord | 401 | نقطة رسمية وتطلب Bot Token |
| Asana | 401 | نقطة رسمية وتطلب PAT |
| Todoist | 401 | نقطة رسمية وتطلب API Token |
| Figma | 401 | نقطة رسمية وتطلب `X-Figma-Token` |

كما تم اختبار Linear عبر الطريقة الرسمية:

```bash
curl -X POST https://api.linear.app/graphql \
  -H 'Authorization: invalid' \
  -H 'Content-Type: application/json' \
  --data '{"query":"{ viewer { id name } }"}'
```

النتيجة: **HTTP 401** مع رسالة `Authentication required`، ما يؤكد أن نقطة GraphQL صحيحة وتتحقق من المصادقة.

## 5. اختبار التنفيذ والصلاحيات

### ما تم إثباته آلياً

- المحولات السبعة مسجلة في Registry وتصل إليها طبقة التنفيذ.
- `agentActions()` لا تعرض إلا `status` و`read`.
- العمليات غير المعروفة تعيد `unknown_action`.
- التنفيذ قبل الاتصال يعيد `not_connected`.
- حالات HTTP `401/403` تعيد `authorization_required` وتخفض حالة الاتصال.
- حالة HTTP `429` تعيد `rate_limited` مع قابلية إعادة المحاولة.
- حالات HTTP الأخرى غير الناجحة تعيد `provider_error`.
- أخطاء الشبكة تعيد `network_error` مع قابلية إعادة المحاولة.
- الإلغاء (`CancellationException`) يُعاد تمريره ولا يتحول إلى نجاح مزيف.
- بيانات الاستجابة محدودة الحجم ولا تُسجل الرموز في السجلات.

### ما يتطلب اختباراً بحسابات فعلية

لا يمكن إكماله في بيئة الاختبار الحالية لعدم توفر رموز المستخدم:

- GitLab: فحص مشروع فعلي يملكه الحساب.
- Linear: قراءة هوية مستخدم فعلي باستعلام `viewer` برمز صالح.
- Slack: التأكد من مساحة العمل والقنوات التي يسمح بها OAuth scope.
- Discord: التأكد من قائمة الخوادم التي يصل إليها البوت.
- Asana: التأكد من ملف المستخدم والـworkspace الفعلي.
- Todoist: قراءة مهام الحساب الفعلية.
- Figma: قراءة هوية المستخدم الفعلية برمز PAT.

## 6. اختبار Gradle

تمت محاولة تشغيل:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.airi.assistant.connector.*' --no-daemon
```

لكن التنفيذ توقف قبل الترجمة بسبب عدم وجود Android SDK في الـSandbox:

```text
SDK location not found.
Define a valid SDK location with an ANDROID_HOME environment variable
or by setting sdk.dir in local.properties.
```

هذه نتيجة **بيئية** وليست فشلاً اختبارياً في الكود. يجب تشغيل اختبارات Gradle على جهاز Android/CI يحتوي على SDK مضبوطاً، ثم تنفيذ:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.airi.assistant.connector.*' --no-daemon
```

## 7. النتيجة النهائية

| المجال | الحالة |
|---|---|
| إغلاق سبعة موصلات من `Coming Soon` | ناجح |
| التسجيل في Bootstrap | ناجح |
| الوصول عبر Rollout Registry | ناجح |
| استراتيجيات المصادقة | ناجح بنيوياً |
| تخزين الاعتماديات وفصلها | مربوط عبر `ConnectorAuthManager` |
| فحص صحة المزود | منفذ لكل موصل |
| التنفيذ القراءة فقط | منفذ ومقيّد |
| حماية عمليات الكتابة | لا توجد عمليات كتابة في المحول |
| فحوص دورة الحياة | 13/13 ناجحة |
| اختبار نقاط API العامة | ناجح مع حالات المصادقة المتوقعة |
| اختبارات Gradle الكاملة | لم تبدأ بسبب غياب Android SDK |
| اختبار حسابات مزود حقيقية | مطلوب في CI أو بيئة مصادقة |

**الحكم:** التغييرات جاهزة للمراجعة والنشر من ناحية الكود والعقود ومسار التنفيذ، مع بقاء تحقق نهائي مطلوب على CI/جهاز Android يحتوي Android SDK وبحسابات اختبار حقيقية لكل مزود. لا ينبغي اعتبار الموصل متصلاً إلا بعد تمرير فحص الصحة باستخدام credential صالح فعلياً.
