# تقرير تنفيذ P0-B / P0-C

## النطاق المنفذ

- تمت مراجعة التنفيذ الموجود أولاً. تبين أن `DurableTask` وRoom store وexternal polling وapproval continuation موجودة جزئياً، لذلك لم تُنشأ مسارات موازية.
- أضيف `OperationLedger` محايد عن الموصلات، بحالات `PREPARED/CLAIMED/SUCCEEDED/FAILED/UNKNOWN`، ومفتاح idempotency ثابت مشتق من `task/run/step/action`، وتجزئة payload مستقرة بترتيب المفاتيح، وCAS على رقم الإصدار. النتيجة `UNKNOWN` لا تقبل انتقالاً لاحقاً تلقائياً.
- أضيف `FakeIdempotentConnector` لاختبارات JVM: يعيد نتيجة الطلب الأول عند تكرار المفتاح، ويحاكي قبول المزود ثم فقد النتيجة قبل تسجيلها، من دون أي provider حي أو secret.
- أضيف `ExecutionBudget` و`ExecutionUsage` كعقد مستقل يفصل decision cycles وtool operations وretries وtokens وcost وtask deadline وrun deadline، مع hard decision ceiling. أضيف `ProgressWatchdog` تعاوني لا يعتبر الانتظار الخارجي stall.
- رُبطت الميزانية والاستخدام بالكيان المستدام `DurableTask` مع defaults متوافقة للخلف، بحيث لا تتغير استدعاءات constructors الحالية.
- شُددت قرارات الموافقة إلى انتقال أحادي الاتجاه من `PENDING` فقط، مع حالات `DECLINED` و`REVOKED` وإجراء `revokeApproval`. القرارات المتأخرة وdouble-tap لا تغير الموافقة ولا تعيد continuation.
- أضيفت اختبارات JVM حتمية للمفتاح/التجزئة/CAS/idempotency وfault injection والميزانيات والـwatchdog وrevoke.

## التنفيذ الموجود الذي تم تثبيته بدل إعادة تنفيذه

- `DurableTaskManager` كان يملك بالفعل Room transactional persistence وstate-version bump/CAS على التخزين، وexternal polling يستخدم due-time CAS وunique WorkManager work.
- `ApprovalContinuationRuntime` يclaim continuation قبل استدعاء connector ويمنع retry التلقائي بعد claim.
- حالات `WAITING_EXTERNAL` و`WAITING_TIMER` و`UNKNOWN` ومسار recovery موجودة في `DurableTask` و`ExternalOperationRecoveryCoordinator`؛ الاختبارات الحالية تثبت أن نتيجة العملية الخارجية لا تعيد تشغيل run القديم.

## الاختبارات

أُضيف الاختبار:

`app/src/test/java/com/airi/assistant/agent/durable/P0BCOperationBudgetContractTest.kt`

تمت محاولة تشغيل:

```text
./gradlew :app:testDebugUnitTest --tests com.airi.assistant.agent.durable.P0BCOperationBudgetContractTest --no-daemon
```

## Blocker بيئي

فشل Gradle قبل compilation لأن Android SDK غير متاح في البيئة:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
or by setting the sdk.dir path in local.properties
```

لا توجد credentials أو live provider مستخدمة. لم يتم إنشاء commit أو push. تمت إزالة آثار Gradle المؤقتة من working tree.

## حدود متبقية

هذا التغيير يضيف عقد المجال والاختبارات ويثبت الفجوات الحرجة دون ادعاء نجاح Android instrumentation أو process-death فعلي. لا يزال دمج عدادات `ExecutionUsage` مع كل نقطة استدلال/أداة في `AgentLoop` يحتاج تشغيل Android/build والتحقق من مسارات production، كما أن CAS الموزع النهائي يعتمد على Room الموجود في `DurableTaskManager`؛ `OperationLedger` الجديد هو مرجع JVM/domain قابل للتوصيل وليس بديلاً عن transaction Room.
