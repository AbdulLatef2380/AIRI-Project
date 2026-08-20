# أدلة تحقق Gate 2A: `core-domain`

## نطاق الميلستون

ينشئ هذا الميلستون وحدة Kotlin Multiplatform باسم `core-domain`، ويستخرج إليها **سياسة قبول الذاكرة فقط** مع اختباراتها. استبدل `MemoryManager` في Android النسخة المحلية بالـpolicy المشتركة ثم حذفت النسخة القديمة واختبارها، لذلك أصبح منطق القبول له مصدر واحد. لم تُنقل Room أو DAOs أو embedding أو RAG persistence أو UI أو JNI أو provider clients.

> هذا دليل بناء لوظيفة مشتركة محدودة، وليس دليلاً على تطبيق AIRI Desktop أو Windows أو Linux أو Web. تبقى هذه المنتجات `PLANNED` في المصفوفة.

| عنصر | الحالة | الدليل |
| --- | --- | --- |
| `core-domain/commonMain` | `IMPLEMENTED` | `com.airi.core.memory.MemoryAdmissionPolicy` لا يعتمد Android أو JVM APIs. |
| `core-domain/commonTest` | `IMPLEMENTED` | أربع حالات تغطي content حساساً، greeting، طلب ذاكرة عربي، وفئات facts المسموح بها. |
| target JVM باسم `desktop` | `BUILDS` | `:core-domain:desktopTest` نجحت. |
| target Android للوحدة | `BUILDS` | `:core-domain:compileDebugKotlinAndroid` نجحت. |
| استهلاك Android | `BUILDS` | `MemoryManager` يستورد policy من `core-domain` و`:app:testDebugUnitTest :app:assembleDebug` نجحت. |
| تطبيق AIRI Desktop | `PLANNED` | لا يوجد `app-desktop` أو artifact أو اختبار تشغيل. |
| Windows/Linux product support | `PLANNED` | لا يوجد runtime/package/acceptance evidence لكل OS. |

## أوامر التنفيذ والنتائج

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid
```

**النتيجة:** `BUILD SUCCESSFUL`، 10 مهام منفذة.

```bash
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  :app:testDebugUnitTest :app:assembleDebug
```

**النتيجة:** `BUILD SUCCESSFUL`، 73 مهمة (67 منفذة و6 up-to-date). احتوى debug APK التحقق native المعتاد: `lib/arm64-v8a/libairi_native.so`.

```bash
python3 scripts/airi_platform_dependency_scan.py
python3 scripts/airi_cross_platform_health.py
```

**النتيجة:** `PASS`. يكتشف الحارس `core-domain/src/commonMain` ولا يسجل Android أو AndroidX أو Room أو WorkManager أو JNI أو JVM APIs في المصدر المشترك.

## تحذير توافق منظور

أظهر Gradle تحذيراً: Kotlin Multiplatform 1.9.22 يذكر أن AGP 8.10.1 أعلى من آخر AGP مختبر له (8.2). لم يفشل البناء الحالي، لكن لا يُخفى التحذير ولا يُعامل كدعم رسمي مضمون. سُجل ذلك في `CP-21` ضمن [سجل المخاطر](RISK_REGISTER.md). يبقى تحديث Kotlin/Compose أو AGP milestone منفصلاً بعد تثبيت الاختبارات في CI؛ لا يختلط مع هذا الاستخراج المحدود.

## اختبارات السلوك المنقولة

| الحالة | السلوك المثبت |
| --- | --- |
| API key أو بيانات حساسة | لا تصبح مؤهلة للـembedding أو استخراج facts. |
| تحية قصيرة | لا تدخل semantic memory. |
| طلب عربي صريح غير حساس | يصبح مؤهلاً للـembedding واستخراج facts. |
| fact durable | يسمح فقط بفئات preference/dislike/language/project وبقيمة غير حساسة. |

## بوابة الخروج

نجح Gate 2A عندما توفرت وحدة مشتركة تبني من دون تطبيق Android، وتملك اختبارات مشتركة، ويستهلكها Android من دون نسخة منطق موازية. الخطوة التالية المسموح بها هي Gate 2B: نقل **العقود والنماذج الخالصة التالية فقط**، بعد فحص dependencies transitive وتوسيع الحارس ليدعم وحدات `core-*` الجديدة. لا يبدأ Desktop product أو Web implementation بهذا الدليل.
