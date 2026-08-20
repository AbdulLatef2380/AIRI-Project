# أدلة تحقق Gate 1

تم تنفيذ أوامر هذا المستند على فرع `cp-foundation` بعد إنشاء فاحص تبعيات المنصة ووثائق التحول. لا يحتوي Gate 1 على وحدة KMP أو تطبيق Desktop/Web، ولذلك لا يرفع حالة تلك المنصات فوق `PLANNED` ولا يرفع AIRI Core فوق `ARCHITECTED`.

| التحقق | الأمر | النتيجة | الملاحظة |
| --- | --- | --- | --- |
| صحة diff | `git diff --check` | `PASS` | لا أخطاء whitespace. |
| فحص تبعيات المنصة | `python3 scripts/airi_platform_dependency_scan.py` | `PASS` | فحص 822 ملفاً؛ 132 مرشحاً أولياً للنواة، 412 Android-only، 290 native-runtime. |
| صحة الحدود متعددة المنصات | `python3 scripts/airi_cross_platform_health.py` | `PASS` | لا توجد `commonMain` بعد؛ يسجل الفاحص الحالة `Core extraction pending` كمعلومة لا كنجاح زائف. |
| ضمانات AIRI الحالية | `python3 tools/verify_core_changes.py` | `PASS` | 41/41 فحصاً ناجحاً. |
| فاحص الأمان | `python3 tools/security_scan.py` | `PASS` | 8/8 ضوابط ناجحة، ولا نتائج secrets. |
| صحة AIRI Core الحالية | `python3 scripts/airi_core_health.py` | `PASS` مع عناصر مراجعة | لا تعارضات دمج أو نقص موارد؛ 4 ملفات Kotlin كبيرة و4 callbacks فارغة تُراجع لاحقاً خارج نطاق Gate 1. |
| جرد التبعيات | `python3 scripts/supply_chain_inventory.py` | `PASS` | 42 تبعية مباشرة موثقة. |
| بناء واختبار Android | `./gradlew … :app:assembleDebug :app:testDebugUnitTest` | `PASS` | نجح في 3m31s مع 53 مهمة منفذة. |

## بيئة بناء Android

```text
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ANDROID_HOME=/home/ubuntu/android-sdk
Gradle: 8.11.1
Android plugin/toolchain: حسب ملفات المشروع
```

أمر البناء الكامل المستخدم:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  :app:assembleDebug :app:testDebugUnitTest
```

شهد البناء أن debug APK يحتوي `lib/arm64-v8a/libairi_native.so`. ظهرت رسالة strip متوقعة خاصة بـ`libjnidispatch.so` ولم تمنع النجاح. كما ظهرت تحذيرات Kotlin قائمة عن parameters غير مستخدمة وواجهات API، لكنها لم تكن أخطاء بناء ولم تُغير ضمن Gate 1، لأن نطاقه تحليل معماري لا إعادة تنسيق شاملة.

## تفسير النتيجة

> نجاح Gate 1 يعني أن حدود التحول وقرار التقنية وفحوص منع التسرب موثقة ومتحققة، وأن Android لم ينحدر نتيجة هذا العمل. لا يعني أن وحدة AIRI Core أو Windows أو Linux أو Web قد بُنيت أو شُغّلت بعد.

الخطوة المسموح بها التالية هي Gate 2A فقط: إنشاء `core-domain` واستخراج السياسات والنماذج الخالصة المحددة في [خطة الترحيل](MIGRATION_PLAN.md)، مع تشغيل `commonTest` وAndroid integration checks. يبقى `architecture-refactor` غير معدل.
