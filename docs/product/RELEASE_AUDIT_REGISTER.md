# سجل مراجعة الإطلاق الشاملة

> **الحالة:** `IN_PROGRESS`. هذا السجل لا يمنح حالة إطلاق أو توقيع أو متجر. يربط كل نتيجة بدليلها وحدها، ويفصل فشل مورد بيئة البناء عن فشل الكود.

## 1. نتيجة بوابات البناء الحالية

| البوابة | النتيجة | الدليل | الحكم الصريح |
|---|---|---|---|
| Kotlin debug compilation | `BUILD_VERIFIED` | دفعات Project Home والتفضيلات ودورة الجلسة اجتازت `:app:compileDebugKotlin`. | لا يثبت APK release أو تشغيل جهاز. |
| JVM targeted tests | `TESTED` | `WorkspaceContextTest` و`UserPreferenceProfileTest` والاختبارات المستهدفة السابقة للمهام/RAG اجتازت. | لا يثبت Room أو Android provider أو UI على جهاز. |
| Android instrumentation compilation | `BUILD_VERIFIED` | `:app:compileDebugAndroidTestKotlin` نجحت بعد محاولة release. | لا جهاز أو محاكي متصل؛ لم تُنفذ أي instrumentation test. |
| Android device availability | `RUNTIME_VERIFICATION_PENDING` | `adb devices -l` لم يعرض جهازاً. | لا يجوز وصف صلاحيات أو UI أو WorkManager أو Calendar بأنها اجتازت وقت التشغيل. |
| Release assembly | `PARTIAL` | أول محاولة ثبتت CMake 3.22.1 ثم توقف daemon؛ المحاولة المنفصلة وصلت `minifyReleaseWithR8` ثم أوقفت لحماية ذاكرة sandbox. | لا APK/AAB release مكتمل، لا native-APK check مكتمل، ولا توقيع تحقق. |
| Android Lint | `PARTIAL` | `:app:lintDebug` وصل إلى `compileDebugKotlin` ثم بقي فوق عشر دقائق مع metaspace محدود؛ أوقف لحماية الذاكرة. | لا توجد نتيجة lint ناجحة أو قائمة تحذيرات مكتملة بعد. |

## 2. تكوين الإصدار والنتائج الثابتة

| المجال | ما وجد | التقييم | الإجراء |
|---|---|---|---|
| توقيع الإصدار | `build.gradle.kts` يقرأ `KEYSTORE_BASE64` وبياناته من البيئة فقط، وينشئ `release.keystore` محلياً عند توافرها؛ `.gitignore` يستثني `*.jks` و`*.keystore`. | `IMPLEMENTED` للحماية من الإيداع العرضي، لكن `EXTERNAL_VERIFICATION_REQUIRED` لتوقيع حقيقي ورفع Play. | اختبار workflow/keystore في CI أو بيئة إصدار معتمدة؛ لا توضع مفاتيح في المستودع أو ملفات التطبيق. |
| تقليص وحماية APK | `release` يفعّل R8 وresource shrinking، ويحتوي على فحص JNI مخصص لكل APK. | `PARTIAL` حتى يكتمل assembleRelease ويفحص APK الناتج. | إعادة تشغيل release assembly في CI/ذاكرة كافية؛ جمع mapping والتحقق من APK/AAB. |
| الشبكة | `main` صار TLS-only؛ استثناءات `localhost` و`127.0.0.1` و`10.0.2.2` انتقلت إلى `src/debug` فقط. | `IMPLEMENTED` / `BUILD_VERIFIED` بالحارس وتجميع Kotlin. | تحقق release manifest وprovider traffic في artifact/device قبل الإطلاق. |
| R8 entry point | قاعدة ProGuard تحتفظ الآن بـ`com.airi.assistant.app.AIRIApplication` المطابق للـmanifest. | `IMPLEMENTED` / `BUILD_VERIFIED` بالحارس وتجميع Kotlin. | إثبات shrinking في APK release مكتمل ما زال مطلوباً. |
| النسخ الاحتياطي والتجميع الخفي | `allowBackup="false"` وCrashlytics/Analytics معطلة افتراضياً في manifest حتى consent runtime. | `IMPLEMENTED` ثابتاً. | تحقق device/consent/network قبل إطلاق المتجر. |
| الصلاحيات | Camera وmicrophone وContacts وCalendar وexact alarm لكل منها مسارات مصدر/شاشات إذن مرئية. | `PARTIAL`: وجود مسار لا يكفي لقبول Play أو UX. | تدقيق runtime لكل permission، إزالة/تأجيل ما لا يدخل first-release journey، وإعداد Data safety/justifications. |
| Onboarding والأذونات الاختيارية | صفحات البداية لا تطلب microphone أو notification أو calendar إلا من زر يمنحه المستخدم؛ `PermissionsScreen` يظل مسار المنح اللاحق. النصوص المحلية في ar/es/zh تطابق default ولا تدّعي تحكم وكيل عام؛ وصف Calendar يذكر proposal/review الصريح. | `IMPLEMENTED` / `BUILD_VERIFIED` بواسطة `:app:compileDebugKotlin` وحارس المصدر والتوطين الصارم. | تحقق device للرفض الدائم، العودة من Settings، قارئ الشاشة، والنتائج على API/device matrix؛ يظل قبول Play/Data safety خارجياً. |

## 3. الحواف الخارجية التي لا يمكن تزويرها

| البوابة | ما يلزم قبل أي وصف «جاهز للنشر» |
|---|---|
| Android runtime | جهاز/محاكي فعلي يشغل instrumentation، الإذن، UI، process recreation، Calendar، WorkManager، browser handoff، والملفات. |
| Release artifact | `assembleRelease` أو `bundleRelease` مكتمل مع R8، native library verification، artifact hash، mapping، وتوقيع موثوق. |
| Play/Legal | حساب ناشر، package/version policy، Data safety، سياسة خصوصية، وصف صلاحيات، اختبار pre-launch، وشروط النماذج/المفاتيح والأصول. |
| Provider/connector | مفاتيح حقيقية بموافقة المستخدم، OAuth redirect/consent، ومراقبة الأخطاء/الإلغاء/التعافي وفق كل مزود. |
| تبنٍ تجاري | جلسات مستخدمين من persona المحددة، احتفاظ، ثقة في الموافقة، وإشارة دفع؛ لا يمكن استبدالها بعدد commits أو شاشات. |

## 3.1 مصفوفة التنفيذ الخارجي

`RELEASE_DEVICE_AND_STORE_MATRIX.md` هو المرجع التشغيلي لكل تحقق Android/device/store. يحدد لكل صف الإجراء والنتيجة المتوقعة والدليل المنقح المطلوب، ولا يغير أي حالة إلى نجاح من تلقاء نفسه.

## 4. ترتيب الإصلاح التالي

1. **مكتمل:** فصل سياسة شبكة debug عن release وإصلاح R8 entry point؛ اجتازا `compileDebugKotlin` وحارس النواة والتوطين.
2. **مكتمل:** جعل onboarding محلياً لكل اللغات، وإبقاء طلب microphone/notification/calendar خلف زر صريح مع مسار Settings لاحق، وإزالة ادعاء التحكم العام للوكيل.
3. تنفيذ `RELEASE_DEVICE_AND_STORE_MATRIX.md` على أجهزة فعلية/CI لتدقيق الرفض والعودة من Settings والواجهة/قارئ الشاشة وتسجيل الأدلة.
4. إعادة تشغيل release build وLint ضمن CI أو ذاكرة كافية؛ لا تكرر الضغط في sandbox الحالي.
5. تجهيز release documentation وstore/legal gates بعد وجود artifact موقع ونتائج runtime.

## 5. تدقيق مسارات المنتج والخصوصية والاستمرارية

| المسار المدقق | النتيجة | حالة الإطلاق |
|---|---|---|
| مؤشرات التنفيذ الناقص | لا توجد `TODO` أو `FIXME` أو `UnsupportedOperationException` في Kotlin الإنتاجي وفق المسح الثابت. حقول `placeholder` في الموارد هي placeholders إدخال أو أمثلة، لا نجاحات وهمية. | `BUILD_VERIFIED` للمسح فقط؛ لا يثبت اكتمال كل ميزة. |
| التوطين | فحص `airi_localization_health.py --strict` أعاد `likely_untranslated_values=0` مع تكافؤ المفاتيح للغات الأربع، ويشمل الآن صفحات Onboarding وحالات الأذونات. | `BUILD_VERIFIED`؛ يحتاج تحقق مرئي RTL/LTR وحجم خط/قارئ شاشة على جهاز. |
| Onboarding والصلاحيات | لا تُطلق بطاقات microphone/notification/calendar طلباً عند الانتقال بين صفحات البداية؛ الطلب يقع فقط بعد ضغط المستخدم. تعرض كل لغة وصفاً يربط Calendar بالمراجعة الصريحة ويمنع ادعاء أن Accessibility يخول تحكم الوكيل العام. | `BUILD_VERIFIED`/`RUNTIME_VERIFICATION_PENDING`: البناء وحارس المصدر يؤكدان البنية، أما تجربة منح/رفض/Settings فتتطلب جهازاً. |
| Calendar create | المسار المسموح في AgentLoop أصبح runtime typed يمر بمهمة/موافقة/evidence؛ `CalendarTool` القديم ما زال طبقة provider تستخدمها runtime المحلية ولا يجوز استدعاؤها مباشرة من dispatcher العام. | `IMPLEMENTED`/`RUNTIME_VERIFICATION_PENDING` وفق العقد؛ اختبار Calendar permission/provider على جهاز ما زال مطلوباً. |
| browser، terminal، accessibility، alarms، skills، connector mutation | تبقى محظورة في AgentLoop غير المملوك بحسب العقد. وجود شاشات أو أدوات محلية لا يساوي تفويضاً لوكيل. | `PARTIAL` مقصود؛ لا توسع surface قبل typed ownership منفصل. |
| Hotword | `HotwordService` يفتح نشاط AIRI نفسه عند اكتشاف wake word بعد cooldown، لا تطبيقاً خارجياً ولا أداة وكيل. | يحتاج تحقق runtime ومراجعة سلوك foreground/الإشعار/الإذن؛ لا يغير حظر AgentLoop. |
| intents والمشاركة | مسارات `ACTION_SEND`/`ACTION_VIEW` المعروضة تقع في شاشات تُستدعى باختيار المستخدم. يجب ألا تحوّل إلى أدوات AgentLoop أو action تلقائي. | `RUNTIME_VERIFICATION_PENDING` لاختيار التطبيق والإلغاء وعدم وجود handler. |

### عيوب وخطط علاج مرشحة من تدقيق المسارات

| الأولوية | العيب أو الفجوة | قرار المراجعة |
|---|---|---|
| P0 | مورد أمان الشبكة في `main` يسمح بنطاقات cleartext لتطوير emulator. | إصلاحه الآن بفصل مورد debug عن release. |
| P0 | قاعدة ProGuard تشير إلى `AiriApplication` غير المطابقة للاسم الحي. | إصلاحها الآن وحمايتها بحارس مصدر. |
| P1 | لا يوجد تشغيل فعلي للـinstrumentation أو المسارات ذات الصلاحيات لغياب جهاز. | لا يُحل بالكود؛ يعد matrix تشغيلياً ويُسجّل كحاجز خارجي. |
| P1 | release/R8 وLint لم ينتجا نتيجة نهائية ضمن ذاكرة sandbox. | لا تكررها في البيئة المضغوطة؛ تنقل إلى CI أو آلة ذاكرة أكبر. |
| P2 | فحص مشاركة المحادثة/الملفات والأذونات/قارئ الشاشة مرئيّاً. | يؤجل إلى جلسة device validation؛ لا يدّعى نجاحه. |
