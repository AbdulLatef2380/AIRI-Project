# Sandbox Naming and Lifecycle Contract

## التسمية الرسمية

الاسم الصحيح في AIRI هو **sandbox**. هذا الاسم يطابق الحزم والأنواع الحالية:

- `com.airi.assistant.agent.sandbox.SandboxExecutor`
- `SandboxManager`
- `SandboxSession`
- `SandboxIsolationPolicy`
- `AgentSandbox` في حدود security/coding

كلمة **sendbox** ليست طبقة تنفيذ موجودة في المستودع. تُعامل كخطأ إملائي متوافق معه في البحث أو الملاحظات فقط، ولا يجوز إنشاء package أو class أو route باسم `sendbox`.

إذا احتاجت واجهة أو migration قبول المصطلح القديم، يكون العقد صريحاً: `sendbox` alias إدخالي يُطبع فوراً إلى `sandbox`، ولا يظهر في identifiers أو telemetry أو أسماء الملفات الجديدة.

## حدود العقد

`SandboxExecutor` يوفر حدود path/command ونتيجة تنفيذ مقيدة، بينما `SandboxIsolationPolicy` يحدد إن كان OS-level isolation متاحاً. وجود مجلد `filesDir/sandbox` أو `SandboxManager` لا يساوي OS sandbox. أي تقرير يعلن عزل OS يجب أن يستند إلى runtime/device evidence منفصل.

`AgentSandbox` و`sandbox` workspace ليسا بديلاً تلقائياً عن بعضهما؛ لكل منهما owner وpermission boundary. لا ينبغي توحيدهما بمجرد تشابه الاسم.

## Lifecycle contract للأحداث

- `ExecutionStatusBus.status` و`trace` هما `StateFlow` replaying لآخر snapshot، ومصدر الحقيقة للـUI بعد إعادة الاشتراك.
- `executionId` هو مفتاح العزل؛ لا يجوز أن تظهر أحداث execution قديم في execution جديد بعد process recreation.
- الأحداث terminal (`COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`) idempotent: لا تكرر ولا تسمح بأحداث late بعد terminal.
- rotation تعيد الاشتراك فقط؛ لا تعيد emit للأحداث السابقة ولا تفقد snapshot الحالي.
- process restart يبدأ in-memory bus جديداً؛ استعادة مهمة durable لا تعيد بث trace القديم إلا عبر adapter صريح يملك provenance جديداً.
- الاختبار JVM يثبت replay، ordering، duplicate rejection، وexecution isolation. إثبات Android rotation/process restart الحقيقي يحتاج instrumentation/device evidence.

الاختبارات المرجعية: `ExecutionStatusBusTest` و`ExecutionStatusBusLifecycleContractTest` إن أضيف، مع إبقاء `sandbox` و`sendbox` منفصلين لغوياً ووظيفياً.
