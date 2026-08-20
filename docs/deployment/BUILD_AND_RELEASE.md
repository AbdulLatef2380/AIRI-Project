# AIRI Core Build and Release

## متطلبات البيئة

| المتطلب | القيمة المثبتة في المصدر |
|---|---|
| JDK | 17 |
| compileSdk / targetSdk | 36 |
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.10.1 |
| Android NDK | 25.2.9519653 |
| CMake | 3.22.1 |
| إصدار التطبيق | `com.airi.assistant`، `versionCode=1`، `versionName=1.0` |

أنشئ `local.properties` متضمناً `sdk.dir=<path-to-android-sdk>`. لا تضعه أو مفاتيح signing في Git.

## أوامر التحقق

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC' \
  :app:assembleRelease :app:bundleRelease

python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_core_health.py
python3 scripts/supply_chain_inventory.py
```

## مخرجات الإصدار

| المخرج | المسار | التحقق المطلوب |
|---|---|---|
| Debug APK | `app/build/outputs/apk/debug/` | تثبيت/اختبار محاكي، ووجود JNI للـABI المطلوب. |
| Release APK | `app/build/outputs/apk/release/` | signing وR8 وغياب metadata تشخيصية. |
| Release AAB | `app/build/outputs/bundle/release/` | signing، R8 mapping، وفحص Manifest. |
| R8 mapping | `app/build/outputs/mapping/release/` | حفظ آمن مع artifact الإصدار. |
| Room schemas | `app/schemas/` | تضمين migration/test عند تغيير schema. |

## CI

ينفذ `.github/workflows/android_build.yml` build Debug وlint وJVM وRelease وAAB وتحضير/تشغيل Android instrumentation والتحقق من المكتبة الأصلية ثم رفع artifacts. لا يعتمد CI على ملف signing في Git؛ تُستخدم secrets البيئية المخصصة.

## توقيع الإنتاج

1. أنشئ أو انقل keystore عبر قناة آمنة وتحت ملكية الجهة الناشرة.
2. وفر aliases وكلمات المرور وBase64 keystore عبر secrets الخاصة بالبيئة فقط.
3. لا تغيّر `applicationId` أو signing lineage بعد توزيع Play من دون خطة ترحيل مدروسة.
4. احفظ artifact الموقع وAAB وmapping وSHA-256 وبيانات CI تحت سجل إصدار قابل للتدقيق.

## بنود خارج بيئة البناء

| البند | الحالة |
|---|---|
| هاتف فعلي وتنوع ABI/ذاكرة/حرارة | **NOT_RUNTIME_VERIFIED** |
| مفاتيح cloud وOAuth وFirebase الحقيقية | **EXTERNAL** |
| Google Play Console/Data Safety والتوقيع النهائي | **EXTERNAL** |
| مراجعة قانونية للتبعيات والنماذج | **EXTERNAL** |

لا يحول نجاح CI دون تنفيذ هذه الخطوات عند التحضير لنشر أو نقل تجاري فعلي.
