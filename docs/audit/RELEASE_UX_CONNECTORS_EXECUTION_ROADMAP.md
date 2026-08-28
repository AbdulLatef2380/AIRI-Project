# خارطة إغلاق تجربة AIRI والموصلات قبل النشر

**الحالة:** وثيقة تنفيذية حية — لا تمثل إعلان جاهزية أو نجاح أجهزة/موفرات خارجية.

## الغرض وحدود القرار

AIRI **مشروع Android قائم**؛ لذلك لا تقترح هذه الخارطة إعادة بنائه أو تبديل بنيته الأساسية. الغرض هو إغلاق ما تبقى من عقود الوكيل والخطة، وصدق الموصلات، وتناسق تجربة الاستخدام، مع الحفاظ على الملكيات الموجودة: `StateFlow` داخل التطبيق للحالة، و`ConnectorRegistry` كمرجع وحيد للموصلات، و`HybridOrchestrator`/`RuntimeRouter` للنماذج، و`SecureStorage`/`ConnectorAuthManager` للأسرار. لا يوجد في النطاق إنشاء خادم خلفي أو قناة أحداث شبكية جديدة لمجرد تقليد منتج آخر.

الصور المرفقة هي **مراجع تصميم ومتطلبات مستخدم** وليست دليلاً أن AIRI يعرض السطح ذاته حالياً. لهذا تفصل هذه الوثيقة بين الملاحظة البصرية المطلوبة وبين العيب المثبت في كود AIRI. يجب أن ينتهي كل بند إلى أحد التصنيفات: `DONE` أو `PARTIALLY_DONE` أو `MITIGATED` أو `OPEN` أو `BLOCKED`، مع اختبار مناسب ودليل تشغيل حيث يلزم.

| مجال الإغلاق | الدليل الحالي | القرار الهندسي | الحالة |
|---|---|---|---|
| تخطيط الوكيل وtrace | مسار trace وواجهة الخطة أضيفا في commits سابقة؛ ودفعة validator موجودة في `d84f4390` ثم إصلاح JVM في `215c4a4c` | منع admission للخطة غير القابلة للتنفيذ ثم إعادة تحقق CI قبل احتسابها دليلاً | `PARTIALLY_DONE` |
| حقيقة الموصلات | `c61d7131` يوحّد Google UI والموصل على `ServiceLocator.googleAuthService`، ويعيد تقييم `ConnectorState` بعد قرار المستخدم؛ `7a24d723` أثبت التوافق في CI | الحالات تميّز غير متصل/يحتاج تفويض/جاهز. التفويض الحالي قراءة موحّدة على مستوى الاتصال فقط، وأفعال الكتابة محجوبة حتى approval دائم | `PARTIALLY_DONE` / `CI_VERIFIED` / `EXTERNAL_PENDING` |
| العربية وRTL | أُزيلت النصوص الإنجليزية الصريحة من إعدادات AIRI وتخصيص الصوت، واستعملت الأسهم الاتجاهية المعكوسة المدعومة | بقيت مراجعة السطوح الأخرى، وForce RTL وTalkBack وfont scale على هاتف حقيقي | `PARTIALLY_DONE` / `CI_VERIFIED` |
| المظهر والسطوح | استبدلت شاشة الإعدادات ألوان حالة التخزين وأيقونات التنقل الصريحة برموز `AiriTheme` الدلالية | بقيت مراجعة بقية الشاشات واختبار light/dark على جهاز | `PARTIALLY_DONE` / `CI_VERIFIED` |
| المحادثة والمرفقات | شريط الإدخال وخيارات الرسائل والخطة ضمن أسطح متعددة؛ معظم مسارات الإرفاق والإلغاء موجودة ومربوطة بالـViewModel | `e838b5b3` نقل شرائح Plan/Tools/Skills/Web/Code واختصارات الكاميرا والملف إلى موارد `ar/en/es/zh`، ورفع أهداف شرائح الأدوات إلى 48dp مع semantics وظيفية؛ فحوص redaction وlocalization نجحت وCI الكامل للرأس نجح | `PARTIALLY_DONE` / `CI_VERIFIED` / `RUNTIME_PENDING` |
| الحساب والاستخدام | يجب التعامل مع بيانات الحساب والرصيد كمعلومات حساسة ودقيقة، لا كنصوص تسويقية أو عدادات غير موثوقة | إخفاء ما لا يلزم، وفصل الرصيد المعروض عن عدادات داخلية غير idempotent | `OPEN` |
| الصوت والنماذج والمهام | تخصيص TTS كان يقصر الأصوات على الإنجليزية ويعرض حالة فارغة قبل اكتمال تهيئة المحرك؛ دفعة شريط الإدخال حسّنت اختصارات الصوت/الأدوات دون ادعاء تنفيذ محرك حي | قائمة الصوت تقبل أصوات لغة التطبيق المحلية فقط، تستبعد network-only، وتفصل initializing/unavailable/no-offline-voice؛ بقية عقود النماذج والمهام ما زالت مفتوحة | `PARTIALLY_DONE` / `CI_VERIFIED` / `RUNTIME_PENDING` |

## الاستنتاجات المعتمدة من المصدر والمراجع

بنية Compose الصحيحة لا تعني مجرد تحسين بصري؛ إنها تقوم على حالة وحيدة قابلة للرصد، مع إبقاء المنطق في state holder أو ViewModel وتمرير الحالة إلى composables، وهو ما يمنع تضارب حالة الاتصال أو الاختيار بين واجهات متعددة. توصي Android بجمع `Flow` بصورة lifecycle-aware واستخدام state hoisting كي لا تصبح الواجهة مصدراً منافساً للحقيقة [1]. لذلك يحظر على شاشة الموصلات أن تستنتج الجاهزية من toggle محلي أو من وجود نص محفوظ.

تشير وثائق Android إلى أن دعم العربية يتجاوز ترجمة الموارد: التخطيط يجب أن ينعكس، والرموز الاتجاهية يجب أن تنعكس عند ملاءمتها، بينما البريد والروابط والمعرفات تحتاج معالجة bidi صحيحة حتى لا تختلط العلامات والأرقام أو ينقلب ترتيبها [2]. كما تدعم Material نفس المبدأ: تعكس أشرطة التطبيق والقوائم وحقول النص، لكن لا تعكس عناصر الزمن أو عناصر الوسائط التي يبقى اتجاهها LTR [3].

> «Incorrectly rendering text in RTL languages can create cognitive overload and negatively impact user sentiment and trust.» — إرشادات Material حول RTL [3]

الموصل الخارجي ليس «متصلاً» لأن قيمة credential أو بريد محفوظ موجود. توصي Firebase بمسار Google Sign-In الحديث عبر Credential Manager، مع تمكين موفر Google وSHA-1 الصحيح ثم تحديث ملف إعداد Firebase. وتؤكد وثائق Android أن **المصادقة** و**تفويض بيانات Google** تدفقان منفصلان: ID token يثبت الهوية، بينما الوصول إلى Gmail أو Drive أو Calendar يحتاج `AuthorizationClient.authorize()` للحصول على access token قصير العمر للنطاقات المطلوبة [4] [5]. في `c61d7131` يطلب AIRI حزمة قراءة موحّدة عند اتصال المستخدم المتعمد، ولا يدّعي بعد تفويضاً مستقلاً لكل فعل؛ أما الكتابة فمرفوضة قبل الشبكة إلى أن يكتمل مسار approval دائم. وبالمثل، تؤكد GitHub أن GitHub App مناسب عندما تحتاج صلاحيات دقيقة وtokens قصيرة العمر، وأن OAuth code flow يحتاج `state` غير قابل للتخمين وPKCE/S256 والتحقق من الهوية بعد كل تفويض [6] [7].

| مسار | أصل المشكلة المثبت | التصحيح الأدنى | دليل الإنجاز |
|---|---|---|---|
| Google connector | كانت شاشة التكاملات تنشئ `GoogleAuthService` مستقلة عن مثيل `GoogleConnector` المسجل، فتفويض الذاكرة لا يصل إلى الموصل؛ كما كان زر Connect يعيد sign-in عند حاجة التفويض | `c61d7131`: خدمة singleton مشتركة، إعادة تقييم صريحة للموصل بعد القرار، سياسة زر نقية، رمز ذاكرة يُصفّر عند تبديل الهوية، نطاقات قراءة فقط، وحجب `gmail_send`/`calendar_create` قبل الشبكة | اختبارات JVM للسياسات، Android CI `33133058954` ناجح؛ Google Sign-In/consent/revoke بحساب حقيقي لاحقاً |
| تخصيص TTS وإعدادات الصوت | كانت قائمة TTS تقيد المستخدم بصوت إنجليزي offline وتعرض نتيجة فارغة قبل تهيئة المحرك، كما أن labels وpresets إنجليزية محفوظة في طبقة التفضيلات | `28638905` ينقل النصوص إلى الموارد، يختار لغة التطبيق ويستبعد network-only عبر `VoiceLocalePolicy`، ويعرض initializing/unavailable/no-offline-voice مع أهداف لمس 48dp؛ `0f352a90` أصلح بنية فرع الاختيار و`fd9f90c9` أصلح observation لتكوين Compose | اختبارات JVM لسياسة اللغة؛ Android CI `33138024292` وDeep `33138024268` ناجحان؛ توفر محركات TTS وForce RTL/قارئ الشاشة يحتاجان جهازاً حقيقياً |
| GitHub connector | تحقق PAT عند الاتصال أفضل من Google، لكن النصوص والحالات حرة وغير محلية | إسقاط حالة موحدة وآمنة وملخص scopes/health دون token أو خطأ خام | اختبارات projection؛ health check بحساب اختبار معتمد |
| Connector health monitor | قراءة state فقط مع انتظار زمني ثابت وغير مضمون، وقد يبث خطأ connector خاماً إلى ActivityFeed | استبدال الانتظار غير الحتمي بتجميع bounded، وحجب الرسالة مركزياً، وعدم تسمية القراءة health check | اختبار coroutine؛ تحقق تشغيل حقيقي |
| Connectors screen | يعتمد على `connected` ولا يعرض `healthy` أو `statusLine` أو تاريخ مفيد | بطاقة تعرض الحالة الدقيقة وسبباً آمناً وزر الإجراء المناسب | Compose semantics/screenshots؛ جهاز فعلي |
| إعدادات عامة | نصوص صريحة إنجليزية لـAI execution/secure storage/about/internal surfaces، ووصف appearance ثابت غير مستمد من الحالة | `28638905` و`e838b5b3`: موارد `ar/en/es/zh` متكافئة في السطوح المدققة، وتحذير التخزين وأشرطة الأدوات تستخدم موارد وحالات دلالية؛ الأسهم الاتجاهية تستعمل `AutoMirrored` المدعوم | فحص parity محلي وحارس أمان وAndroid CI `33149044041`؛ Force RTL/light/dark/TalkBack على جهاز حقيقي |

## أولويات التنفيذ

### P0 — صحة العقد والخصوصية قبل التجميل

تبدأ المرحلة بإغلاق validator الخطة قبل execution admission، ثم استكمال trace مقيّد ومُحجَب. يجب أن ترفض البوابة JSON غير صالح، خطة فارغة، معرفات مكررة، دورات، اعتماداً غير ممكن، وحداً أعلى للخطوات، وأن يكون ترتيب العقد الحاضرة قابلاً للتنبؤ. ظهر فشل CI سابق في اختبارات `PlanGeneratorAdmissionTest` لأن مسار fallback استدعى `android.util.Log` داخل JVM؛ حافظ الإصلاح في `215c4a4c` على الاختبارات وأزال هذا الاعتماد من مسار parser. لا يصبح هذا البند `DONE` إلا بعد نتيجة CI كاملة للرأس نفسه.

بالتوازي، يجب إزالة أي تسجيل جزئي لقيم OAuth مثل prefix `state`؛ امتلاك جزء من السر لا يجعله آمناً للتسجيل. ويجب حجب errors القادمة من الشبكة أو الموفر قبل Agent Activity أو trace أو analytics، لأن رسائل HTTP قد تشمل رابط callback أو معرفاً أو بيانات مستخدم.

### P1 — موصلات صادقة ومفهومة

تضاف طبقة presentation نقية فوق `ConnectorState`، لا registry موازٍ. تميز هذه الطبقة بين: **غير مهيأ**، **يحتاج تفويض**، **مهيأ محلياً**، **جاهز حسب آخر تحقق**، و**خطأ قابل للتصرف**. `lastUpdatedMs` لا يصير دليلاً على الصحة إلا مع مصدر تحقق واضح، ولا تعرض الواجهة health كأنه مباشر إذا لم تنفذ فحصاً للشبكة. يفترض العرض نوع المصادقة، الحد الأدنى من permissions، وآخر تحقق بلا عرض token أو بريد كامل في مواضع غير ضرورية.

ينبغي أن تكون إجراءات الكتابة (إرسال بريد أو تعديل مستودع أو إنشاء حدث) طلبات تأكيد محددة النطاق، مع وصف أثر واضح، وليس مجرد switch. يحافظ AIRI حالياً على مسار proposal/approval متين لإنشاء حدث تقويم عبر `CalendarCreateRuntime`، لكنه لا يملك بعد مساراً مكافئاً لإرسال Gmail عبر `GoogleConnector`. لذلك يحجب `c61d7131` كلا فعلي الكتابة في هذا الموصل قبل الطلب الشبكي ولا يدّعي توافرهما. لا تدخل موصلات تسويقية غير منفذة إلى الإصدار؛ يضاف أي فعل كتابة فقط بعد عقد تنفيذ، حالات فشل، resources، اختبار، وموافقة durable ودليل حساب/موفر خارجي عندما يلزم.

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
| Google/Firebase | حالة readiness، سلامة singleton، حجب الكتابة، compile/unit/lint/instrumentation في Android CI `33133058954` | OAuth success/consent/cancel/revoke وSHA في Firebase وprovider runtime على جهاز فعلي | مالك Firebase/حساب اختبار |
| GitHub/Telegram/موصلات | validation/projection/policy tests | حساب اختبار وصلاحيات حية وrate limit | مالك الحساب |
| Store/Data Safety | اتساق manifest وSDK والوثائق | قرار Data Safety/Play Console/قانوني | مالك المتجر/قانوني |
| CI للرأس `7a24d723` | Android `33133058954`، Deep Audit `33133058955`، Architecture `33133058940`، Oracle `33133058947` ناجحة؛ Desktop Windows `33133058958` ناجح أيضاً | Google/Firebase/provider live وواجهة RTL/أجهزة ARM64 لا تستنتج من CI | مالك الحساب/الجهاز |
| CI للرأس `fd9f90c9` | Android `33138024292`، Deep Audit `33138024268`، Architecture `33138024276`، Oracle `33138024272` ناجحة؛ وتشغيلات `cp-foundation` النظيرة نجحت أيضاً | لا تثبت صلاحية محرك TTS أو Force RTL أو TalkBack أو light/dark على جهاز حقيقي | مالك الجهاز |

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
