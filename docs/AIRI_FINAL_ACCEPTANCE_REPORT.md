# تقرير قبول AIRI

**تاريخ التحقق:** 18 أغسطس 2026
**الفرع:** `architecture-refactor`
**نطاق الدليل:** بناء محلي نظيف وتحليل ثابت واختبارات JVM وتهيئة اختبارات Android على الشفرة الحالية.

> الشفرة الحالية هي **Release Candidate** مبنية على Android 16 / API 36. تؤكد الأدلة أدناه صحة مخرجات Debug وRelease وAAB غير الموقعة محلياً، بينما يعالج مسار CI توقيع upload من أسرار GitHub فقط.

## نتيجة بوابات الإصدار

| البوابة | النتيجة المثبتة | الدليل |
|---|---|---|
| سلسلة أدوات Android 16 | PASS | AGP 8.10.1، Gradle 8.11.1، JDK 17، و`compileSdk`/`targetSdk` 36. [1] [2] |
| اختبارات JVM | PASS | **26/26**؛ لا إخفاقات ولا أخطاء. |
| المتحقق الثابت | PASS | **25/25** ضابطاً. |
| Android lint | PASS | `:app:lintDebug` نجح بلا أخطاء. |
| APK Debug | PASS | `:app:assembleDebug` نجح وتحقق من `libairi_native.so`. |
| APK Release وAAB | PASS | `:app:assembleRelease :app:bundleRelease` نجحا عبر R8. |
| اختبارات Android | COMPILED | `:app:assembleDebugAndroidTest` نجح ويحتوي اختبار Room migration ومخزن المفاتيح المشفر. |

## مخرجات التوزيع

| artifact | القيمة |
|---|---|
| APK Release | `app-release-unsigned.apk`، نحو 26 MB |
| AAB Release | `app-release.aab`، نحو 23 MB |
| ABI المضمن | `arm64-v8a` |
| JNI | `libairi_native.so` بحجم 3,759,488 بايت داخل APK وAAB |
| R8 mapping | مُنشأ بحجم 102,253,321 بايت |
| الحد الأدنى للنظام | API 26 |
| هدف النظام | API 36 |

الـAPK المحلي غير موقّع عمداً لغياب مادة توقيع upload من البيئة. يستقبل workflow في GitHub المتغيرات `KEYSTORE_BASE64` و`STORE_PASSWORD` و`KEY_ALIAS` و`KEY_PASSWORD` من الأسرار، ويحذف ملف keystore المؤقت بعد البناء. لا تُخزن أي مادة توقيع في Git أو في artifacts المحلية.

## التغطية الآلية المضافة

تغطي الاختبارات الآن قواعد التوجيه المحلي والسحابي في حالتي الاتصال وعدم الاتصال، fallback، الرؤية، ورفض responses من `401` و`429` و`500`. كما تغطي states الخاصة بـOAuth واستخدامها مرة واحدة وربط PKCE S256، وعزل أسماء تخزين endpoints المخصصة، وحاجز generation الذي يرفض callbacks المتأخرة بعد الإلغاء أو بعد طلب أحدث.

أضيف اختبار Android لترحيل Room من قاعدة v1 ممثلة إلى v6 باستخدام instances الترحيل نفسها التي يمررها التطبيق إلى Room، مع التحقق من احتفاظ البيانات وإنشاء جداول embedding وaudit وworkspace. وأضيف اختبار Android لمخزن مفاتيح API يغطي الحفظ بعد trim والاستعادة والكتابة فوق القيمة والحذف وعزل endpoints المخصصة.

## قرارات هندسية مضمّنة

أزيل مسار SQLCipher المؤجل ومساعد الترحيل غير المفعّل واعتماده، بدلاً من شحن تشفير اختياري غير قابل للتشغيل. تستمر مفاتيح API وOAuth في التخزين المشفر المعتمد على AndroidX Security؛ أما بيانات Room فتستخدم تخزين التطبيق الداخلي ومسار ترحيل Room المختبر آلياً. أزيلت ملفات التخطيط الفارغة وحاجز التكيف المعطل، ووحّدت حراسة الإلغاء في `ExecutionGenerationGate`.

كما أزيلت الرموز النصية والوسوم المرحلية من كود الإنتاج والموارد، وحُدّثت رسائل وملاحظات التشغيل لتصف السلوك الفعلي فقط.

## التحقق الخارجي المخصص للإطلاق

يبقى تنفيذ رحلة الاستخدام على جهاز Android حقيقي، وفق التفويض المحدد، للتحقق من الصوت والصلاحيات والبث والإلغاء وموفري cloud وOAuth وDoze وTalkBack وRTL المرئي. ويظل توقيع upload النهائي عملية سرية في CI لا يمكن إجراؤها دون مفاتيح النشر. لا تؤثر هذه العمليات في الشفرة أو بنية الحزمة المبنية أعلاه.

## المراجع

[1]: https://developer.android.com/about/versions/16/setup-sdk "إعداد Android 16 SDK"
[2]: https://developer.android.com/build/releases/agp-8-10-0-release-notes "توافق Android Gradle Plugin 8.10"
