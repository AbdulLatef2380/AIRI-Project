# حد أفعال الجهاز

**الحالة:** `IMPLEMENTATION_COMPLETE` لحارس `DeviceAppsConnector` بين discovery المحلي وأفعال النقل خارج AIRI. **`RUNTIME_VERIFICATION_PENDING`** لواجهة تسليم المستخدم على جهاز Android حقيقي. لا يدعي العقد أن AIRI يستطيع فتح تطبيقات أو روابط تلقائياً.

## المسار المنفذ

`DeviceAppsConnector` يبقي `list_apps` و`find_app` قابلين للتنفيذ محلياً. قبل أي `startActivity` يفحص `DeviceActionPolicy` action المطلوب:

| Action | القرار |
|---|---|
| `list_apps` و`find_app` | قراءة محلية مسموحة |
| `open_app` | `user_takeover_required` لأن فتح تطبيق آخر ينقل التحكم خارج AIRI |
| `open_url` بعنوان HTTP(S) عام | `user_takeover_required` وفق `BrowserNavigationPolicy.OPEN_EXTERNAL` |
| `open_url` محلي أو غير HTTP(S) | `blocked_by_policy` |

يمر تقييم URL عبر `BrowserNavigationPolicy`؛ لذلك لا يسمح connector بعنوان localhost أو نطاق private/local/internal أو scheme مثل `file:`. يعود الموصل بمخرجات failure machine-readable قبل `startActivity`، لا بنجاح صوري أو محاولة صامتة.

## الفجوات الصريحة

يلزم لاحقاً UI takeover حقيقي يعرض التطبيق أو الرابط ويطلب من المستخدم النقر بنفسه، ثم مسار task-owned approval/resume إذا كان الوكيل هو من اقترح الإجراء. لا يفتح هذا العقد نظام التحكم في Windows أو جهازاً بعيداً؛ يبقى ذلك جزءاً من AIRI Mesh/paired-device trust غير المنفذ.

## الأدلة

| الدليل | التغطية |
|---|---|
| `DeviceActionPolicyTest` | discovery، public URL takeover، private/file URL block |
| `:app:compileDebugKotlin` | policy موصولة بمسار الموصل قبل `startActivity` |
