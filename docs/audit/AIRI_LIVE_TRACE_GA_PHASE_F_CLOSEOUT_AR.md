# تقرير إغلاق المرحلة السادسة — سجل التنفيذ الحي في AIRI

**تاريخ المراجعة:** 19 أكتوبر 2026  
**الفرع:** `main`  
**رأس الشجرة عند المراجعة:** `41f0341c` (`docs: add live trace GA closure plan`)  
**نطاق التقرير:** المرحلة F من خطة `AIRI_LIVE_TRACE_GA_CLOSURE_PLAN.md`

## القرار

**القرار الحالي: `NO-GA` لسجل التنفيذ الحي.**

هذا ليس حكماً بأن تغييرات المصدر فاشلة. بل يعني أن شرط المرحلة F يتطلب دليلاً منشوراً من CI على الالتزام الحالي، وتشغيل instrumentation، ومراجعة artifacts، وإثبات ARM64/process-death/offline أو قبول مخاطر مملوكاً وموقعاً. هذه الأدلة غير متاحة في جلسة المراجعة الحالية، ولذلك لا يجوز رفع الحالة إلى `GA READY`.

الحالة المناسبة مؤقتاً هي **مرشح داخلي / Conditional Release Candidate** بعد نجاح البوابات الساكنة، مع منع الإعلان العام حتى إغلاق البنود الخارجية أدناه.

## ما أُغلق في المصدر

| المجال | النتيجة | الدليل |
|---|---|---|
| عقود سجل التنفيذ | مغلق ساكنياً | `WAITING` و`CANCELLED` موجودتان في `TaskStatus`، و`memoryOperations` داخل الملخص، و`actionId` مطلوب لأحداث الأداة في `ExecutionTraceBuffer`. |
| دمج المجموعات | مغلق في المصدر | `AgentLifecycleTrackerPanel` يعرض `projection.groups`، ويستخدم رؤوس مجموعات قابلة للطي، ولا يعرض `taskTree` بالتوازي. |
| حالة التوسيع | مغلق في المصدر | حالة المجموعات والتفاصيل محفوظة عبر `rememberSaveable(executionId)`. |
| الوصولية | مغلق ساكنياً | أوصاف مترجمة للوحة والمجموعات والأحداث والحالات وإجراءات التوسيع/الطي. |
| التوطين | مغلق | تعادل مفاتيح `en/ar/es/zh` يساوي صفر مفاتيح مفقودة. |
| الضغط | مضاف للمصدر | اختبار 5,000 حدث خارج الترتيب في Projector، واختبار 5,000 إضافة متزامنة مع نافذة احتفاظ من 1,000 حدث في Buffer. |

## بوابات المراجعة التي نجحت

```text
python3 tools/verify_core_changes.py
summary: 96/96 checks passed
```

```text
python3 scripts/airi_localization_health.py --strict
[INFO] likely_untranslated_values=0
```

وتحققت مراجعة parity لكل اللغات الأربع:

```text
values-ar: Missing keys: 0
values-es: Missing keys: 0
values-zh: Missing keys: 0
```

كما نجحت:

- `python3 scripts/airi_release_health.py`
- `python3 tools/final_runtime_integration_audit.py` — `status: PASS`
- `python3 scripts/connector_lifecycle_static_scan.py` — `summary: 13/13 checks passed`
- فحص XML لجميع ملفات الموارد — `xml-ok`
- `git diff --check`

## ما يثبته CI في المصدر، وما لم يثبت بعد

ملف `.github/workflows/android_build.yml` يحتوي على بوابات مناسبة للإصدار:

- إعداد Android SDK وNDK وCMake.
- `assembleDebug` واختبارات JVM و`lintDebug`.
- `compileReleaseKotlin`.
- `assembleRelease` و`bundleRelease`.
- تشغيل `connectedDebugAndroidTest` على Emulator API 29/x86_64.
- التحقق من وجود `libairi_native.so`.
- إنشاء `mapping.txt` و`SHA256SUMS` وartifacts الإصدار.
- قصر التوقيع على `main` عند توفر أسرار التوقيع الأربعة.

لكن هذه المراجعة لم تحصل على تشغيل CI منشور يثبت الالتزام الحالي `41f0341c` بعد تغييرات سجل التنفيذ. كما أن بيئة Sandbox لا تحتوي Android SDK؛ لذلك فشل تشغيل Gradle محلياً أثناء تهيئة المشروع قبل بدء الاختبار:

```text
SDK location not found
```

## البنود المفتوحة قبل GA

1. تشغيل workflow على الالتزام الذي يحتوي تغييرات سجل التنفيذ، وحفظ رابط التشغيل ونتيجة كل بوابة.
2. تنزيل ومراجعة artifacts: Debug APK، Release APK/AAB، `mapping.txt`، `SHA256SUMS` وتقارير instrumentation.
3. تشغيل Compose/instrumentation tests الخاصة باللوحة على Emulator، بما يشمل التوسيع والطي وعدم تكرار الصفوف.
4. إثبات RTL العربية وLTR الإنجليزية، font scale، وsemantics/TalkBack على Emulator أو جهاز فعلي.
5. إضافة أو استخراج دليل ARM64 فعلي؛ اختبار x86_64 في CI لا يغلق توافق ARM64.
6. تنفيذ سيناريو process death واستعادة السجل أو توثيق `accepted-risk` من مالك الإصدار.
7. تنفيذ اختبار offline/captive-portal مع التحقق من عدم إظهار حالة cloud online كاذبة.
8. إجراء مراجعة بشرية للقطات الواجهة، خصوصاً ارتفاع اللوحة وعدم دفع composer خارج الشاشة.

## قاعدة الانتقال

لا يتغير القرار إلى `GA READY` إلا بعد أن تكون البنود السابقة مرفقة بأدلة قابلة لإعادة التشغيل، وأن تكون كل نتائج CI خضراء على الالتزام نفسه. عند غياب دليل ARM64 أو process-death أو الجهاز الفعلي، يبقى القرار `NO-GA` أو `Conditional Release Candidate` داخلياً فقط.

## أوامر إعادة التحقق

```bash
python3 tools/verify_core_changes.py
python3 scripts/airi_localization_health.py --strict
python3 scripts/airi_release_health.py
python3 tools/final_runtime_integration_audit.py
python3 scripts/connector_lifecycle_static_scan.py
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease :app:bundleRelease
./gradlew :app:connectedDebugAndroidTest
```

الأوامر الأخيرة تتطلب Android SDK وEmulator أو تنفيذها عبر GitHub Actions؛ لا تُفسر نتائج الفحوصات الساكنة بديلاً عن دليل الجهاز أو CI.

## ملحق المرحلة الأخيرة — الفجوة الثامنة

أضيفت تغطية ضغط Compose في `AgentLifecycleTrackerPanelTest`:

- خمسة آلاف حدث موزعة على خمس مجموعات، مع عرض LazyColumn محدود.
- تحديث أثناء streaming مع الحفاظ على المجموعة المفتوحة.
- إبقاء حالة `FAILED` ضمن أحداث الضغط.

كما عُدّلت `ExecutionTraceBuffer` بحيث تبحث عن أقدم حدث غير نهائي عند تجاوز السعة قبل إخلاء حدث نهائي. يغطي `ExecutionTraceBufferTest` ذلك مباشرةً، إضافة إلى اختبار 5,000 إضافة متزامنة ونافذة احتفاظ من 1,000 حدث.

تبقى نتيجة Android instrumentation وقياس recomposition الفعلي غير منفذة في Sandbox بسبب غياب Android SDK. لذلك لا يتغير قرار `NO-GA` حتى يتم تشغيل CI/Emulator وتوثيق artifacts وقياسات الجهاز.
