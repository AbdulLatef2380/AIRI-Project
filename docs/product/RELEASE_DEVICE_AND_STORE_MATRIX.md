# مصفوفة تحقق الجهاز والمتجر للإطلاق

> **الحالة:** `RUNTIME_VERIFICATION_PENDING`. هذه مصفوفة تنفيذ، وليست نتيجة اختبار. لا يجوز تحويل أي صف إلى `TESTED` أو `READY` قبل إرفاق جهاز/نسخة/وقت/نتيجة وشاهد للأثر أو للفشل الآمن.

## نطاق الأجهزة

| فئة الجهاز | الحد الأدنى | ما يثبت |
|---|---:|---|
| محاكي CI | API 29، `x86_64` | تشغيل `connectedDebugAndroidTest` وفحوص Room/project isolation المجمعة في workflow. |
| هاتف فعلي أساسي | API 35 أو 36، `arm64-v8a` | مكتبة JNI، الأذونات، الخدمات الأمامية، WorkManager، browser handoff، التخزين، TalkBack، وحجم خط كبير. |
| هاتف حد أدنى | API 26، `arm64-v8a` أو ABI مدعوم | توافق `minSdk=26`، التخزين والـTLS والتراجع الآمن عند الميزات غير المتاحة. |
| هاتف دون حساب provider | أي من السابق | الرفض الواضح وعدم تسريب السر عند غياب حساب/مفتاح/إذن/متصفح. |

## مسارات الجهاز ذات الأولوية

| الأولوية | المسار | إجراء المستخدم / المحفز | نتيجة قبول قابلة للرصد | دليل يجب حفظه |
|---|---|---|---|---|
| P0 | مسار مشروع → ملف → موافقة → تطبيق | ينشئ المستخدم ملفاً مُداراً ويقترح تعديل نصي ثم يرفضه/يوافق عليه. | الرفض لا يغير البايتات؛ الموافقة تطبق revision مرة واحدة، تربط evidence، وتتعافى بأمان بعد قتل العملية. | سجل task/run/step منقح، hash revision، artifact/evidence، ونتيجة `ProjectResourceIsolationTest`. |
| P0 | عزل المشروع | ينشئ مشروعان A/B بملفات/معرفة/ذاكرة/أسرار/artifacts مختلفة. | لا تُرى أو تُقرأ أو تُستهلك موارد B من A؛ تظل الاستعلامات scoped. | نتيجة instrumentation وحالة رفض لكل نوع مورد. |
| P0 | تقويم AgentLoop | مشروع نشط، طلب `calendar_create`، ثم Allow/Deny/Expiry وقتل عملية. | proposal خاص قبل grant؛ إدراج provider واحد بعد claim؛ لا retry تلقائي؛ المحذوف/المنتهي لا يبقي payload خاصاً. | شاشة Trust Center، سجل منقح، event provider أو failure، artifact evidence، وسجل process recreation. |
| P0 | Browser handoff | يطلب المستخدم فتح رابط عام مدعوم ثم يلغي/يؤكد أو لا يوجد handler. | لا launch قبل التأكيد؛ تظهر URL المطبعة؛ launch واحد فقط بعد الاختيار؛ يفشل بأمان بلا handler. | فيديو/لقطات شاشة، log منقح، ونتيجة إلغاء. |
| P0 | WorkManager schedule/run-now | ينشئ مهمة agent، يشغل Run Now، يعيد المحاولة ويقتل/يعيد فتح التطبيق. | لا يستبدل cadence؛ لا يقبل manual duplicate؛ يظهر task المرتبط والنتيجة؛ يمسح marker من worker المطابق فقط. | WorkManager diagnostics، شاشة Execution Center، وسجل Doze/OEM. |
| P0 | توقيع release وJNI | يبني APK/AAB signed من CI، يثبت على `arm64-v8a`. | R8 والموردات المصغرة مكتملة، `libairi_native.so` موجود، التطبيق يبدأ ولا يوجد crash startup. | SHA-256، mapping، CI artifact، نتيجة تثبيت/versionCode، logcat startup. |
| P1 | التفضيلات الصريحة | يدخل سياق عمل/هدف، يفعّل المشاركة ثم يعطلها ويعيد التشغيل. | لا يصل شيء إلى prompt قبل opt-in؛ يختفي عند التعطيل؛ لا يظهر في الذاكرة/الأدلة/مهمة. | لقطة UI، inspect منقح لبناء prompt في بيئة اختبار، ونتيجة restart. |
| P1 | جلسات الدردشة | Rename/Pin/Delete لجلسة مختارة، ثم تبديل/إعادة تشغيل. | العملية تستهدف session المختار، pin يبقى أولاً، الحذف يطلب تأكيداً ويستبدل current session بأمان. | Room state قبل/بعد، شاشة RTL/LTR، TalkBack. |
| P1 | ملفات ومرفقات | يختار ملفاً وصورة وكاميرا ثم يلغي أو يحرم الإذن. | لا crash، لا read بلا إذن، URI غير قابل للوصول يعرض failure واضحاً، وshare/export لا يرسل دون اختيار المستخدم. | لقطات، logs منقحة، ونتائج permission denial. |
| P1 | صوت/Hotword | يمنح/يرفض mic، يبدأ/يوقف wake word، ينتقل foreground/background. | لا يبدأ capture بلا mic؛ تظهر خدمة foreground مع إشعار واضح؛ wake word يفتح AIRI فقط ولا ينفذ أداة. | شاشة الإذن/الإشعار، battery/foreground observation، وlogcat منقح. |
| P1 | الموصلات والأسرار | GitHub project secret مفقود/موجود ومشروع مخالف. | لا global fallback؛ لا raw preview/copy؛ مِلْكية task/run/step شرط للاستهلاك؛ provider failure لا يعرض السر. | UI presence فقط، logs منقحة، نتائج adapter/no-network والاختبار الحي عند توفر حساب تجريبي. |

## مصفوفة الصلاحيات والإفصاح

| الإذن أو الإعلان | السطح المصرح به | اختبار الرفض | شرط متجر/إفصاح قبل الإصدار |
|---|---|---|---|
| `RECORD_AUDIO` وforeground microphone | STT/voice/hotword بعد فعل مستخدم واضح. | إيقاف/شرح واضح عند denial وعدم فتح `AudioRecord`. | وصف voice/foreground service وسياسة احتفاظ الصوت. |
| `CAMERA` | إرفاق صورة من زر الدردشة فقط. | لا camera launch أو crash عند denial. | Data safety للمرفق والصور إن حفظت/أرسلت. |
| `READ_CALENDAR` / `WRITE_CALENDAR` | قراءة/إنشاء تقويم مقيد بموافقة Trust Center. | لا provider I/O أو proposal replay بلا الإذن والموافقة. | justification للتقويم ووصف الآثار والاحتفاظ. |
| `READ_CONTACTS` | Contacts connector عندما يختاره المستخدم. | لا listing أو sync ضمني عند denial أو disconnect. | justification واضح؛ قرر إزالة الإعلان من first release إن لم يكن connector ضمن رحلة المستخدم الأولى. |
| `SCHEDULE_EXACT_ALARM` | **غير معلن في الإصدار الحالي**؛ أزيل لأن AlarmTool لا يملك مسار تنفيذ/receiver مملوكاً. | لا يجب أن يظهر prompt أو إعداد أو قدرة agent alarm؛ أي إعادة إدراج تتطلب عقداً محلياً مستقلاً وطلباً صريحاً. | لا يوجد إفصاح/اختبار متجر لهذا الإصدار. إن أعيد لاحقاً، يلزم تبرير Play واختبار fallback قبل إضافة manifest. |
| `POST_NOTIFICATIONS` | تنبيه مرئي بعد خيار مستخدم. | التطبيق usable بلا إذن ولا spam. | وصف الإشعارات/القنوات وإلغاء الاشتراك. |
| `INTERNET` وFirebase | نموذج/موصل اختاره المستخدم؛ analytics/crash reporting بعد consent. | local path يعمل حيثما أمكن؛ لا analytics/crash upload بلا opt-in. | Data safety، consent flows، وقائمة processors. |

## بوابات المتجر والتسليم

| البوابة | المالك | دليل القبول |
|---|---|---|
| Signing identity وversioning | release engineer | **متحقق في CI للحزمة المرجعية:** هوية production جديدة مع recovery مشفرة خارج GitHub؛ signed APK/AAB وSHA-256 وmapping و`apksigner` موجودة في run `32783660291` عند `ca881a1b`. قبل الرفع، يتحقق المالك من versionCode متزايد مقابل Play ويحتفظ بالـmapping/recovery خارج المستودع. |
| CI release evidence | release engineer | run `32783660291` ناجحة على `ca881a1b`؛ signed artifacts و`SHA256SUMS` وcertificate evidence وmapping وreports محفوظة، مع native/R8/lint/instrumentation مكتملة. |
| Data safety وسياسة الخصوصية | product/legal owner | إجابات مطابقة للسلوك الفعلي لكل إذن وFirebase/provider، رابط سياسة ساري، ومسار حذف بيانات مختبر. |
| Play pre-launch | release engineer | تقرير على أجهزة Play أو سبب/إصلاح كل failure وعدم وجود crash/blocker. |
| Provider credentials | connector owner | حسابات اختبار، redirect URIs، revocation، errors/cancel، وعدم ظهور أسرار في التقرير أو UI. |
| قبول المستخدم | product owner | نتائج جلسات persona الأولى: نجاح إكمال رحلة المشروع، وضوح الموافقات، عدم فقد البيانات، وإشارة تبنٍّ قابلة للقياس. |

## قاعدة تحديث الحالة

يرفق منفذ الاختبار لكل صف: `commit SHA` و`build/versionCode` و`الجهاز/API/ABI` و`الوقت` و`الخطوات` و`النتيجة` و`رابط artifact أو screenshot منقح`. إذا غاب أي عنصر، تبقى الحالة `RUNTIME_VERIFICATION_PENDING` أو `EXTERNAL_VERIFICATION_REQUIRED`.
