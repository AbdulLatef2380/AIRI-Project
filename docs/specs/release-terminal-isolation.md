# مواصفة: Release Terminal Isolation

## الهدف
توفير مسار آمن لتشغيل `terminal_execute` في نسخة Release دون تنفيذ الأوامر داخل UID التطبيق. لا تُعد الطرفية جاهزة للإصدار إلا إذا أثبتت خدمة Android معزولة عزلها، وحدود الزمن والذاكرة وCPU، ورفض الوصول إلى ملفات التطبيق والشبكة غير المصرح بها.

## النطاق
يشمل التنفيذ:

- خدمة داخلية تعمل بـ `android:isolatedProcess="true"` ولا تكون قابلة للتصدير.
- بروتوكول Binder يمرر correlation IDs كاملة: `sessionId`, `messageId`, `executionId`, `traceId`.
- allowlist للأوامر وargv بدون shell parsing أو shell metacharacters.
- workspace مؤقت خاص بالخدمة، ولا يُمرر مسار `filesDir` أو أي مسار خاص بالتطبيق إلى العملية المعزولة.
- حدود زمنية، حجم خرج، حجم ملفات، وwatchdog للذاكرة وCPU.
- منع الشبكة افتراضيًا، وعدم قبول طلب network إلا عبر policy صريحة تفشل مغلقًا.
- موافقة صريحة للأوامر التغييرية/الخطرة قبل التنفيذ.
- نتائج قابلة للتدقيق تربط النتيجة بكل المعرفات.
- اختبارات JVM واختبارات instrumentation للتحقق من النجاح والفشل وtimeout ومحاولات الهروب وmanifest isolation.

## القيود الأمنية

- ممنوع تغيير Boolean فقط أو إضافة استثناء Release.
- ممنوع تمرير مسارات التطبيق أو secrets أو environment من التطبيق إلى العملية المعزولة.
- ممنوع استخدام `/system/bin/sh -c`.
- ممنوع اعتبار نجاح Binder أو إنشاء العملية دليلًا على نجاح الأمر.
- كل رفض يفشل مغلقًا ويحتوي سببًا قابلًا للعرض والتدقيق.

## معيار الجاهزية
يُسمح لـ `SandboxIsolationPolicy` بإرجاع Release-ready فقط عندما:

1. تكون الخدمة موجودة في manifest بـ `isolatedProcess=true`, `exported=false`.
2. ينجح probe فعلي من داخل الخدمة ويؤكد اختلاف UID عن التطبيق.
3. لا تستطيع الخدمة قراءة marker خاص داخل `filesDir`.
4. ينجح timeout ويُقتل process tree.
5. تُفرض حدود output/file/memory/CPU.
6. تُرفض الشبكة عندما تكون غير مسموحة.
7. تمر correlation IDs كاملة ولا يمكن تبديلها.
8. تنجح اختبارات instrumentation على الجهاز في build Release-like.

## أوامر التحقق

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

## الاختبارات المطلوبة

- أمر مسموح ينجح ويعيد exit code وoutput.
- binary أو argv غير مسموح يُرفض قبل التشغيل.
- traversal وabsolute path وshell injection تُرفض.
- timeout يقتل العملية ولا يترك descendant.
- output وfile size يتوقفان عند الحدود.
- محاولة قراءة marker من `filesDir` تفشل.
- network probe يُرفض افتراضيًا.
- correlation ناقص أو غير مطابق يُرفض.
- dangerous mutation تحتاج approval ولا تنفذ قبلها.
- manifest يثبت isolated UID وnon-exported service.

## قرارات مفتوحة
لا يوجد تفعيل Release تلقائي قبل اكتمال اختبارات الجهاز؛ إذا تعذر إثبات capability في بيئة الجهاز، تبقى النتيجة `UnsupportedOnDevice` والحظر قائمًا.
