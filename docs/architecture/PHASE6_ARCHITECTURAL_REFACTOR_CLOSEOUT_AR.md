# إغلاق المرحلة 6 — refactoring معماري تدريجي

**الحالة:** مغلقة على `main` الحالي

**الرأس الذي تمت مراجعته:** `eee12e59 refactor: unify chat lifecycle teardown`

## القرار المعماري

تتبنى AIRI حدودًا تدريجية بين واجهة Android والمجالات المشتركة دون إعادة بناء واسعة أو تغيير السلوك الملحوظ. يبقى `ChatViewModel` منسقًا لحالة العرض وcommands وnavigation/focus، بينما تُستخرج منه حدود ownership قابلة للاختبار:

- `ModelLifecycleCoordinator`: startup/load/unload/stop لدورة النموذج.
- `AttachmentDispatchCoordinator`: admission للملفات والقدرات والموافقة قبل staging.
- `ConversationExecutionCoordinator`: dispatch/cancel/teardown لمسار التنفيذ.
- `ChatLifecycleCoordinator`: startup وclose idempotent وحد واحد لإغلاق الموارد.
- `CustomSkillRuntimePort`: يعزل `CustomSkillExecutor` عن `ServiceLocator` وAnalytics singleton، ويحقن network/telemetry من composition root.

تبقى عمليات حذف الحساب التي تتعامل مع WorkManager وRoom وfilesystem وSharedPreferences وFirebase داخل adapter Android (`DataDeletionCoordinator`). وهي لا تستورد `ServiceLocator` من domain؛ أما remote deletion فله port مستقل هو `RemoteAccountDataDeletion`. هذا الحد يمنع تسرب ServiceLocator إلى domain دون إنشاء abstraction مصطنع لعمليات Android التي لا يمكن مشاركتها بين Android/Desktop.

## سجل التنفيذ

تمت مراجعة commits المرحلة 6 الموجودة على `main`:

1. `554c1443` — عزل Android attachment payloads وإضافة metadata محايدة.
2. `c154fd54` — استخراج Model lifecycle coordinator.
3. `75c4cffd` — استخراج attachment dispatch وconversation execution coordinators.
4. `8920e1cf` — تفعيل lifecycle وcustom-skill runtime ports.
5. `eee12e59` — توحيد chat lifecycle teardown.

كل extraction له commit مستقل في التاريخ، مع الاختبارات الخاصة به أو اختبارات contract المقابلة.

## مطابقة معايير الإغلاق

| المعيار | الدليل | النتيجة |
|---|---|---|
| إزالة الدورات المباشرة | لا يوجد `ServiceLocator` في `CustomSkillExecutor`؛ يعتمد على `CustomSkillRuntimePort` | ناجح |
| حذف البيانات مع حدود واضحة | `DataDeletionCoordinator` يعتمد constructor injection؛ remote deletion عبر `RemoteAccountDataDeletion` | ناجح |
| فصل Android attachment types | `AttachmentMetadata` و`AttachmentPolicy` في `core-domain`؛ `AndroidAttachmentPayload` يحتفظ بـ`Uri`/`Bitmap` خارج العقد المحايد | ناجح |
| عدم تمرير MutableStateFlow للخدمات الجديدة | coordinators تستقبل commands/callbacks فقط؛ state يبقى في ViewModel | ناجح |
| تقسيم ChatViewModel حسب owners | model، attachment، conversation، lifecycle coordinators مربوطة من ViewModel | ناجح |
| lifecycle idempotency | `startOnce`/`closeOnce`/`stopOnce` واختبارات التكرار وclose-before-start | ناجح |
| عدم تغيير trace/session semantics | teardown يحافظ على cancellation وtrace cleanup وsession guards القائمة | ناجح بالمراجعة الساكنة |
| core-domain Android-free | لا توجد Android imports في `core-domain/src/commonMain` | ناجح |

## نتائج التحقق

- `python3 scripts/airi_localization_health.py --strict`: ناجح.
- `python3 scripts/airi_release_health.py`: ناجح.
- `python3 tools/final_runtime_integration_audit.py`: ناجح.
- `python3 tools/verify_core_changes.py`: ناجح، **101/101 checks passed**.
- `git diff --check`: ناجح.
- الاختبارات المستقلة الحالية للـcoordinators تغطي idempotent startup/teardown، close-before-start، load/unload boundaries، dispatch/cancel بعد الإغلاق، attachment admission، والـmetadata/payload boundary.

## حد التحقق

لم يُشغّل `./gradlew` بنجاح داخل Sandbox لأن Android SDK غير متاح في البيئة الحالية؛ هذا عائق بيئي وليس assertion فاشلًا. يجب تشغيل `:app:testDebugUnitTest` و` :core-domain:allTests` وAndroid instrumentation في GitHub Actions أو بيئة تحتوي SDK قبل إصدار release. لا تُعدّل المعمارية أو السلوك لمعالجة هذا القيد.

## عدم التنفيذ المتعمد

لم يتم تقسيم `ChatViewModel` إلى ثلاثة خدمات ضخمة جديدة، ولم تُنقل `Context` أو `WorkManager` أو `Uri` أو `Bitmap` إلى `core-domain`. كما لم تُنشأ مخازن أو abstractions إضافية بلا حاجة. هذا يحافظ على coupling منخفضًا مع إبقاء composition root مسؤولًا عن wiring Android.
