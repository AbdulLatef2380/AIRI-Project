# AIRI — تقرير تقدم Agent Planning & Execution Trace

> **الحالة:** تنفيذ مرحلي. لا يساوي نجاح CI إغلاق تجربة المستخدم أو بوابات runtime الخارجية.

## الدفعات المنفذة

| Commit | التغيير المثبت | الدليل |
|---|---|---|
| `19befb7d4c7e62dafe9f9e9e3579189d764fe534` | احتفاظ `ActivityEvent` بـ`executionId` وربط أحداث الحالة به. | Android CI `33090117930`، Deep `33090117913`، Architecture `33090117902`، Oracle `33090117983`. |
| `5dfc7afbd53baa8eab203e3a3a0e9e2366ebfa5c` | `PrivacyGuard.redactForTrace` وحجب محتوى trace المعروض، مع اختبار سلبي للأسرار والمسارات. | Android CI `33102945878`، Deep `33102945872`، Architecture `33102945882`، Oracle `33102945908`. |
| `aff4df606b4d7cd69260a8f43ad1f5f5bded3b6c` | حجب مدخل pipeline في سجل تشخيص الوكيل. | Android CI `33105515450`، Deep `33105515447`، Architecture `33105515510`، Oracle `33105515429`. |
| `b06bfd143b6a5125139f97b03fd282c5c7987ac6` | `ExecutionTraceBuffer` محدود ومتسلسل، و`ExecutionStatusBus.trace` الذي ينشر مراحل graph الصحيحة فقط. | Android CI `33108069796`، Deep `33108069822`، Architecture `33108069818`، Oracle `33108069801`. |
| `34016308c607ca33b26f72cef637aa4ede26f51b` | عزل `ActivityFeedComposable` داخل Chat بالـ`executionId` النشط. | Android CI `33109969920`، Deep `33109969956`، Architecture `33109969945`، Oracle `33109969935`. |
| `8be8d049faa02d23000bad45ff55d8f5f7a2fbf7` | حجب مدخلات ومخرجات وأخطاء AgentTrace المخزنة مركزياً. | فشل Android CI الأول `33112525371` كشف سيناريو cookie field؛ لم يُخفَ. |
| `3b04224ed83e87e4347511670eca49d8634be192` | حجب قيم حقول trace الحساسة اعتماداً على اسم الحقل؛ أعاد الاختبار السابق إلى النجاح. | Android CI `33113695184`، Deep `33113695208`، Architecture `33113695300`، Oracle `33113695280`. |

## النتائج المثبتة

| المجال | الحالة | الدليل المتوفر | ما لا يزال غير مغلق |
|---|---|---|---|
| execution ownership في feed | **MITIGATED** | Activity events القادمة من ExecutionStatusBus تحمل executionId؛ Chat يعرض السجل المطابق للتنفيذ النشط. | ربط جميع tool/skill event sources بهوية التنفيذ. |
| trace safety | **MITIGATED** | Redaction للـlive trace وAgentTrace المخزن، مع tests سلبية للأسرار والمسارات. | مراجعة كل producer وكل crash/analytics sink. |
| event sequence/retention | **PARTIALLY_DONE** | Buffer محدود بـ150، sequence monotonic، ورفض event بلا owner/summary. | دمج sequence داخل كل tool event واختبارات lifecycle/rotation. |
| planning progress | **PARTIALLY_DONE** | يبدأ trace عند graph admission وينشر planning/step/retry/reflection/terminal. | validation شامل للخطة وdependency/cycle/hierarchy والتفاصيل المستمرة. |
| live trace UI | **PARTIALLY_DONE** | Feed موجود في Chat، filtered بسياق التنفيذ الحالي؛ plan panel قائم. | UI trace مخصص بالـsequence، filters وظيفية، auto-scroll pause/jump-to-latest وUI evidence. |
| tool tracing | **OPEN** | توجد AppEvent للأدوات لكن لا تحمل executionId/actionId/sequence/duration كاملة. | عقد typed وربط المصدر من graph إلى Skill/Tool service. |
| external runtime | **BLOCKED / EXTERNAL_PENDING** | لا ادعاء باختبار Provider أو ARM64 فعلي أو OAuth. | أجهزة ARM64، provider credentials/authorization، واختبارات UI الحقيقية. |

## الفشل المصحح

أظهر Android CI `33112525371` أن اختبار `AgentTraceManagerRedactionTest` يفشل عندما تكون قيمة cookie الحساسة منفصلة عن اسم الحقل. كان ذلك عيب حماية حقيقياً؛ تم إصلاحه في `PrivacyGuard.redactTraceField`، الذي يحجب القيمة اعتماداً على اسم الحقل، ثم نجحت إعادة CI في `33113695184`.

## القرار التالي

الدفعة التالية يجب أن تنقل `executionId` من graph/pipeline إلى SkillService وtool event contract، ثم تربط terminal tool event والمدة والـsequence بالسجل المحدود. لا يجوز وضع UI جديد قبل أن يملك مصدر الأحداث هذه الهوية.
