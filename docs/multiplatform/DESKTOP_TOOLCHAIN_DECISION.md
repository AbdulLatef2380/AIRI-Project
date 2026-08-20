# قرار toolchain لسطح المكتب

## النتيجة

لا يبدأ إنشاء `app-desktop` بواجهة Compose Multiplatform على toolchain AIRI الحالي. يستخدم المشروع Kotlin `1.9.22` وAGP `8.10.1`، وقد أظهر KMP بالفعل تحذيراً أن AGP أعلى من آخر إصدار مختبر لهذه النسخة. وتوضح وثائق Compose Multiplatform الرسمية الحالية أن أحدث Compose يتطلب Compose Compiler Gradle plugin بالإصدار نفسه لـKotlin Multiplatform، وأن الإصدارات الحديثة ابتداءً من Compose 1.8.0 انتقلت إلى K2 وتتطلب Kotlin 2.1.0 على الأقل [1].

إن اختيار إصدار Compose قديم متوافق مع Kotlin 1.9.22 من دون توثيق إصدار دقيق واختبار Android/Windows/Linux لا يحقق قاعدة AIRI ضد الدعم الوهمي. كما أن تحديث Kotlin أو Compose أو AGP يغير toolchain تطبيق Android المرجعي وقد يغير Compose compiler وKSP وRoom وسلوك JNI/CI؛ لذلك يعد **milestone ترقية مستقلاً** وليس جزءاً من Gate 2 extraction.

| قرار | الحالة | السبب |
| --- | --- | --- |
| إنشاء تطبيق Compose Desktop حديث الآن | `BLOCKED` | لا توافق مثبت بين toolchain الحالي وCompose Desktop حديث. |
| إعلان Windows/Linux مدعومين | مرفوض | لا يوجد app، artifact، أو اختبار runtime لكل OS. |
| نقل core policies الحالية | `BUILDS` كنواة محدودة | نجح على JVM عام وAndroid، لكنه ليس تطبيق Desktop. |
| ترقية toolchain | `PLANNED` كـspike منفصل | تحتاج compatibility matrix وbranch/rollback وCI كامل. |
| اختبار Desktop بعد upgrade | `PLANNED` | يبدأ بتطبيق محدود ثم build/package/runtime evidence لكل OS. |

## مسار ترقية آمن مقترح

1. ينشأ milestone منفصل لتحديد زوج Kotlin/Compose/KSP/AGP مدعوم رسمياً، مع عدم تحديث Android UI أو business logic في الدفعة نفسها.
2. تشغل بوابات Android الحالية: debug/release، unit tests، lint، instrumentation، native verification، Deep Audit، Architecture Audit.
3. ينشأ `app-desktop` minimal فقط بعد أن يبني toolchain المحدث بنجاح، ويستهلك `core-domain` بلا نسخة منطق أعمال.
4. لا ترتفع Windows أو Linux عن `PLANNED` إلا بعد artifact لكل OS وتشغيل قبول حقيقي يشمل chat وmemory وattachments وcancellation وفق المصفوفة.

> هذا حظر هندسي موثق، لا توقف عن برنامج التحول. يمنع تغييراً مدمراً غير معتمد في Android baseline إلى أن يثبت toolchain في milestone مستقل قابل للرجوع.

## المراجع

[1]: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html "Compatibility and versions | Kotlin Multiplatform"
[2]: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-create-first-app.html "Create your Compose Multiplatform app"
