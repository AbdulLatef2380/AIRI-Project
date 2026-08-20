# أدلة تحقق Gate 2B: `MemoryTextNormalizer`

ينقل Gate 2B خوارزمية تطبيع نصوص الذاكرة إلى `core-domain/commonMain` بعد نجاح Gate 2A. أزيل الاعتماد الوحيد على JVM، وهو `java.util.Locale`، واستبدل بـ`String.lowercase()` المتاح في Kotlin المشترك. أصبح `MemoryEvolutionEngine` في Android مستهلكاً لـ`com.airi.core.memory.text.MemoryTextNormalizer`، وحذفت النسخة Android-only واختبارها حتى لا توجد خوارزميتان متباعدتان.

| عنصر | الحالة | الدليل |
| --- | --- | --- |
| normalizer مشترك | `IMPLEMENTED` | لا imports Android أو AndroidX أو Java/JVM أو I/O أو persistence. |
| اختبارات التطبيع | `IMPLEMENTED` | حالات punctuation، Arabic marks، أشكال الألف، وإزالة "ال" التعريفية. |
| JVM Desktop target للنواة | `BUILDS` | `:core-domain:desktopTest` نجحت مع الاختبارات المضافة. |
| Android target للنواة | `BUILDS` | `:core-domain:compileDebugKotlinAndroid` نجحت. |
| تكامل Android | `BUILDS` | `MemoryEvolutionEngine` يستورد implementation المشترك و`:app:testDebugUnitTest :app:assembleDebug` نجحت. |
| AIRI Desktop product | `PLANNED` | لا يوجد تطبيق أو artifact أو runtime verification لسطح المكتب. |

## أوامر ونتائج التحقق

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

**النتيجة:** `BUILD SUCCESSFUL`، 73 مهمة (67 منفذة و6 up-to-date). نجح تحقق الـAPK native المعتاد وظهر تحذير strip غير حاجب لـ`libjnidispatch.so`.

## حدود الميلستون

لم ينقل هذا Gate `MemoryEvolutionEngine` نفسه، لأنه Android-specific: يملك `Context` و`SharedPreferences` و`Log` ووقت النظام. بقيت تلك المسؤوليات في تطبيق Android، فيما انتقل فقط التطبيع الخالص. لا يغيّر Gate 2B حالة Windows أو Linux أو Web، ولا يعالج بعد Room/RAG storage أو model routing أو local runtime.

## بوابة الخروج

يصبح normalizer مرشحاً للاستعمال في أي adapter جديد لأنه يترجم من `commonMain` ويملك اختبارات مشتركة. أما نقل المرشح التالي فيستلزم تحليلاً مستقلاً لتبعياته المتعدية؛ لا تنتقل classes كاملة لمجرد أن أحد helpers صار مشتركاً.
