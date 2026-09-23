# تدقيق مكتبة Skills في AIRI

## النتيجة التنفيذية

المرفق يطلب نظام Skills احترافيًا type-safe، قابلًا للاكتشاف، قابلًا للتركيب، آمنًا، مترجمًا، ومتكاملًا مع Planner وAgent، مع مكتبة لا تقل عن 40–60 مهارة حقيقية. كما ينص بوضوح على عدم إنشاء Prototype وهمي، وعدم كسر الإنشاء من الصفر أو الاستيراد من التخزين أو GitHub أو إنشاء المهارة بواسطة AIRI.

بعد قراءة المرفق كاملًا ومراجعة الشيفرة، اتضح أن AIRI يمتلك أساسًا حقيقيًا وليس شاشة شكلية: `AiriSkill`، و`SkillManifest`، و`SkillRegistry`، و`AiriSkillOrchestrator`، و`SkillInvocationAccessPolicy`، و`SkillPackageVerifier`، ومهارات تنفيذية رسمية، ومستودع Custom Skills، ومسارات الإنشاء والاستيراد الأربعة.

## ما هو موجود فعليًا

| المجال | الحالة الحالية |
|---|---|
| عقد المهارة | موجود عبر `AiriSkill` مع الهوية والوصف والإصدار والمؤلف والفئة والأذونات والذاكرة والنموذج والأدوات والتبعيات والتنفيذ |
| Manifest | موجود type-safe عبر `SkillManifest` مع JSON compatibility وchecksum وversioning وendpoint |
| Registry | موجود، يسجل المهارات الرسمية، يضم المهارات المخصصة، يدعم التفعيل والتعطيل والإصدارات والتبعيات والتسجيل الديناميكي |
| Planner/Agent | موجود `AiriSkillOrchestrator` ويقوم بالتحليل والترشيح والتحقق وبناء خطة |
| الأمان | موجود `SkillInvocationAccessPolicy` و`SkillPackageVerifier` والتحقق من HTTPS والصلاحيات والموصلات |
| المهارات الرسمية | 15 مدخلًا رسميًا و14 صنف تنفيذ فعليًا، وليست بطاقات وهمية |
| الإنشاء والاستيراد | موجود: إنشاء يدوي، استيراد تخزين، استيراد GitHub، وإنشاء عبر AIRI؛ لن يتم حذفها أو إعادة بنائها بمسار موازٍ |
| الواجهة | جيدة بصريًا وقريبة من المستوى المطلوب، لكن يوجد نقص في الترجمة، metadata، الفئات، وبطاقات التفاصيل |

## الفجوات المؤكدة

1. عقد `AiriSkill` و`SkillManifest` لا يصرحان بعد بكل حقول المرفق: `inputSchema` و`outputSchema` و`instructions` و`examples` و`limitations` و`riskLevel` و`requiresConfirmation` و`supportsStreaming` و`supportsAttachments`.
2. `SkillRegistry` يحتوي التسجيل والتنفيذ، لكنه يحتاج API موحدًا صريحًا للبحث بالمعرّف والفئة والقدرات والمهارات المفعلة والمتطلبات حتى لا تعتمد الواجهات أو Planner على قوائم متفرقة.
3. مكتبة `OfficialSkillLibrary` تحتوي 15 مهارة فقط؛ إضافة 60 مهارة يجب أن تكون على دفعات تنفيذية حقيقية مرتبطة بأدوات AIRI، لا مجرد descriptors أو ملفات توثيق.
4. مطابقة الفئة في `SkillManagerScreen` تستخدم `info.name` بدل المعرّف في موضع يعتمد على `manifest.id`؛ هذا يجعل الفلترة والفئة قابلة للانحراف.
5. توجد نصوص إنجليزية مستخدمية hard-coded في مسارات استيراد وإنشاء المهارات، مثل أخطاء endpoint ورسائل التحذير والتحقق، رغم وجود موارد عربية.
6. `SkillDetailsScreen` يعرض المهارات الرسمية فقط، ولا يعرض كل metadata المطلوبة مثل مستوى الخطورة، التأكيد، المرفقات، streaming، الأمثلة، والقيود.
7. توجد حقول في الواجهة مكتوبة بلغة برمجية أو بصياغة غير مترجمة مثل أسماء الفئات و`memoryAccess` و`modelAccess`.

## القرار التصميمي عند التعارض

- **اللغة:** اعتماد Android resource localization و`AppCompatDelegate.setApplicationLocales`/per-app language APIs بدل تبديل نصوص يدوي أو فرض RTL عالمي. هذا هو المسار الموصى به رسميًا، ويحافظ على الموارد ويعالج Android 13+ مع توافق AndroidX للإصدارات الأقدم.
- **التخطيط:** الحفاظ على تخطيط البطاقات الحالي مع تحسين التباعد والـmetadata والـresponsive behavior، بدل استبداله بالكامل. توصي Android بالتخطيط التكيفي وwindow size classes، لكن التغيير المرحلي أقل مخاطرة لشاشة هاتف تعمل جيدًا.
- **الاستيراد من GitHub:** الإبقاء على المسار الحالي الآمن القائم على HTTPS وmanifest والتحقق. GitHub Contents API يدعم raw/object media types، لكن استبدال المستورد الحالي بعميل GitHub API كامل ليس ضروريًا لمتطلبات skill.json الحالية وسيزيد سطح الفشل.
- **التوثيق:** اعتماد `SKILL.md` مستقل لكل مهارة مستقبلية مع manifest type-safe؛ لا يتم اعتبار التوثيق تنفيذًا ما لم يوجد executor أو endpoint حقيقي.
- **الأمان:** عدم السماح للمهارة بتجاوز `SkillInvocationAccessPolicy` أو sandbox، وعدم اعتبار `requiresConfirmation` مجرد شارة UI.

## خطة التنفيذ

### المرحلة 1: عقد موحد متوافق للخلف

إضافة الحقول الناقصة إلى `AiriSkill` و`SkillManifest` بقيم افتراضية محافظة، مع JSON round-trip tests، دون تغيير signatures الحالية أو مسارات CustomSkill.

### المرحلة 2: Registry API موحد

إضافة `findById` و`search` و`byCategory` و`enabledSkills` و`requiredCapabilities` و`compatibility` إلى `SkillRegistry`، ثم جعل الواجهة وPlanner يستفيدان من نفس المصدر بدل hard-coded lists.

### المرحلة 3: الترجمة والواجهة

نقل كل النصوص التي تصل للمستخدم إلى resources، إضافة العربية والإنجليزية للفئات والحقول الأمنية، تصحيح مطابقة `id`، وإضافة metadata في بطاقة التفاصيل مع الحفاظ على شكل البطاقات الحالي.

### المرحلة 4: مكتبة المهارات

إضافة المهارات على دفعات حسب فئات المرفق. كل دفعة يجب أن تملك تنفيذًا فعليًا أو endpoint موثقًا أو composition مع مهارات موجودة، واختبارات permission/input/failure. لا تُضاف مهارات placeholder للوصول إلى رقم 60.

### المرحلة 5: الاختبار

اختبارات Registry وmanifest وimport validation وpermission/confirmation، ثم Android instrumentation لمسارات الإدارة واللغة، ثم build/test على CI. لا تعتبر المهمة مكتملة مع وجود compilation errors.

## ما لن يتغير

لن يتم حذف أو تعطيل أو استبدال:

- إنشاء مهارة جديدة يدويًا.
- استيراد skill من التخزين.
- استيراد skill من GitHub.
- إنشاء skill بواسطة AIRI.
- CustomSkillRepository أو GitHubSkillImporter أو SkillPackageVerifier.
- هوية AIRI البصرية أو تخطيط الشاشة الأساسي دون اختبار مقارنة.

## مصادر القرار

- [Android per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages)
- [Android localization best practices](https://developer.android.com/guide/topics/resources/localization)
- [Android adaptive apps](https://developer.android.com/develop/ui/compose/build-adaptive-apps)
- [GitHub repository contents API](https://docs.github.com/en/rest/repos/contents)
- [iflytek/skillhub](https://github.com/iflytek/skillhub) — مرجع مفتوح المصدر ناضج لفكرة registry، تمت مراجعته كمرجع معماري لا كاعتماد مباشر.

## القاعدة الحاكمة

لن يتم رفع أي تغيير يضيف عددًا شكليًا من المهارات أو يكسر أحد مسارات الإضافة الحالية. كل تغيير يجب أن يمر بفحص compile/test وdiff review على `main` و`cp-foundation`.

## المرحلة المنفذة في هذه الدفعة

تم تنفيذ عقد metadata المتوافق للخلف في `AiriSkill` و`SkillManifest` مع JSON round-trip، وإضافة API موحد للبحث والفئات والمهارات المفعلة والقدرات، وتصحيح فلترة الفئات بالـID، وتوطين رسائل الاستيراد والتحقق، وإظهار metadata الأمن والتنفيذ في شاشة التفاصيل، وإضافة اختبار JVM مستقل للعقد.

التحقق الساكن نجح: لا توجد فروقات whitespace، ولا أسماء موارد مكررة، ولا بقيت رسائل التحقق الإنجليزية الصلبة في المسارات التي عولجت. تعذر تشغيل Gradle محليًا قبل compilation بسبب غياب Android SDK في Sandbox؛ لذلك لم يتم الادعاء بنجاح البناء.

## المرحلة الثانية — الدفعة الأولى المنفذة

أضيفت 11 executor رسمية حقيقية model-backed عبر `ModelUtilitySkill`: `summarizer`, `email_drafter`, `text_rewriter`, `sentiment_analyzer`, `json_extractor`, `decision_matrix`, `study_tutor`, `interview_coach`, `sql_assistant`, `meeting_agenda`، و`requirements_extractor`.

كل executor يملك كلمات توجيه عربية وإنجليزية، input/output contract، system policy، أمثلة، limitations، score مستقل، وإرجاعًا صريحًا للفشل عند غياب `SkillContext` أو النموذج. لا تنفذ مهارات البريد أو SQL أي إجراء خارجي: الأولى تصيغ فقط، والثانية تكتب أو تشرح فقط ولا تشغّل الاستعلام.

تم ربط الدفعة تلقائيًا بـ`OfficialSkillLibrary` و`SkillRegistry` حتى تظهر في الاكتشاف والفلترة والتفعيل والـPlanner بنفس المصدر الرسمي، مع اختبار uniqueness/routing/execution/failure.

## المرحلة الثانية — الدفعة الثانية المتقدمة

أضيفت ثماني مهارات متقدمة متعددة المراحل عبر `AdvancedModelSkill`: `fact_checker`, `code_review_advanced`, `data_insight_advanced`, `security_threat_model`, `test_strategy`, `architecture_advisor`, `prompt_evaluator`, و`incident_analyzer`.

كل مهارة تنفذ مرحلتين أو أكثر عبر `SkillModelBridge`: استخراج/تحليل أولي، ثم تحقق أو مقارنة أو صياغة نهائية. يتم تمرير مخرجات كل مرحلة إلى المرحلة التالية، وتتوقف السلسلة بفشل صريح عند فشل أي مرحلة أو غياب النموذج. لا تنفذ هذه المهارات إجراءات خارجية، ولا تدعي تصفح مصادر أو تشغيل كود أو اختبارات.

تمت إضافة manifests وأدوات وقيود ومخاطر `MEDIUM` واختبارات للسلسلة كاملة، وتم إصلاح فشل CI السابق في عقد `UserBubble` بإعادة `Arrangement.Start` المطلوبة من محاكاة الواجهة. محاكاة `tools/airi_runtime_simulation.py` مرت بحالة `PASS` بعد الإصلاح.

## المرحلة الثالثة — مهارات الأداء وجودة الكود

أضيفت ثماني مهارات مخصصة عبر `EngineeringQualitySkill`: `performance_profiler`, `memory_leak_auditor`, `startup_latency_auditor`, `compose_recomposition_auditor`, `concurrency_auditor`, `complexity_reducer`, `api_quality_reviewer`, و`refactoring_planner`.

كل مهارة تستخدم ثلاث مراحل: جرد الأدلة، ترتيب findings مع severity/confidence/evidence، ثم خطة إصلاح واختبار تحقق. التعليمات تمنع اختراع benchmark أو trace أو measured value، وتوضح أن التحليل لا يشغّل الكود ولا يثبت غياب كل التسريبات أو السباقات دون أدوات القياس المناسبة.

تمت إضافة اختبارات routing، اختلاف مجالات PERFORMANCE وQUALITY، chaining للمراحل الثلاث، تمرير الأدلة بين المراحل، والفشل الصريح عند توقف مرحلة أو غياب النموذج.

أثناء تدقيق CI للدفعة الثانية ظهر فشل localization parity مستقل: 21 مفتاحًا ناقصًا في `values-es` و`values-zh`. أضيفت الترجمات المطلوبة، وأصبح الفحص المحلي يثبت صفر مفاتيح ناقصة في `values-ar` و`values-es` و`values-zh`، مع مرور runtime simulation بحالة `PASS`.
