# عقد لوحة خطة الوكيل

## الغرض والنطاق

تعرض لوحة التخطيط للمستخدم مسار تنفيذ AIRI الحالي، بحيث يرى **مرحلة الوكيل** والخطوات المعروفة وحالة كل خطوة والزمن المنقضي في أثناء التنفيذ. لا تنشئ اللوحة خطة مستقلة ولا تنفذ أدوات أو أوامر؛ إنها إسقاط قابل للقراءة من `ExecutionStatusBus` إلى واجهة Compose.

| المسار | المصدر الحي | السلوك المنفذ |
|---|---|---|
| بدء التخطيط | `ExecutionStage.PLANNING` | ينشئ `TaskExecutionTracker` جذر المهمة وخطوات انتظار عند توفر عدد العقد. |
| تنفيذ خطوة | `ExecutionStage.EXECUTING` | يستبدل placeholder بعقدة التنفيذ أو يحدّث العقدة الفعلية إلى `RUNNING`. |
| استرداد أو محاولة جديدة | `ExecutionStage.RECOVERING` | يعرض حالة `RETRYING` وسبب الاسترداد المحدود. |
| اكتمال أو فشل | `COMPLETED` أو `FAILED` | ينهي الخطوات النشطة بالحالة المناسبة ويحتفظ بها لحين طيّ اللوحة. |
| الزمن المنقضي | `PlanStepModel.startedAtMs` | يعيد Compose حساب الزمن كل ثانية للخطوة النشطة فقط. |

## حدود الأمان والخصوصية

تستخدم اللوحة labels وdetails التي يرسلها مسار تنفيذ AIRI. لا تضيف سجلات جديدة ولا تعرض secrets أو response bodies للمزوّد. تطبق حدود `PrivacyGuard` و`CloudErrorMapper` و`RetryPolicy` قبل أن تصل أخطاء cloud المنقحة إلى diagnostics.

## الإتاحة والترجمة

تملك كل خطوة وصفاً موحداً لقارئ الشاشة بصيغة **الحالة: عنوان الخطوة**، كما يملك زر الإغلاق وصفاً مترجماً. حالات المرحلة والخطوة والزمن متاحة في `values` و`values-ar` و`values-es` و`values-zh`؛ ويظل اختبار TalkBack واللمس وrotation على جهاز Android ضمن `RUNTIME_VERIFICATION_PENDING`.

## الدليل المحلي

| الدليل | النتيجة | ما يثبته |
|---|---|---|
| `:app:compileDebugKotlin` | `LOCAL_VERIFIED` | سلامة Compose والمراجع والموارد. |
| `scripts/airi_localization_health.py --strict` | `LOCAL_VERIFIED` | key parity وعدم وجود قيمة مرجح أنها إنجليزية في es/zh. |
| `tools/verify_core_changes.py` | `LOCAL_VERIFIED` | حدود النواة الحالية وسياسات التنفيذ والخصوصية. |

> **لا يزال معلّقاً:** lint الكامل في هذه الجلسة تجاوز حد الذاكرة أثناء `lintAnalyzeDebug`، لذلك لا يمثل هذا العقد نجاح lint جديداً. كما أن عرض خطة تنفيذ حقيقية على جهاز Android ومزامنة progress عبر جهاز ثانٍ يتطلبان تحققاً خارجياً منفصلاً.
