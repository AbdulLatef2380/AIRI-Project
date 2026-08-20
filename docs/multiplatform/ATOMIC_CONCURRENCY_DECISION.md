# قرار التزامن الذري لحاجز الإلغاء

يبقى `ExecutionGenerationGate` في Android مؤقتاً. منطق الحاجز صالح للنواة، لكنه يستخدم `java.util.concurrent.atomic.AtomicLong` و`AtomicBoolean`، وهما غير متاحين في `commonMain`. لا يُستبدل بهما متغير عادي أو lock خاص بمنصة، لأن قبول callback بعد إلغاء أو بعد generation أحدث خطأ سلامة تنفيذ.

راجعت AIRI مصدر AtomicFU الرسمي. يصف المشروع AtomicFU بأنه مكتبة متعددة المنصات للعمليات الذرية، ويوصي باستخدام compiler plugin بدلاً من bytecode transformation [1]. لكن الفرع الرسمي الحالي يذكر build Kotlin حديثاً، في حين يعتمد AIRI Kotlin 1.9.22 ويظهر تحذير توافق قائم مع AGP 8.10.1. لذلك لا تضاف نسخة AtomicFU أو compiler plugin بالتخمين.

| القرار | الحالة | السبب |
| --- | --- | --- |
| نقل `ExecutionGenerationGate` الآن | `BLOCKED` | يحتاج primitive ذري متعدد المنصات متوافقاً ومختبراً. |
| استخدام متغيرات عادية في `commonMain` | مرفوض | يكسر دلالة التزامن ويفتح احتمال callbacks متأخرة. |
| إضافة AtomicFU | `PLANNED` كـspike منفصل | يلزم تثبيت نسخة متوافقة مع Kotlin 1.9.22، ثم build JVM/Android وstress/cancellation tests. |
| إبقاء Android gate الحالي | `IMPLEMENTED` | اختبارات الحاجز الحالية تبقى المرجع حتى ينجح spike. |

معيار إغلاق الحظر هو: اختيار dependency/compiler plugin متوافقين بمرجع رسمي، بناء `core-domain` على JVM وAndroid، اختبارات concurrent تؤكد generation/cancel semantics، نجاح Android integration، وتحديث SBOM/Supply-chain inventory. لا يغيّر هذا القرار حالة Windows أو Linux أو Web.

## المراجع

[1]: https://github.com/Kotlin/kotlinx-atomicfu "Kotlin/kotlinx-atomicfu"
