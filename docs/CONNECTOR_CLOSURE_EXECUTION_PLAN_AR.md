# خطة تنفيذ إغلاق طبقة الموصلات في AIRI

**الفرع:** `main`  
**نقطة البداية:** `1b9b4be2a617ef993fa1c330b650e9e638a2637c`  
**النطاق:** 35 سطح كتالوج + 9 معرفات runtime = 44 سطحاً

## الهدف المنتجّي

عند اختيار المستخدم موصلاً:

1. يحدد السطح الذي يريد استخدامه.
2. يرى الصلاحيات التي ستُمنح وأقل نطاقات المزود المطلوبة.
3. يختار مستوى الوصول صراحةً: قراءة، قراءة/كتابة، أو وصول كامل إذا كان adapter والمزود يدعمانه.
4. ينتقل مباشرة إلى مصادقة المزود أو إدخال الاعتماد المناسب.
5. تحفظ AIRI الاعتماد في التخزين الآمن، وتربط callback/credential بمنحة المستخدم.
6. تجري فحص صحة حقيقياً.
7. لا تعرض للوكيل إلا الأفعال التي تغطيها المنحة والصحة الحالية.
8. تتطلب الأفعال الحساسة تأكيداً مستقلاً حتى مع `FULL_ACCESS`.
9. تسمح بالإلغاء والفصل والمسح الكامل دون بقاء token أو profile أو callback state.

> «الوصول الكامل» لا يعني منح صلاحيات إدارية تلقائياً؛ يعني الحد الأعلى الذي يدعمه المزود والـadapter بعد اختيار المستخدم، مع بقاء الحذف والأفعال الحساسة خلف تأكيد مستقل.

## المرحلة الأولى — إغلاق مسار الاختيار والمصادقة والحوكمة

### العمل

- توحيد كل نقاط الدخول (`ConnectorsScreen`, `ConnectorDetailsScreen`, `IntegrationsScreen`, deep links) على `ConnectorAuthorizationManager`.
- منع أي UI أو deep link من بدء OAuth أو health-check لموصل محجوب في release.
- جعل اختيار `ConnectorAccessProfile` خطوة صريحة قبل المصادقة عندما يكون السطح قابلاً للتنفيذ.
- تحويل هوية catalog surface إلى runtime adapter بشكل واضح، وحفظ المنحة على هوية السطح لا على runtime المشترك فقط.
- عرض قائمة الأفعال والصلاحيات والمنح المطلوبة قبل فتح شاشة المزود.
- حفظ OAuth scopes وPKCE state وربطهما بملف الوصول وقت البدء؛ رفض callback عند تغير الملف أو إعادة استخدام state.
- حفظ الاعتمادات عبر `SecureStorage`/`ConnectorAuthManager` فقط، ثم health-check قبل إعلان `READY`.
- إبقاء enforcement داخل `ConnectorRuntimeManager` و`ConnectorToolBridge` بعد المصادقة، لا في الواجهة فقط.
- إضافة سجل تدقيق durable لمنح/تغيير/إلغاء الصلاحيات ونتائج المصادقة دون أسرار.
- تغطية unknown action، المنحة المفقودة، تغيير scope أثناء OAuth، callback replay، disconnect، ومسح الحساب.

### بوابة قبول المرحلة الأولى

- اختيار الموصل يؤدي إلى تدفق واحد واضح: **permission preview → profile choice → provider auth → health check → ready**.
- لا يوجد OAuth أو اتصال يبدأ من مسار غير المدير المركزي.
- لا يُنشأ OAuth state قبل وجود profile صالح.
- `NOT_CONFIGURED` يرفض كل أفعال الوكيل.
- `READ_ONLY` لا يسمح بالكتابة، و`READ_WRITE` لا يسمح بالحذف/الإدارة.
- `FULL_ACCESS` لا يلغي confirmation للأفعال الحساسة.
- كل سطح منفذ له `catalogId`, `runtimeId`, actions, provider grants, profile enforcement.
- فحوص المصدر والاختبارات مرتبطة بـSHA، وAndroid build يمر على SHA نفسه.

### ما بدأ تنفيذه الآن

أضيف حاجز release داخل `ConnectorAuthorizationManager` قبل أي `begin()` أو `completeOAuth()` لمعرفات الأتمتة الخارجية `zapier`, `ifttt`, `n8n`. هذا يمنع مسارات Integrations العامة وdeep links من تجاوز سياسة الإصدار حتى لو لم تمر عبر شاشة Settings.

## المرحلة الثانية — إكمال adapters والأدلة الحية

### العمل

تقسم إلى دفعات مزودين، ولا يُعلن السطح مغلقاً إلا بعد اكتمال adapter واختبار حقيقي:

1. **Google:** Gmail, Calendar, Drive ثم Docs/Sheets/Contacts/Tasks/Meet عند تنفيذ adapters الفعلية.
2. **Microsoft Graph:** Outlook, Calendar, OneDrive, Teams ثم SharePoint/To Do عند اعتماد العقود.
3. **GitHub / Telegram / Notion:** إكمال الأفعال المدعومة، revoke/disconnect، والتحقق من الحسابات الحقيقية.
4. **Automation:** Zapier/IFTTT/n8n بعد قرار release مستقل، تسجيل provider apps وredirects، واختبار webhook/idempotency.
5. **Local/System/API:** Android permissions، voice, contacts, clipboard, device apps, system info, remote LLM مع حدود الخصوصية والصحة.
6. **COMING_SOON:** لا تُعرض كجاهزة؛ لكل سطح إما adapter كامل أو يبقى صراحةً غير منفذ.

### بوابة قبول المرحلة الثانية

- لكل سطح دليل provider/device/CI/release مستقل.
- تغطية success و401 و403 و429 و5xx وtimeout وexpiry وrefresh وcancel وdeny وreplay وdisconnect.
- لا توجد adapters وهمية أو حالة `READY` بلا health evidence.
- الأفعال الكتابية والتدميرية والإدارية typed ومؤكدة ومربوطة بسياق تنفيذ durable.
- اختبار Android فعلي/CI على SHA المطابق، ثم توقيع ونشر ومراجعة الخصوصية والمتجر.
- تبقى الأسطح بلا أدلة خارجية في `BLOCKED_EXTERNAL_EVIDENCE` ولا تتحول إلى `CLOSED`.

## ترتيب التنفيذ الحالي

1. تثبيت حاجز authorization المركزي — **بدأ الآن**.
2. إضافة نموذج `AccessProfileRequired` وواجهة اختيار المنحة قبل المصادقة.
3. ربط جميع مسارات الدخول بالهوية السطحية الصحيحة.
4. إضافة اختبارات regression لمسارات UI/deep-link والـcallback.
5. تشغيل الفحوص المحلية ورفع commit مرحلي إلى `main`.
6. بدء دفعة Google/Microsoft/GitHub/Telegram/Notion بحسب توفر إعدادات المزود.

## قرار الصلاحيات

لا يجوز تنفيذ طلب «أعط Airi كل الصلاحيات» كمنح صامتة. الصيغة الآمنة والمطابقة لنية المستخدم هي: تعرض AIRI خيار `FULL_ACCESS` عندما يدعمه adapter، ويؤكده المستخدم، ثم يطلب المزود نطاقاته الفعلية، وتبقى كل عملية حساسة خلف تأكيد مستقل.
