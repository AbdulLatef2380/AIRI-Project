# Durable Step Execution Boundary Batch — 2026-11-21

هذه الدفعة تطبق جزءاً محدداً من P0-B2: منع الـgeneric WorkManager worker من إعادة تشغيل خطوة مكتملة أو استئناف أثر جانبي حالته `CLAIMED` أو `UNKNOWN` دون reconciliation صريح.

أُضيف `DurableTaskExecutionBoundary` كعقد pure يختار خطوة `PENDING` أو `PAUSED` فقط، يتحقق من المهمة غير الطرفية، يحمل checkpoint الحالي، ويرفض العمليات ذات النتيجة الملتبسة أو الخارجية غير المحسومة. كما أصبح WorkManager يحمل `step_id` مع `task_id`، ويجتاز هذا العقد قبل `beginRun`، مع تمرير lease للـrun والـstep إلى مسار التنفيذ.

أُضيفت اختبارات JVM لحالات اختيار الخطوة التالية، حمل checkpoint، رفض الخطوة المكتملة، ورفض operation بحالة `CLAIMED` أو `UNKNOWN`.

التحقق الساكن عبر `git diff --check` مطلوب قبل الدفع. محاولة Gradle قد تتوقف قبل compilation في بيئة لا تحتوي Android SDK؛ لا يُعد ذلك دليلاً على فشل الاختبارات نفسها، بل قيداً بيئياً يجب ذكره في تقرير التسليم.
