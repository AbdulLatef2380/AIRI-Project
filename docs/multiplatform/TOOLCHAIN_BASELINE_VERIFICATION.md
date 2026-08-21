# تحقق Toolchain Baseline

**فرع العمل:** `cp-foundation` عند `eb4714d2` قبل أي تغيير للإصدارات.
**نقطة rollback المنشورة:** `cp-toolchain-baseline` عند `eb4714d2`.
**الخط المحمي غير المعدل:** `architecture-refactor` عند `1027dee2`.

## البيئة

| عنصر | القيمة |
| --- | --- |
| Gradle | 8.11.1 |
| Gradle embedded Kotlin | 2.0.20 |
| JDK | OpenJDK 17.0.19 |
| نظام الاختبار | Linux amd64 |
| Android SDK | `/home/ubuntu/android-sdk` |
| Kotlin/KMP | 1.9.22 |
| AGP | 8.10.1 |
| KSP | 1.9.22-1.0.17 |

## النتائج المحلية

| البوابة | الحالة | الدليل |
| --- | --- | --- |
| Gradle metadata وapp tasks | `PASS` | `reports/multiplatform/toolchain-baseline/gradle-version.txt` و`app-tasks.txt`. |
| Shared core JVM/Android | `PASS` | `:core-domain:desktopTest :core-domain:compileDebugKotlinAndroid`. |
| Android unit tests وlint | `PASS` | ضمن `debug-tests.log`. |
| Android debug APK | `PASS` | تحقق native من `libairi_native.so` ضمن `debug-tests.log`. |
| AndroidTest APK | `PASS` | `:app:assembleDebugAndroidTest` ضمن `debug-tests.log`. |
| Release APK/AAB محلي | `EXTERNAL_VERIFICATION_REQUIRED` | R8 (`:app:minifyReleaseWithR8`) قتل daemon مرتين تحت sandbox بذاكرة 3.8GiB؛ لا تغيّر source أو dependency محدد في السجل. |
| Static/security/platform gates | `PASS` | toolchain health وcross-platform health و41/41 core checks وsecurity scan وcore health. |
| Remote Android release/instrumentation baseline | `RUNTIME_VERIFIED` سابقاً | AIRI Android CI Run 32428583601 نجح قبل هذه migration. |

## دلالات النتيجة

فشل R8 المحلي مقيد بموارد البيئة ولا يُعالج بتعطيل minification أو تقليل ضوابط release. يظل تحقق release البعيد إلزامياً بعد كل gate toolchain. لا يمنح baseline أي دليل Desktop أو Windows أو Linux؛ جميع تلك الحالات تبقى وفق مصفوفة المنصات.
