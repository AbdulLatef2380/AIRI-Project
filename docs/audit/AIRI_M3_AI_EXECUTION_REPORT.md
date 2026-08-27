# AIRI — تقرير تدقيق وإصلاح AI Execution (M3)

## نطاق الدفعة

ركزت هذه الدفعة على ملكية أحداث التنفيذ التي تغذي لوحة التخطيط، وعلى منع الأحداث القديمة أو غير المعرّفة من تعديل حالة تنفيذ آخر. لم تُفتح إعادة كتابة للـrouter أو provider integrations خارج هذا الجذر.

## العيب الجذري والإصلاح

| Root cause | العيب المثبت | الإصلاح المنفذ | الدليل |
|---|---|---|---|
| RC-01 / RC-03 | `ExecutionStatusBus` كان يملأ executionId الافتراضي من الحالة الحالية، مما يسمح لحدث بلا هوية صريحة أن يُنسب إلى التنفيذ النشط | أصبحت كل defaults فارغة، وأضاف bus predicate صريح `acceptsEvent` لا يقبل إلا هويتين غير فارغتين ومتطابقتين | `ExecutionStatusBusTest` + Deep/Architecture Audit |
| RC-01 | `AdaptiveGraphEngine` كان يرسل node events دون executionId | يولّد engine هوية ثابتة لكل graph execution، ويجري graph admission قبل node events، ثم يمرر الهوية إلى running/completed/recovering/completed/cancelled | Android CI وArchitecture Audit |
| RC-03 | graph execution لا يغلق حالة لوحة التخطيط عند completion/cancellation/error بملكية واضحة | أضيفت أحداث graph lifecycle المرتبطة بالهوية نفسها في AdaptiveGraphEngine | Android CI instrumentation |
| Validation | اختبار regression الأول استدعى Android Log من JVM unit test وفشل بسبب بيئة Android غير متاحة في الاختبار | استُخرج predicate نقي واختُبر دون Android runtime؛ لم يتم إخفاء الفشل أو حذف الاختبار | Android CI المعاد |

## الاختبارات والأدلة

| الفحص | النتيجة | المرجع |
|---|---|---|
| أول Android CI على `0ccf5175` | فشل صادق: اختباران استدعيا `ExecutionStatusBus` الذي يكتب Android Log من JVM | [33069493138](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33069493138) |
| الإصلاح وإعادة الاختبار | أُصلح الاختبار بإبقاء regression على predicate النقي | commit `06115624` |
| AIRI Android CI | PASS؛ compile/unit/lint/release packaging/signing/instrumentation/native/artifacts | [33070581767](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581767) |
| AIRI Deep Audit | PASS | [33070581782](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581782) |
| AIRI Oracle | PASS | [33070581804](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581804) |
| AIRI Architecture Audit | PASS | [33070581824](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581824) |
| `git diff --check` وsource precheck | PASS قبل الدفع | سجل محلي |

## حالة الدفعة

الإغلاق الحالي هو commit `06115624fac5954ca9919ff4368392fada88c99b`، ودُفع إلى `cp-foundation` ثم fast-forward إلى `main`. شجرة العمل نظيفة. لا يثبت هذا التقرير أجهزة ARM64 فعلية أو مزودات خارجية حية؛ تلك البوابات موثقة منفصلة في مصفوفة M9.

## القيود المتبقية

هذه الدفعة لا تغلق cancellation الشبكي العميق أو event ledger أو provider fallback semantics بالكامل. كما أن الرسائل الظاهرة في بعض طبقات التنفيذ ما زالت تحتاج مرور localization ضمن M8؛ لم أخلط ذلك مع إصلاح ownership الحالي.

## الخطوة التالية

الدفعة التالية هي مراجعة cancellation/fallback في `HybridOrchestrator` و`CloudBackend`، مع اختبار أن الإلغاء لا يواصل retry أو يطلق fallback بعد إلغاء المستخدم، وأن كل نتيجة terminal تُسجل مرة واحدة.

## References

[1]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581767 "AIRI Android CI — commit 06115624"

[2]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581782 "AIRI Deep Audit — commit 06115624"

[3]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581824 "AIRI Architecture Audit — commit 06115624"

[4]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33070581804 "AIRI Oracle — commit 06115624"


## جولة cancellation اللاحقة

في commit `84da7434892551d2da6d18499d028cea30aded47` أضيف `cancelRequested` ذري إلى `CloudBackend` مع `cancelStream()` غير حاجز. يتوقف مسار provider failover وRetryPolicy فوراً عند الإلغاء أو `CloudErrorType.CANCELLED`، ولا يبدأ طلباً جديداً بعد الإشارة. كما يعيد `generate()` نتيجة `cancelled` عند وجود signal سابق.

نجحت تشغيلات CI الأربعة لهذه الجولة: Android CI `33073079681`، Deep Audit `33073079638`، Oracle `33073079615`، وArchitecture Audit `33073079670`. شمل Android CI unit tests وlint وcompile وrelease signing وinstrumentation والتحقق من native output والـartifacts. لا يثبت ذلك اختبار مزود حي أو جهاز ARM64 فعلياً خارج بيئة CI.


## جولة terminal callback الأخيرة

في commit `9122f3f63d477c46d19b1577e933db37cf2df97d` أضيف `completionDelivered` داخل محاولة backend في `HybridOrchestrator` لمنع تسليم `onComplete` أكثر من مرة لنفس generation حتى إذا أرسل adapter callback مكرراً. نجحت تشغيلات CI الأربعة: Android CI `33075669176`، Deep Audit `33075669201`، Oracle `33075669167`، وArchitecture Audit `33075669230`. شمل Android CI unit tests وlint وcompile وrelease signing وinstrumentation والتحقق من native output والـartifacts.
