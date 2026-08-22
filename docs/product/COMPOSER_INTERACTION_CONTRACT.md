# عقد تفاعل الملحن

**الحالة:** `IMPLEMENTATION_COMPLETE` لمسار اختيار `/skill` و`@knowledge` مع الحفاظ على نص المستخدم. **`RUNTIME_VERIFICATION_PENDING`** للتحقق البصري وTalkBack وIME على جهاز Android حقيقي. لا يدعي هذا العقد إعادة تصميم كاملة للملحن أو اكتمال ترجمة كل واجهات المنتج.

## المشكلة المغلقة

كان اختيار اقتراح مهارة أو معرفة يستبدل حقل الملحن كله. لذلك كان المستخدم الذي يكتب مثلاً `/web ابحث عن نماذج محلية` يفقد النص بعد اختيار skill من الاقتراحات، ويصل للوكيل directive من دون هدف المهمة.

## السلوك المنفذ

`ComposerDirectivePolicy.applySelection` يستبدل directive الأول فقط ويحتفظ بكل النص الذي يلي أول مسافة. يعمل ذلك مع المسافات البادئة ومع العربية، ثم يعود الملحن بالنص الجاهز إلى `ChatScreen` كما كان قبل الاختيار.

| إدخال المستخدم | اختيار الاقتراح | النص الناتج |
|---|---|---|
| `/web research Android offline models` | `web_search` | `/skill:web_search research Android offline models` |
| `@docs لخص مواصفات الواجهة` | `project_docs` | `@knowledge:project_docs لخص مواصفات الواجهة` |
| `/code` | `code_assistant` | `/skill:code_assistant ` |

لا يحفظ policy أو يرسل أي نص؛ الإرسال الفعلي وإلغاء التوليد وتفسير directive تبقى في `ChatViewModel` ومسار agent الموجود.

## الأدلة

| الدليل | التغطية |
|---|---|
| `ComposerDirectivePolicyTest` | حفظ نص إنجليزي وعربي، والحالة الفارغة |
| `:app:compileDebugKotlin` | ربط policy باختيار اقتراحات Composer الحية وإضافة semantics لبطاقة الاقتراح |

## فجوات الإغلاق الصريحة

تدمج بطاقة الاقتراح descendants وتعرض لقارئ الشاشة الفئة المترجمة والعنوان والوصف كزر واحد، بدلاً من تكرار وصف الأيقونة. تظل محتاجة تحقق TalkBack وترتيب focus ولقطات هاتفية عربية/إنجليزية فعلية وIME. أما فحص صحة الترجمة فيسجل الآن `likely_untranslated_values=0` للإسبانية والصينية؛ راجع `LOCALIZATION_HEALTH_CONTRACT.md` لحدود المراجعة اللغوية البشرية.
