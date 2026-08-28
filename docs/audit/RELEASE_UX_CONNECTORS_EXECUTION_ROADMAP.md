# خارطة إغلاق تجربة AIRI والموصلات قبل النشر

**الحالة:** وثيقة تنفيذية حية — لا تمثل إعلان جاهزية أو نجاح أجهزة/موفرات خارجية.

## الغرض وحدود القرار

AIRI **مشروع Android قائم**؛ لذلك لا تقترح هذه الخارطة إعادة بنائه أو تبديل بنيته الأساسية. الغرض هو إغلاق ما تبقى من عقود الوكيل والخطة، وصدق الموصلات، وتناسق تجربة الاستخدام، مع الحفاظ على الملكيات الموجودة: `StateFlow` داخل التطبيق للحالة، و`ConnectorRegistry` كمرجع وحيد للموصلات، و`HybridOrchestrator`/`RuntimeRouter` للنماذج، و`SecureStorage`/`ConnectorAuthManager` للأسرار. لا يوجد في النطاق إنشاء خادم خلفي أو قناة أحداث شبكية جديدة لمجرد تقليد منتج آخر.

الصور المرفقة هي **مراجع تصميم ومتطلبات مستخدم** وليست دليلاً أن AIRI يعرض السطح ذاته حالياً. لهذا تفصل هذه الوثيقة بين الملاحظة البصرية المطلوبة وبين العيب المثبت في كود AIRI. يجب أن ينتهي كل بند إلى أحد التصنيفات: `DONE` أو `PARTIALLY_DONE` أو `MITIGATED` أو `OPEN` أو `BLOCKED`، مع اختبار مناسب ودليل تشغيل حيث يلزم.

| مجال الإغلاق | الدليل الحالي | القرار الهندسي | الحالة |
|---|---|---|---|
| تخطيط الوكيل وtrace | مسار trace وواجهة الخطة أضيفا في commits سابقة؛ ودفعة validator موجودة في `d84f4390` ثم إصلاح JVM في `215c4a4c` | منع admission للخطة غير القابلة للتنفيذ ثم إعادة تحقق CI قبل احتسابها دليلاً | `PARTIALLY_DONE` |
| حقيقة الموصلات | `GoogleConnector.connect()` يتعامل مع بريد مخزن كاتصال، بينما `execute()` يطلب token؛ `ConnectorsScreen` يعرض `connected` فقط | فصل الحالة إلى غير مهيأ/يحتاج تفويض/مهيأ/جاهز/خطأ، وعرض الصحة والوقت والقدرة الفعلية | `OPEN` |
| العربية وRTL | شاشات وإشعارات وموصلات تحتوي نصوصاً إنجليزية صريحة؛ بعض المعرفات موجهة يدوياً LTR | إزالة النصوص الصريحة، وضبط bidi للبيانات فقط، واختبار Force RTL وTalkBack | `OPEN` |
| المظهر والسطوح | طبقة الثيم موجودة، لكن الشاشات تستعمل ألواناً صريحة وسلوكاً بصرياً غير موحّد | توحيد الألوان والحالات عبر رموز theme، ثم اختبار light/dark على جهاز | `OPEN` |
| المحادثة والمرفقات | شريط الإدخال وخيارات الرسائل والخطة ضمن أسطح متعددة | تثبيت حالات الإرسال/الإلغاء/الفشل والمرفقات ثم اختبار UI؛ لا ننسخ هوية منتجات أخرى | `OPEN` |
| الحساب والاستخدام | يجب التعامل مع بيانات الحساب والرصيد كمعلومات حساسة ودقيقة، لا كنصوص تسويقية أو عدادات غير موثوقة | إخفاء ما لا يلزم، وفصل الرصيد المعروض عن عدادات داخلية غير idempotent | `OPEN` |
| الصوت والنماذج والمهام | المزايا قائمة جزئياً عبر الطبقات الحالية | سد حالات offline/fallback، الإلغاء، الأذونات، والجدولة المدعومة فعلاً | `OPEN` |

## الاستنتاجات المعتمدة من المصدر والمراجع

بنية Compose الصحيحة لا تعني مجرد تحسين بصري؛ إنها تقوم على حالة وحيدة قابلة للرصد، مع إبقاء المنطق في state holder أو ViewModel وتمرير الحالة إلى composables، وهو ما يمنع تضارب حالة الاتصال أو الاختيار بين واجهات متعددة. توصي Android بجمع `Flow` بصورة lifecycle-aware واستخدام state hoisting كي لا تصبح الواجهة مصدراً منافساً للحقيقة [1]. لذلك يحظر على شاشة الموصلات أن تستنتج الجاهزية من toggle محلي أو من وجود نص محفوظ.

تشير وثائق Android إلى أن دعم العربية يتجاوز ترجمة الموارد: التخطيط يجب أن ينعكس، والرموز الاتجاهية يجب أن تنعكس عند ملاءمتها، بينما البريد والروابط والمعرفات تحتاج معالجة bidi صحيحة حتى لا تختلط العلامات والأرقام أو ينقلب ترتيبها [2]. كما تدعم Material نفس المبدأ: تعكس أشرطة التطبيق والقوائم وحقول النص، لكن لا تعكس عناصر الزمن أو عناصر الوسائط التي يبقى اتجاهها LTR [3].

> «Incorrectly rendering text in RTL languages can create cognitive overload and negatively impact user sentiment and trust.» — إرشادات Material حول RTL [3]

الموصل الخارجي ليس «متصلاً» لأن قيمة credential أو بريد محفوظ موجود. توصي Firebase بمسار Google Sign-In الحديث عبر Credential Manager، مع تمكين موفر Google وSHA-1 الصحيح ثم تحديث ملف إعداد Firebase. وتؤكد وثائق Android أن **المصادقة** و**تفويض بيانات Google** تدفقان منفصلان: ID token يثبت الهوية، بينما الوصول إلى Gmail أو Drive أو Calendar يحتاج `AuthorizationClient.authorize()` للحصول على access token قصير العمر للنطاقات المطلوبة عند تنفيذ المستخدم للفعل ذاته [4] [5]. وبالمثل، تؤكد GitHub أن GitHub App مناسب عندما تحتاج صلاحيات دقيقة وtokens قصيرة العمر، وأن OAuth code flow يحتاج `state` غير قابل للتخمين وPKCE/S256 والتحقق من الهوية بعد كل تفويض [6] [7].

| مسار | أصل المشكلة المثبت | التصحيح الأدنى | دليل الإنجاز |
|---|---|---|---|
| Google connector | اتصال UI من بريد مخزن، بينما التنفيذ يحتاج ID token صالحاً | حالة `authorizationRequired` مستقلة، و`runtimeReady` لا تكون صحيحة قبل token صالح وصلاحية مطلوبة | اختبارات حالة JVM؛ Google Sign-In فعلي لاحقاً |
| GitHub connector | تحقق PAT عند الاتصال أفضل من Google، لكن النصوص والحالات حرة وغير محلية | إسقاط حالة موحدة وآمنة وملخص scopes/health دون token أو خطأ خام | اختبارات projection؛ health check بحساب اختبار معتمد |
| Connector health monitor | قراءة state فقط مع انتظار زمني ثابت وغير مضمون، وقد يبث خطأ connector خاماً إلى ActivityFeed | استبدال الانتظار غير الحتمي بتجميع bounded، وحجب الرسالة مركزياً، وعدم تسمية القراءة health check | اختبار coroutine؛ تحقق تشغيل حقيقي |
| Connectors screen | يعتمد على `connected` ولا يعرض `healthy` أو `statusLine` أو تاريخ مفيد | بطاقة تعرض الحالة الدقيقة وسبباً آمناً وزر الإجراء المناسب | Compose semantics/screenshots؛ جهاز فعلي |
| إعدادات عامة | نصوص صريحة إنجليزية لـAI execution/secure storage/about/internal surfaces | موارد `ar/en/es/zh` واختبارات parity، مع وصف يطابق القدرة الفعلية | resource checks وForce RTL |

## أولويات التنفيذ

### P0 — صحة العقد والخصوصية قبل التجميل

تبدأ المرحلة بإغلاق validator الخطة قبل execution admission، ثم استكمال trace مقيّد ومُحجَب. يجب أن ترفض البوابة JSON غير صالح، خطة فارغة، معرفات مكررة، دورات، اعتماداً غير ممكن، وحداً أعلى للخطوات، وأن يكون ترتيب العقد الحاضرة قابلاً للتنبؤ. ظهر فشل CI سابق في اختبارات `PlanGeneratorAdmissionTest` لأن مسار fallback استدعى `android.util.Log` داخل JVM؛ حافظ الإصلاح في `215c4a4c` على الاختبارات وأزال هذا الاعتماد من مسار parser. لا يصبح هذا البند `DONE` إلا بعد نتيجة CI كاملة للرأس نفسه.

بالتوازي، يجب إزالة أي تسجيل جزئي لقيم OAuth مثل prefix `state`؛ امتلاك جزء من السر لا يجعله آمناً للتسجيل. ويجب حجب errors القادمة من الشبكة أو الموفر قبل Agent Activity أو trace أو analytics، لأن رسائل HTTP قد تشمل رابط callback أو معرفاً أو بيانات مستخدم.

### P1 — موصلات صادقة ومفهومة

تضاف طبقة presentation نقية فوق `ConnectorState`، لا registry موازٍ. تميز هذه الطبقة بين: **غير مهيأ**، **يحتاج تفويض**، **مهيأ محلياً**، **جاهز حسب آخر تحقق**، و**خطأ قابل للتصرف**. `lastUpdatedMs` لا يصير دليلاً على الصحة إلا مع مصدر تحقق واضح، ولا تعرض الواجهة health كأنه مباشر إذا لم تنفذ فحصاً للشبكة. يفترض العرض نوع المصادقة، الحد الأدنى من permissions، وآخر تحقق بلا عرض token أو بريد كامل في مواضع غير ضرورية.

ينبغي أن تكون إجراءات الكتابة (إرسال بريد أو تعديل مستودع أو إنشاء حدث) طلبات تأكيد محددة النطاق، مع وصف أثر واضح، وليس مجرد switch. يطابق هذا contract الحالي الذي يسمح بـ`ApprovalRequired` ويمنع التحويل إلى سلوك صامت. لا تدخل موصلات تسويقية غير منفذة إلى الإصدار؛ يضاف أي موصل فقط بعد عقد تنفيذ، حالات فشل، resources، اختبار، ودليل حساب/موفر خارجي عندما يلزم.

### P2 — العربية أولاً وRTL كامل

تراجع الشاشات حسب الاستخدام، لا عبر استبدال آلي شامل. الأولوية: الإعدادات، الموصلات، الحساب والاستخدام، المحادثة والخطة، المهارات والمعرفة، ثم الإشعارات. تنقل كل عبارة ظاهرة للمستخدم إلى الموارد الأربع (`values`, `values-ar`, `values-es`, `values-zh`) بنفس المفاتيح. توضع المعرفات والبريد والروابط ومخرجات الموفر ضمن direction مناسب فقط؛ لا تفرض LTR على النص العربي أو على المحتوى الذي قد يأتي عربياً.

> في Compose، العناصر القياسية توفر أساساً جيداً للوصول، لكن المكونات المخصصة تحتاج دلالات متعمدة، وتسميات وظيفية للأيقونات، وترتيب انتقال قابل للفهم [8].

### P3 — المظهر والحركة والوصول

تبنى أي تحسينات بصرية فوق `AiriTheme` وMaterial 3 بدلاً من ألوان hex موزعة في الشاشات. الألوان primary/secondary/tertiary تستخدم للمعنى والحالة، لا للزينة العشوائية؛ surface/on-surface ينبغي أن تحفظ التباين في الوضعين. تدعم Material 3 light/dark وdynamic color عندما يكون ذلك مقصوداً ومتوافقاً، مع fallback ثابت [9].

في القوائم والـbottom sheets، تبقى العناصر قصيرة قابلة للمسح، وتستعمل أيقونة/اسم/حالة بنمط ثابت. يناسب modal bottom sheet قائمة إجراءات طويلة، بشرط إمكان الإغلاق بالـscrim والسحب وزر واضح، مع بديل لمس فردي لتغيير الارتفاع إن لم يتوفر السحب [10] [11]. تحذف الرموز التعبيرية كبديل للأيقونات، ويستعمل Material icon أو asset مرخّص، مع `contentDescription` للأيقونات الوظيفية فقط. لكل عنصر قابل للضغط هدف لمس لا يقل عن 48dp، وحالة loading/disabled/error واضحة.

### P4 — المحادثة والخطة والمرفقات

تثبت لوحة الخطة فوق شريط الإدخال كمراقب للحالة لا كمنفذ لها، وتعرض عناصر trace المرتبة حسب sequence، مع filter، تفاصيل آمنة قابلة للطي، auto-scroll يمكن إيقافه، وjump-to-latest. يظل `ExecutionStatusBus` هو المالك؛ لا يستخدم أي مسار واجهة لاستنتاج `executionId` عالمياً. شريط الإدخال يعرض نوع كل مرفق، حجمه وحالته، إمكانية الإزالة والإعادة للفشل، وحالة الإرسال/الإلغاء. تصبح خيارات الدردشة والردود سطوح Material واضحة ذات تسميات عربية صحيحة وإجراءات destructive محمية بتأكيد.

### P5 — الصوت والنماذج والمهام والاستمرارية

تحسن طبقة الصوت حول حالات permission، تهيئة المحرك، offline، interruption، الإلغاء، وتشخيص آمن لا يسجل النص الصوتي أو credentials. تختبر النماذج local/cloud/hybrid على مسارات متطابقة من حيث الإلغاء وfallback ورسائل المستخدم. لا يصبح fallback «ناجحاً» إن لم يعمل النموذج أو الموفر؛ يعلن للمستخدم طريق التنفيذ الحالي وسبب عدم التوفر دون كشف إعدادات حساسة.

المهمة المجدولة هي عقد دائم له وقت تشغيل، سياسة إعادة محاولة، نتيجة، cancellation، ومصدر قدرة واضح؛ لا تستخدم حلقة غير محدودة داخل ViewModel. يعتمد التنفيذ على WorkManager/الطبقة القائمة فقط عندما يناسب ضمانات Android، ويحتاج كل side effect خارجي إلى idempotency وapproval مناسبين.

## بوابات الجودة وملكية الأدلة

| البوابة | ما يمكن إثباته محلياً أو في CI | ما لا يثبت إلا خارجياً | المالك |
|---|---|---|---|
| Kotlin/Gradle/lint/unit | compile وunit/lint وtests في Android CI | سلاسة جهاز حقيقي أو TalkBack | CI ثم مالك جهاز |
| RTL/accessibility | resource parity وsemantics tests | Force RTL وTalkBack وfont scale على هاتف | مالك جهاز |
| Google/Firebase | مسار حالة وunit tests وإعدادات غير سرية | OAuth success وSHA في Firebase وprovider runtime | مالك Firebase/حساب اختبار |
| GitHub/Telegram/موصلات | validation/projection/policy tests | حساب اختبار وصلاحيات حية وrate limit | مالك الحساب |
| Store/Data Safety | اتساق manifest وSDK والوثائق | قرار Data Safety/Play Console/قانوني | مالك المتجر/قانوني |
| CI للرأس `215c4a4c` | لا توجد نتيجة مؤكدة بعد فشل مصادقة GitHub من بيئة العمل | نتائج workflow والوصول إلى logs | تكامل GitHub الخارجي |

## معيار الإغلاق الموحد

لا يغلق بند بسبب وجود كود أو نجاح CI وحده. يلزم: **سبب جذري موثق، إصلاح كامل بأقل تغيير، اختبار إيجابي، اختبار سلبي أو انحدار، CI ناجح إن كان متاحاً، ودليل UI/runtime/device/موفر عندما ينطبق**. أي اعتماد على جهاز ARM64، حساب OAuth، Firebase Console، Play Console، أو مراجعة قانونية يبقى `BLOCKED` أو `EXTERNAL_PENDING` حتى يثبت عملياً.

## المراجع

[1]: [Android Developers — State and Jetpack Compose](https://developer.android.com/develop/ui/compose/state)
[2]: [Android Developers — Support different languages and cultures](https://developer.android.com/training/basics/supporting-devices/languages)
[3]: [Material Design 3 — Bidirectionality & RTL](https://m3.material.io/foundations/layout/bidirectionality-rtl)
[4]: [Firebase — Authenticate with Google on Android](https://firebase.google.com/docs/auth/android/google-signin)
[5]: [Android Developers — Authorize access to Google user data](https://developer.android.com/identity/authorization)
[6]: [GitHub Docs — Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
[7]: [GitHub Changelog — PKCE support for OAuth and GitHub App authentication](https://github.blog/changelog/2025-07-14-pkce-support-for-oauth-and-github-app-authentication/)
[8]: [Android Developers — Accessibility in Jetpack Compose](https://developer.android.com/develop/ui/compose/accessibility)
[9]: [Android Developers — Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
[10]: [Material Design 3 — Bottom sheets](https://m3.material.io/components/bottom-sheets/guidelines)
[11]: [Material Design 3 — Lists](https://m3.material.io/components/lists/overview)
