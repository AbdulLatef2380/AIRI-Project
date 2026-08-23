# عقد إيقاف العمل النشط

## الغرض

يوفر AIRI إجراء **إيقاف العمل النشط** في شاشة المهام. يعالج الإجراء الأعمال التي تملك طبقات AIRI الحالية واجهة إلغاء حقيقية لها، ويعرض نطاقه قبل التأكيد. لا يقدم ادعاءً عاماً بـ«إيقاف كل شيء» لأن بعض المسارات الخارجية لا تملك بعد lifecycle cancellation موحداً.

| المجال | المسار الحي | الإجراء عند التأكيد | الدليل |
|---|---|---|---|
| خطط الوكلاء الجارية | `ProductionAgentOrchestrator` | يلغي scope الخطة الجاري ثم ينشئ scope جديداً للخطط التالية. | `ProductionAgentOrchestrator.cancelAll()` و`ProductionAgentOrchestratorCancellationTest`. |
| المهام الدائمة | `DurableTaskManager.activeTasks()` | يلغي WorkManager الخاص بالمهمة ويضيف `TASK_CANCELLED` إلى timeline. | `DurableTaskManager.cancel(taskId)`. |
| أتمتة المستخدم المجدولة | `ScheduledJobOrchestrator` | يلغي كل وظيفة لا يملكها agent المحجوز `system` ويحذفها من تخزين الجدولة. | `cancelAllUserJobs()`. |
| الأمر الجاري في الطرفية | `TerminalRuntime` | يلغي Job الجاري؛ يظل `SandboxExecutor` مسؤولاً عن تنظيف العملية. | `cancelActiveCommand()`. |
| سجل التدقيق | `AuditRepository` | يسجل **عدادات فقط** للمهام/الوظائف/الطرفية التي تأثرّت. | `ACTIVE_WORK_STOP` audit event. |

## الحدود المقصودة

لا يلغي هذا الإجراء connector action أو browser action بدأ بالفعل، لأن `ConnectorRuntimeManager` يحتفظ بتتبع inflight لكنه لا يملك واجهة `cancel` أو supervisor-level stop. كما لا يعالج desktop/VNC أو remote node؛ لا توجد تلك runtimes كطبقة تشغيل AIRI مكتملة بعد.

> لا يتحول هذا الإجراء إلى إيقاف شامل صادق إلا بعد إضافة cancellation contract لكل runtime خارجي، ثم اختبار الإلغاء والـcleanup وعدم تنفيذ أي callback متأخر لكل واحد.

## التحقق

| البوابة | النتيجة في هذه الدفعة | نطاقها |
|---|---|---|
| `:app:compileDebugKotlin` | `LOCAL_VERIFIED` | يثبت أن حاوية الخدمات والمتحكم والواجهة والموارد تتجمع. |
| `tools/verify_core_changes.py` | `LOCAL_VERIFIED` | 46/46، ومنها ضمان scope الإيقاف وإعادة التشغيل. |
| `scripts/airi_localization_health.py --strict` | `LOCAL_VERIFIED` | parity والموارد الإسبانية/الصينية لا تحتوي قيمة مرجح أنها إنجليزية. |
| `ProductionAgentOrchestratorCancellationTest` | `RUNTIME_VERIFICATION_PENDING` | كُتب الاختبار، لكن تشغيل `testDebugUnitTest` المحدد تجاوز metaspace في جلسة sandbox قبل مرحلة الاختبار. |
| TalkBack/WorkManager على جهاز | `RUNTIME_VERIFICATION_PENDING` | يتطلب جهاز Android حقيقياً. |
