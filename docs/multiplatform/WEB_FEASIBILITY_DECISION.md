# قرار Feasibility للـWeb

## القرار

يبقى AIRI Web في حالة **`PLANNED`**. لا توجد وحدة `app-web` أو target Kotlin/Wasm أو artifact متصفح في هذه الشجرة. لا يبدأ تنفيذ Web قبل اكتمال أسس Linux وWindows، وقد اكتملت تلك الأسس بالدليل المتاح؛ لكن ذلك لا يحوّل Web إلى امتداد مباشر لسطح المكتب.

يتخذ المسار المقترح **Kotlin/Wasm مع Compose Multiplatform** خياراً أولياً لـواجهة AIRI التفاعلية عند بدء gate منفصل، لأنه يناسب مشاركة منطق وسياسات محددة مع واجهة متعددة المنصات. ومع ذلك، تصنّف وثائق Kotlin الحالية Kotlin/Wasm وCompose Multiplatform Web كـ`Beta`، بينما Kotlin/JS مستقر ويظل خيار compatibility/interop محتملًا؛ لا يبرر هذا المستوى البدء بمنتج Web أو وعد المستخدمين بدعم متصفح نهائي. [1] [2]

> لا تنقل AIRI JNI/NDK أو ملفات النماذج أو provider secrets أو Android Room أو منطق Desktop filesystem إلى المتصفح. لا يصبح أي منها جزءاً من Web بسبب قابلية `commonMain` للبناء وحدها.

## النطاق الممكن والنطاق المحجوب

| المجال | القرار في Web | الحالة | سبب القرار |
| --- | --- | --- | --- |
| سياسات النواة الخالصة | مرشح للفحص عبر `commonMain` بعد إضافة target Wasm | `ARCHITECTED` | سياسات النص والذاكرة والمرفقات وعقود التخطيط لا ينبغي أن تعتمد API منصة. |
| واجهة محادثة responsive | مسار Compose/Wasm منفصل بعد spike | `PLANNED` | Compose Web Beta ويتطلب تحقق browser فعلي وaccessibility/performance. [1] |
| استجابة AIRI المحلية الحتمية | أول مسار قبول محتمل | `PLANNED` | يثبت UI والنواة من دون provider أو secret أو tool OS. |
| cloud providers | عبر backend موثوق أو OAuth PKCE عام فقط | `ARCHITECTED` | browser عميل غير موثوق ولا يجوز أن يحمل provider secret. |
| الذاكرة طويلة المدى | IndexedDB محدود أو sync service | `ARCHITECTED` | يحتاج retention/encryption/logout/quotas ومراجعة privacy. |
| مرفقات المستخدم | File API واختيار صريح فقط | `ARCHITECTED` | لا يمكن افتراض URI Android أو مسارات نظام Desktop. |
| local model runtime | لا يدخل Web gate الأول | `BLOCKED` | JNI غير متاح؛ Wasm/WebGPU يحتاج دراسة runtime وأداء وترخيص منفصلة. |
| tools/skills ذات صلاحيات OS | لا تدخل Web gate الأول | `BLOCKED` | browser لا يمنح filesystem/process/capabilities Desktop؛ يلزم manifest وbackend/permission model منفصل. |
| scheduling/background | لا يدخل Web gate الأول | `BLOCKED` | service worker وbrowser lifecycle لا يماثلان WorkManager أو scheduler سطح المكتب. |

## حدود الأمن والبيانات

يجب أن يعامل تطبيق Web كعميل غير موثوق. يمنع تضمين API keys أو client secrets أو private provider credentials في Wasm/JavaScript أو static assets. تستخدم الطلبات التي تتطلب credential أو كلفة أو صلاحية واسعة backend موثوقاً يطبق authorization وrate limits وaudit، أو تعتمد OAuth PKCE حيث يكون تدفق المستخدم مناسباً. يراجع gate Web لاحقاً CORS وCSP وHTTPS وredirect URIs وlogout clearing وسياسة IndexedDB وservice worker قبل أي إعلان أمني. يتفق ذلك مع نموذج أمن AIRI الحالي؛ لا يعد أي من هذه الضوابط منفذاً الآن.

| حد الثقة | قاعدة التنفيذ المستقبلية | دليل الإغلاق |
| --- | --- | --- |
| provider credentials | لا secrets في bundle؛ backend mediation أو public OAuth PKCE فقط. | فحص artifact، اختبار auth redirect، ومراجعة server authorization. |
| التخزين | أقل بيانات في IndexedDB مع clear-on-logout وسقف retention ظاهر للمستخدم. | اختبار browser حقيقي للكتابة والاستعادة والحذف وquota failure. |
| المرفقات | file handle يمنحه المستخدم، حدود type/size/dedup المشتركة قبل upload. | اختبار accept/reject/abort وعدم حفظ مسار أو blob غير مقصود. |
| tools | لا process أو filesystem واسع في المتصفح؛ capability وuser grant وbackend policy. | tests allowed/denied وaudit بلا payload حساس. |
| الشبكة | HTTPS وCORS محدود وCSP مراجعة. | integration test من origin معتمد ومحاولة origin مرفوض. |

## خطة Gate Web المستقلة

| الترتيب | المخرج المطلوب | الحالة قبل الانتقال |
| --- | --- | --- |
| W0 | مراجعة إصدارات Kotlin/Compose المتوافقة مع Wasm، واختبار `commonMain` الحالي ضد target جديد في فرع مستقل. | لا artifact ولا UI؛ يبقى Web `PLANNED`. |
| W1 | `app-web` مستقل يعرض محادثة محلية حتمية ويستهلك عقداً مشتركاً مثبتاً فقط. | `BUILDS` بعد `wasmJsBrowserDistribution` وartifact ثابت. [3] |
| W2 | قبول browser حقيقي: launch، render، keyboard/mouse/touch، response، وتخزين محدود مع logout clearing. | `RUNTIME_VERIFIED` لنطاق محلي محدود فقط. |
| W3 | backend/auth/attachments حسب threat model وخصوصية البيانات. | ترفع كل قدرة على حدة بعد security review وintegration evidence. |
| W4 | streaming/cancellation/skills/scheduling/local runtime فقط بعد دراسة منفصلة لكل قدرة. | لا تُستنتج من نجاح W1–W3. |

ينتج Compose Web artifacts عبر `wasmJsBrowserDistribution` في مسار distribution الخاص بالوحدة، لكن artifact وحده لا يكفي لتصنيف المنتج runtime؛ يلزم سيناريو مستخدم متصفح موثق. توصي وثائق Kotlin بـJDK 17 أو أحدث لمشروعات Compose Multiplatform، وهو متوفر في baseline الحالي. [3]

## معايير عدم البدء

يؤجل gate Web إذا تطلب اختصاراً أحد الأمور التالية: نقل API key إلى العميل، ادعاء تشغيل نموذج Android المحلي في browser، تخزين ذكريات/مرفقات بلا سياسة retention وlogout، تنفيذ tool محلي بلا capability واضحة، أو دمج UI Desktop في responsive Web من دون اختبار browser. عندئذ تبقى الحالة `BLOCKED` أو `PLANNED` وفق المصفوفة.

## المراجع

[1]: https://kotlinlang.org/docs/multiplatform/supported-platforms.html "Stability of supported platforms | Kotlin Multiplatform"
[2]: https://blog.jetbrains.com/kotlin/2025/05/present-and-future-kotlin-for-web/ "Present and Future of Kotlin for Web | JetBrains Blog"
[3]: https://kotlinlang.org/docs/wasm-get-started.html "Get started with Kotlin/Wasm and Compose Multiplatform"
