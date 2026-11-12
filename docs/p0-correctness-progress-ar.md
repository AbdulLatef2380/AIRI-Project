# تقرير تقدم إصلاحات correctness — القسم P0/P1

## نطاق هذا التعديل

تم تنفيذ الدفعة الأولى من القسم `2.1 CloudSync وidempotency` على فرع `main` المحلي، مع إبقاء النطاق محصوراً في مزامنة السحابة، outbox الذاكرة، واختبارات التحقق الحتمية.

## ما تم تغييره

### 1. durable operation ledger وread-after-write

أضيف marker دائم لكل logical operation داخل:

```text
users/{uid}/sync_meta/operations/entries/{sha256(operationId)}
```

ويُكتب marker بعد نجاح كتابة البيانات، ثم يُقرأ مرة أخرى قبل اعتبار العملية ناجحة أو حذف mutation من outbox. طُبق ذلك على:

- profile push
- memory batch push
- task continuity push

إذا حدث timeout بعد قبول Firestore للكتابة وقبل وصول ACK، فستبقى mutation المحلية موجودة ما لم يُثبت marker العملية. عند إعادة التشغيل يُعاد فحص marker، وتُعتمد العملية دون تكرار side effect.

الملف الأساسي: `CloudSyncCoordinator.kt`.

### 2. عزل partial failure

عند فشل batch لاحق، لا تُحذف mutations للدفعات السابقة أو العملية الفاشلة عشوائياً. marker لكل batch يجعل الاستئناف مرتبطاً بالـlogical operation نفسه بدلاً من اعتبار الدورة كلها عملية غير قابلة للتمييز.

### 3. حماية tombstone من الكتابة الأقدم

كان حذف remote tombstone يزيل الذاكرة المحلية وجميع mutations لنفس `memoryId` بلا مقارنة إصدار. أصبح الحذف الآن مشروطاً بـ`remoteUpdatedAtMs`:

- إذا كانت الذاكرة المحلية أحدث، يُتجاهل tombstone.
- لا تُزال من outbox إلا mutations التي يغطيها الإصدار البعيد (`updatedAtMs <= remoteUpdatedAtMs`).

الملفات:

- `MemoryManager.kt`
- `MemorySyncDao.kt`
- `CloudSyncCoordinator.kt`

### 4. منع تصادم operation IDs

لم يعد operation ID يقص UID إلى أول 64 محرفاً. هذا يمنع تحويل مالكين مختلفين لهما نفس prefix إلى نفس logical ID. ولتجنب حدود طول Firestore، يستخدم اسم marker نفسه SHA-256، مع حفظ logical ID الأصلي داخل المستند.

### 5. سياسة retry قابلة للاختبار

فُصلت سياسة `CloudSyncWorker` في `CloudSyncRetryPolicy`، مع اختبار يثبت سلوك المحاولات من `runAttemptCount = 0` حتى الحد النهائي.

### 6. عقد pure deterministic

أضيف `IdempotentSyncOperationExecutor` و`IdempotentSyncTransport` كعقد مستقل عن Android/Firebase. لا يعتبر العقد العملية ناجحة إلا بعد read-after-write. الحالات المدعومة:

- `CommittedAndVerified`
- `CommittedAfterAmbiguousAck`
- `AlreadyApplied`
- `UnknownCommit`
- `Rejected`

واختباراته تغطي:

- نجاح الكتابة مع verification.
- server commit ثم فقدان ACK.
- إعادة التشغيل بعد process death دون side effect ثانٍ.
- transport failure قبل commit.
- partial failure بين عمليتين مستقلتين.

## الملفات المضافة أو المعدلة

- `app/src/main/java/com/airi/assistant/sync/CloudSyncCoordinator.kt`
- `app/src/main/java/com/airi/assistant/sync/CloudSyncOperationId.kt`
- `app/src/main/java/com/airi/assistant/sync/CloudSyncRetryPolicy.kt`
- `app/src/main/java/com/airi/assistant/sync/IdempotentSyncOperation.kt`
- `app/src/main/java/com/airi/assistant/sync/CloudSyncWorker.kt`
- `app/src/main/java/com/airi/assistant/memory/dao/MemorySyncDao.kt`
- `app/src/main/java/com/airi/assistant/memory/repository/MemoryManager.kt`
- الاختبارات المقابلة في `app/src/test/java/com/airi/assistant/sync/`

## التحقق

تم تنفيذ:

- `git diff --check` بنجاح.
- فحص call sites للتأكد من تمرير إصدار tombstone الجديد.
- فحص عدم بقاء `MAX_RETRIES` محلياً داخل `CloudSyncWorker`.
- فحص وجود جميع operation marker ومسارات read-after-write.

تعذر تشغيل Gradle في Sandbox لأن Android SDK غير مثبت، وظهر الخطأ:

```text
SDK location not found. Define a valid SDK location with ANDROID_HOME
or by setting sdk.dir in local.properties.
```

لذلك لا يُعلن هذا التقرير نجاح Android build أو Firestore integration test. يجب تشغيل CI أو بيئة Android تحتوي SDK قبل commit النهائي.

## ما لم يُغلق بعد

هذه الدفعة لا تثبت بعد:

1. Firestore emulator integration لحالة timeout الفعلية بعد server commit.
2. WorkManager/TestWorkerBuilder integration على Android.
3. profile conflict resolution بإصدار monotonic صريح؛ marker يمنع replay غير الموثق لكنه لا يحل كل تعارض جهازين.
4. task continuity compare-and-set على backend؛ merge المحلي version-aware لكنه ليس بديلاً عن شرط كتابة remote.
5. اختبارات AgentLoop E2E الكاملة لكل cancellation boundary وmalformed partial call وapproval/privacy independence.
6. مصفوفة trace كاملة عبر persistence، projection، UI، وprocess death في CI.

الحالة الدقيقة: **تحسين P0 جزئي مع عقد واختبارات JVM جديدة، وليس إغلاقاً نهائياً للقسم كله**.
