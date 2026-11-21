# P0-B3 — Approval Continuation Batch

التاريخ: 2026-11-21
الفرع: `self-agent-implementation`

## النطاق المنجز

- تم تثبيت أن الموافقة المنتهية لا يمكن أن تتحول إلى `APPROVED` حتى عند استدعاء aggregate مباشرة؛ حالة القرار الفعلية تصبح `EXPIRED` وتُرفض continuation المعلقة.
- تم تغيير `claimApprovedContinuation` بحيث لا يعيد تشغيل نفس `run_id`. بعد نجاح claim:
  - يبقى run الذي طلب الموافقة محفوظاً بحالة `PAUSED` كسجل تاريخي.
  - ينشأ run جديد وفريد للمهمة نفسها.
  - تُنقل step التنفيذ إلى run الجديد وتصبح `RUNNING`.
  - يُحدّث execution identity للـ approval والـ continuation إلى run الجديد.
  - تُحفظ continuation بحالة `CLAIMED` قبل أن يستدعي runtime أي أثر جانبي.
- بقيت حواجز الملكية الدقيقة (`task_id`, `mission_id`, `project_id`, `run_id`, `step_id`) والتحقق من descriptor كما هي، مع منع claim ثانٍ أو claim بعد expiry.
- تم تحديث اختبارات kernel لتثبت run الجديد، بقاء run القديم متوقفاً، ورفض إعادة claim.
- أحداث timeline الموجودة في manager تغطي `APPROVAL_REQUESTED`, `APPROVAL_PAUSED`, `APPROVAL_DECIDED`, `APPROVAL_RESUMED`, و`APPROVAL_CONTINUATION_COMPLETED`، وتستخدم run/step identity الجديدة عند الاستئناف.

## التحقق

- `git diff --check`: نجح.
- `./gradlew test --no-daemon`: تعذر الإقلاع أثناء تهيئة المشروع لأن Android SDK غير موجود في البيئة:
  `SDK location not found`، ولا يوجد `ANDROID_HOME` أو `local.properties` صالح.

## الملفات

- `app/src/main/java/com/airi/assistant/agent/durable/DurableTask.kt`
- `app/src/test/java/com/airi/assistant/agent/durable/DurableTaskProductKernelTest.kt`
- `reports/APPROVAL_CONTINUATION_BATCH_2026-11-21.md`
