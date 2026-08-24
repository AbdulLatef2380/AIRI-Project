# سجل مراجعة الإطلاق الشاملة

> **الحالة:** `IN_PROGRESS`. هذا السجل لا يمنح حالة إطلاق أو توقيع أو متجر. يربط كل نتيجة بدليلها وحدها، ويفصل فشل مورد بيئة البناء عن فشل الكود.

## 1. نتيجة بوابات البناء الحالية

| البوابة | النتيجة | الدليل | الحكم الصريح |
|---|---|---|---|
| Kotlin debug compilation | `BUILD_VERIFIED` | دفعات Project Home والتفضيلات ودورة الجلسة اجتازت `:app:compileDebugKotlin`. | لا يثبت APK release أو تشغيل جهاز. |
| JVM targeted tests | `TESTED` | `WorkspaceContextTest` و`UserPreferenceProfileTest` والاختبارات المستهدفة السابقة للمهام/RAG اجتازت. | لا يثبت Room أو Android provider أو UI على جهاز. |
| Android instrumentation compilation | `BUILD_VERIFIED` | `:app:compileDebugAndroidTestKotlin` نجحت بعد محاولة release، وCI جهز APK الاختبار. | لا يثبت تشغيل الاختبارات أو سلوك device. |
| Android CI: debug/lint/release-source | `CI_VERIFIED` | GitHub Actions run `32677555213` للالتزام `69e02f54` نجح في source contracts والتوطين، shared-core tests، `assembleDebug`، unit tests و`lintDebug`، ثم `compileReleaseKotlin`. | لا ينشئ artifact release موقعاً على `cp-foundation`، ولا يثبت R8/package release. |
| Android CI: instrumentation runtime | `CI_VERIFIED` | artifact `AIRI-Android-Test-Reports` من run `32675745165` حدد الاختبارين اللذين فشلا لأن `ProjectKnowledgeTextPolicy` أسقطت النص الصريح القصير. الالتزام `69e02f54` احتفظ بمقطع lexical واحد للنص القصير غير الفارغ، وأضاف اختبار JVM وحارس مصدر. أعاد GitHub Actions run `32677555213` تشغيل `connectedDebugAndroidTest` بنجاح، ثم نجح `Verify native library output` ورفع تقارير الاختبارات. | يثبت تشغيل محاكي CI API 29 للمسارات المدرجة، لا كل أجهزة Android أو تجارب الإذن/واجهة المستخدم أو مزود Calendar الحقيقي. النص الفارغ يبقى بلا مقطع، ولا يوجد إدخال معرفة تلقائي. |
| Android device availability | `RUNTIME_VERIFICATION_PENDING` | `adb devices -l` لم يعرض جهازاً. | لا يجوز وصف صلاحيات أو UI أو WorkManager أو Calendar بأنها اجتازت وقت التشغيل. |
| Release assembly | `PARTIAL` | أول محاولة ثبتت CMake 3.22.1 ثم توقف daemon؛ المحاولة المنفصلة وصلت `minifyReleaseWithR8` ثم أوقفت لحماية ذاكرة sandbox. وCI الحالي نجح في `compileReleaseKotlin` فقط؛ توقيع/package على `cp-foundation` مقصود أن يتخطى لأن signing محصور في `main` مع الأسرار. | لا APK/AAB release مكتمل، لا native-APK check مكتمل، ولا توقيع تحقق. |
| Android Lint | `CI_VERIFIED` | محاولة sandbox المحلية قُطعت لحماية الذاكرة، لكن GitHub Actions run `32672299812` أكمل `:app:lintDebug` بنجاح للالتزام `8c90a53b`. | هذه نتيجة CI لنسخة debug؛ لا تعوض فحص lint/release artifact أو تشغيل جهاز. |
| Android permission surface | `BUILD_VERIFIED` / `RUNTIME_VERIFICATION_PENDING` | التدقيق الثابت أثبت أن `SCHEDULE_EXACT_ALARM` لم يكن له مستدعٍ أو receiver مملوك؛ أزيل من manifest ومن شاشة الصلاحيات. أذونات Calendar وContacts تبقى لمساراتها الصريحة، وCamera يطلب عند زر المحادثة. صار وصف خدمة Accessibility وواجهة الإعدادات يوضحان أنها تُفعّل يدوياً وأن إجراءات الجهاز محكومة بالسياسة؛ حارس النواة 74/74 وstrict localization و`:app:compileDebugKotlin` اجتازت. | يجب تجربة grant/deny وSettings return وAccessibility disclosure على جهاز وعلى إصدارات Android المستهدفة؛ لا يثبت التدقيق الثابت قبول Play أو تشغيل خدمة Accessibility. |
| Initial permission and deep-link entry | `CI_VERIFIED` / `RUNTIME_VERIFICATION_PENDING` | أزيل طلب `POST_NOTIFICATIONS` التلقائي من `MainActivity`؛ بقي الطلب داخل إجراء Onboarding الصريح. كما بات `ReferralManager` يقبل فقط `airi://referral?code=` الصحيح ولا يلتقط `code` من URI عام أو callback OAuth، مع عدم تسجيل قيمة الإحالة الخام. GitHub Actions run `32679669784` للالتزام `2a40a32b` اجتاز contracts والتوطين وdebug/unit/lint/release-source و`connectedDebugAndroidTest` وفحص native output. | يتطلب التحقق منح/رفض الإشعارات من Onboarding على جهاز، وتجربة cold/warm deep link ورابط OAuth حقيقي؛ لا يثبت ذلك عقد مزود OAuth أو برنامج إحالة/مدفوعات خارجي. |
| Local device-data erase | `CI_VERIFIED` / `RUNTIME_VERIFICATION_PENDING` | `PrivacyDataSettingsScreen` يتيح الآن حواراً صريحاً لمحو بيانات AIRI المحلية فقط دون حذف الحساب البعيد؛ `DataDeletionCoordinator.eraseLocalData()` يوقف WorkManager ويمسح Room/المشروعات/artifacts/المعرفة/الأسرار/الإعدادات/cache ثم يوقع الخروج، ولا يستدعي الحذف البعيد أو Firebase. GitHub Actions run `32690168612` للالتزام `87797631` اجتاز contracts والتوطين وdebug/unit/lint/release-source و`connectedDebugAndroidTest` وفحص native output. | يلزم تحقق يدوي أن الإلغاء لا يغير بيانات، وأن التأكيد يزيل كل سطح محلي متوقع ويترك الحساب البعيد صالحاً على جهاز حقيقي؛ لا يثبت CI حذف بيانات provider أو قبول المتجر. |
| Local project-file approval recovery | `CI_VERIFIED` / `RUNTIME_VERIFICATION_PENDING` | `ProjectFileApprovalRecoveryTest` يبني مشروعاً وملفاً ومهمة تشغيلية مملوكة، ثم ينشئ اقتراح تعديل خاصاً ويطلب approval، ويعيد إنشاء task/workspace/file/artifact/proposal runtimes من التخزين الخاص بالتطبيق. فرع الموافقة يطبق التعديل مرة واحدة ويربط artifact بمشروع/مهمة/run/step ويرفض استئنافاً ثانياً؛ وفرع الرفض بعد إعادة الإنشاء لا يغير الملف ولا ينشئ artifact ولا يستأنف continuation. GitHub Actions run `32695492715` للالتزام `73ac96ad` وrun `32697399760` للالتزام `bfef9bb3` اجتازتا contracts والتوطين وdebug/unit/lint/release-source و`connectedDebugAndroidTest` وفحص native output. | يثبت محاكي CI API 29 العقد المحلي والمسار التشغيلي المحدد، لا واجهة Trust Center الفعلية أو الرفض/الإلغاء على جهاز أو process kill حقيقياً أو توقيعاً أو مزوداً أو متجراً. |
| Explicit project-file task selection | `CI_VERIFIED` / `RUNTIME_VERIFICATION_PENDING` | كانت Library تختار أول مهمة `RUNNING` مؤهلة في المشروع عند اقتراح تعديل ملف، ما يجعل ملكية الاقتراح مع تعدد المهام معتمدة على ترتيب التخزين. `ProjectFileEditTaskSelector` يقبل المهمة الوحيدة فقط؛ وعند وجود أكثر من مهمة مؤهلة تطلب Library اختيار المستخدم وتمنع إنشاء الاقتراح حتى يتم ذلك. اختبارات JVM تثبت اختيار المهمة الوحيدة واستبعاد المشروع/الحالة/الخطوة غير المؤهلة ورفض الاختيار المبهم. GitHub Actions run `32702865271` للالتزام `6d78e0d0` اجتاز contracts والتوطين وdebug/unit/lint/release-source و`connectedDebugAndroidTest` وفحص native output. | يلزم تحقق بصري على جهاز لاختيار المهام المتعددة وتبديل المشروع وTalkBack ومقياس الخط؛ لا يثبت CI محاكي قبول المتجر أو توقيع artifact أو مزود خارجي. |
| Account-deletion localized confirmation | `CI_VERIFIED` / `RUNTIME_VERIFICATION_PENDING` | مسار biometric لحذف الحساب لم يعد يعرض عبارات إنجليزية صلبة؛ يستخدم موارد الحذف الأربعة المحلية أيضاً عند عدم وجود screen lock. GitHub Actions run `32684419569` للالتزام `b3dff1c8` اجتاز contracts والتوطين وdebug/unit/lint/release-source و`connectedDebugAndroidTest` وفحص native output. | يتطلب تجربة biometric unavailable/cancel/confirm ونتائج remote unavailable/partial success على جهاز؛ لا يثبت ذلك حذف Firebase أو البيانات البعيدة. |
| Telemetry and crash-reporting consent | `CI_VERIFIED` / `EXTERNAL_VERIFICATION_REQUIRED` | كان manifest يعطّل جمع Firebase افتراضياً وحارس الأحداث يمنع `logEvent`، لكن SDK لا كان يُزامن صراحة مع تبديل consent وCrashlytics كان يسمح بواجهات metadata/error حسب الاستدعاء. الآن ينعكس consent في `setAnalyticsCollectionEnabled` عند التهيئة والتغيير والسحب، ولا تسجل Analytics محلياً قيم الأحداث؛ كما يحجب `FirebaseCrashReporter` المفاتيح والسجلات والاستثناءات حتى التفعيل ويُعطّل فور السحب. GitHub Actions run `32681472224` للالتزام `0f936dd1` اجتاز contracts والتوطين وdebug/unit/lint/release-source و`connectedDebugAndroidTest` وفحص native output. | يلزم دليل Firebase DebugView/شبكة على جهاز بتسلسل opt-in، opt-out، restart، revoke وتحقق فعلي من عدم إرسال event/crash قبل الموافقة أو بعدها؛ لا يثبت البناء قبول سياسة البيانات أو إعداد مشروع Firebase. |

## 2. تكوين الإصدار والنتائج الثابتة

| المجال | ما وجد | التقييم | الإجراء |
|---|---|---|---|
| توقيع الإصدار | `build.gradle.kts` يقرأ `KEYSTORE_BASE64` وبياناته من البيئة فقط، وينشئ `release.keystore` محلياً عند توافرها؛ `.gitignore` يستثني `*.jks` و`*.keystore`. على `main` مع الأسرار الكاملة، ينفذ CI packaging ثم `apksigner verify` و`apksigner --print-certs` ويحفظ SHA-256 وmapping ضمن artifact evidence. GitHub Actions run `32707768137` للالتزام `fa0e62da` اجتاز الحارس والبناء والاختبارات، وظهرت خطوة التحقق الجديدة لكنها skipped كما هو مقصود على `cp-foundation`. | `IMPLEMENTED` لحماية الإدخال وبوابة evidence، لكن `EXTERNAL_VERIFICATION_REQUIRED` لتوقيع main حقيقي ورفع Play. | شغّل main في بيئة إصدار معتمدة مع الأسرار؛ احفظ APK/AAB و`mapping.txt` و`SHA256SUMS` ومخرجات apksigner. لا توضع مفاتيح في المستودع أو ملفات التطبيق. |
| تقليص وحماية APK | `release` يفعّل R8 وresource shrinking، ويحتوي على فحص JNI مخصص لكل APK. بوابة signing على main تفشل إن غاب `mapping.txt` وتحسب SHA-256 للـAPK/AAB/mapping قبل رفع evidence. | `PARTIAL` حتى يكتمل `assembleRelease`/`bundleRelease` الموقّع وتُفحص مخرجاته الفعلية. | شغّل release assembly في CI المحمي؛ اجمع mapping وSHA-256 ونتيجة apksigner وفحص APK/AAB. |
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
3. **مكتمل:** إعادة تشغيل `connectedDebugAndroidTest` في CI على إصلاح النص القصير؛ اجتازت run `32677555213` الاختبارين وفحص native artifact.
4. تنفيذ `RELEASE_DEVICE_AND_STORE_MATRIX.md` على أجهزة فعلية/CI لتدقيق الرفض والعودة من Settings والواجهة/قارئ الشاشة وتسجيل الأدلة.
5. تشغيل release package/R8 والتوقيع ضمن CI على `main` مع أسرار معتمدة؛ عند النجاح، احفظ `mapping.txt` و`SHA256SUMS` ونتائج apksigner التي ترفعها البوابة تلقائياً. لا تكرر الضغط في sandbox الحالي.
6. تجهيز release documentation وstore/legal gates بعد وجود artifact موقع ونتائج runtime.

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
