# طبقة الوكيل

تملك هذه الحزمة تخطيط الوكيل وتشغيل المهام الفرعية وسجل التنفيذ الدائم والمهام المجدولة. وهي جزء من مسار **Android** في فرع `cp-foundation` ضمن **Feature Freeze**؛ لا تمثل هذه الوثيقة موافقة تشغيل مزود حي أو نشر عام.

## مصادر الحقيقة والملكية

| المجال | المصدر المالك | السلوك المثبت |
|---|---|---|
| خطة متعددة المهام | `ProductionAgentOrchestrator` مع `AgentTeamPolicy` | يقبل المعرفات والتبعيات والحدود السحابية والسياق المعزول قبل routing. لا تُقبل تبعية ذاتية أو مجهولة أو دورة known dependencies. |
| المهمة/run/step | `DurableTaskManager` و`MissionKernel` | تسجل التنفيذ الدائم والموافقات وملكية project/task/run/step؛ لا يكفي UI state لإثبات التنفيذ. |
| نتيجة الخطة | `ExecutionResult` | لا تنتج `Success` إلا بعد اكتمال كل task. دورة أو تبعية غير قابلة للحل أو إلغاء قبل الإكمال تنتج فشلاً جزئياً، ولا يجوز لواجهة execution graph تحويلها إلى completion. |
| المهام المجدولة | `ScheduledJobOrchestrator` و`ScheduledAgentWorker` | metadata محلية دائمة، unique WorkManager request، outcome (`PENDING`/`RETRYING`/`COMPLETED`/`FAILED`) وlink اختياري إلى durable task. |

## حدود الجدولة والأمان

المهمة الخلفية ليست جلسة UI تفاعلية: لها budget محدود ولا تفترض إذناً أمامياً أو موافقة حية. أخطاء domain تسجل للواجهة ولا تعاد تلقائياً إلا للأخطاء transient المحددة. `runNow` يحتاج تأكيداً مرئياً ولا يستبدل cadence المجدول ولا يسمح بطلبين يدويين نشطين للمهمة نفسها.

المعرف المحجوز `system` ليس وكيلًا عاماً. لا يقبل إلا صيانة `sandbox_reaper` و`audit_log_pruner` و`context_cache_pruner`. أي payload غير معروف يُرفض قبل التخزين، وتغلق worker المدخلات القديمة أو المعدلة بفشل مسجل بدلاً من تمريرها إلى orchestrator.

`AgentLoop` لا يمنح أثراً جانبياً عاماً من جلسة chat. تتطلب الأدوات المتغيرة durable task/run/step وسياستها typed approval، وتبقى مسارات غير المصرح بها fail-closed.

## الدليل والحدود

اختبارات JVM تغطي قبول/رفض خطط الفريق، ومن ضمنها cycle، وحجز payload النظام، واستمرارية scope بعد `cancelAll`. يتحقق الحارس الثابت من عقد no-false-success ومسار run-now. تؤكد CI التجميع والاختبارات وinstrumentation المتاح، لكنها لا تثبت WorkManager في Doze/OEM/reboot أو صلاحيات ومزودين حقيقيين؛ تبقى هذه ضمن مصفوفة real-device قبل أي إطلاق.
