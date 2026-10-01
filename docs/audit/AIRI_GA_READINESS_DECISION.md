# AIRI — قرار جاهزية GA

**تاريخ المراجعة:** 2026-10-02 00:02 UTC  
**الرؤوس المفحوصة قبل هذه الدفعة:** `main=fbe94ceb` و`cp-foundation=31980a26`  
**نوع القرار:** مراجعة نهائية ساكنة + بوابات CI + فحوص صحة الإصدار

## القرار

# NO-GA — غير جاهز للإصدار العام بعد

القرار ليس بسبب فشل بوابات المصدر أو CI الحالية. السبب هو أن بعض شروط الإصدار لا يمكن إثباتها من Sandbox/CI الساكن وحده، وما زالت أدلة runtime الخارجية غير مكتملة. لا ينبغي نشر APK/AAB كإصدار GA قبل إغلاق البنود التالية أو قبولها رسمياً من مالك الإصدار.

## ما تم إثباته

- `tools/verify_core_changes.py`: **96/96**.
- Android CI على `main` و`cp-foundation` في آخر دفعة منشورة: **نجاح**.
- Deep Audit وArchitecture Audit في آخر دفعة منشورة: **نجاح**.
- Release health: **نجاح**.
- Localization strict health: **نجاح؛ likely_untranslated_values=0**.
- Attachment flow health: **نجاح**.
- Attachment security scan: **SOURCE_VERIFIED بالكامل**.
- Cross-platform health: **PASS؛ 0 errors**.
- Toolchain health: **نجاح**.
- Telemetry/privacy scan: **12/12**.
- لا توجد تغييرات محلية غير مقصودة بعد تنظيف آثار Gradle.

## إصلاحات هذه المراجعة

1. استبدال `readText()` غير المحدود في `AttachmentContentExtractor` بقراءة chunked محدودة بـ`maxChars` قبل تحليل OOXML.
2. تحديث `airi_attachment_security_scan.py` ليتحقق من أسماء حدود القراءة الفعلية (`maxRead`) بدلاً من oracle قديم (`readLimit`).
3. تحديث `airi_attachment_flow_health.py` ليتطابق مع توقيع `sendMessageInternal` ومسار `LongTextAttachmentPolicy` الحالي، ومنع false negative في المراجعة.

## القيود التي تمنع GA

### Blockers خارجية أو غير مثبتة runtime

- اختبار فعلي على جهاز ARM64 متنوع مع native model/vision وقياس RAM/OOM/cancellation.
- process death وrestore/migration وsecure-store recovery على Android حقيقي.
- اختبار offline/provider failure وDNS/redirect/OAuth مع endpoints حقيقية وبدون أسرار في السجل.
- اختبار font scale وRTL وaccessibility وcompact layouts بصرياً على أجهزة/إصدارات Android مدعومة.
- إثبات Play Console، certificate provenance، وrelease artifact من signing production الحقيقي.
- قرار مالك الإصدار بشأن المخاطر المتبقية من التدقيق الساكن السابق؛ التقرير الأساسي سجل **104 finding** (42 P1، 57 P2، 5 P3) قبل دفعات الإصلاح، ولا يجوز اعتبارها مغلقة كلها بمجرد نجاح oracle.

### قيد التحقق المحلي

اختبارات Gradle المحلية لم تبدأ في Sandbox لأن Android SDK غير متاح:

> `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable...`

لذلك تُعد نتائج GitHub Android CI المرجع المعتمد للـcompile/unit/instrumentation، وليس نتيجة Sandbox المحلية.

## بوابة التحول إلى GA

لا يتغير القرار إلى **GA READY** إلا بعد تحقق جميع الشروط التالية:

1. نجاح Android CI على commit الإصدار النهائي، بما فيه APK/AAB/mapping/hash/signing evidence.
2. تنفيذ runtime/device matrix موثق، أو قبول صريح وموقع للبنود `EXTERNAL_PENDING`.
3. إعادة تدقيق findings بعد هذه الدفعة، مع ربط كل P1/P2 بحالة `closed` أو `accepted-risk` أو `external-pending` ودليل.
4. مراجعة artifact النهائي يدوياً قبل النشر، مع عدم وجود secrets أو debug-only surfaces في release.

## الخلاصة التنفيذية

**جودة المصدر وبوابات CI تحسنت بشكل كبير، لكن الجاهزية التجارية/التشغيلية الكاملة لم تُثبت بعد. القرار المهني الصحيح الآن هو NO-GA مؤقتاً، وليس إعلان GA مبنياً على أدلة ساكنة فقط.**
