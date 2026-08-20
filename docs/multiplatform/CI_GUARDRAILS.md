# بوابات CI للنواة متعددة المنصات

بعد Gate 2C، يحتوي سير العمل `.github/workflows/android_build.yml` على خطوة **Build and test shared core** قبل build Android. تنفذ هذه الخطوة الاختبارات والبناء التاليين:

```bash
./gradlew :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid --stacktrace
```

| الأمر | الغرض | ما لا يثبته |
| --- | --- | --- |
| `:core-domain:desktopTest` | يترجم ويختبر source `commonMain` على target JVM العام المسمى `desktop`. | لا ينشئ تطبيق Desktop أو حزمة Windows/Linux أو تحقق تشغيل لها. |
| `:core-domain:compileDebugKotlinAndroid` | يثبت أن النواة تبني كتبع Android. | لا يثبت تشغيل تطبيق Android على جهاز. |
| `:app:assembleDebug` وما بعده | يثبت أن Android يستهلك النواة مع build ومسارات الاختبار القائمة. | لا يرقّي أي target خارجي إلى دعم منتج. |

يستمر الحارس المحلي `scripts/airi_cross_platform_health.py` في رفض Android/AndroidX/Room/WorkManager/JNI/JVM APIs داخل `commonMain`، ويعيد فاحص التبعيات `scripts/airi_platform_dependency_scan.py` توليد التقرير. تكمل هذه البوابات ولا تستبدل instrumentation Android أو فحص الأمان أو اختبارات acceptance المستقبلية لسطح المكتب وWeb.

> تمرير CI للنواة يعني أن منطقاً مشتركاً محدوداً لا يعتمد Android؛ لا يعني أن Windows أو Linux أو Web مدعومة. تبقى الحالة في [مصفوفة المنصات](PLATFORM_MATRIX.md) مقيدة بأدلة artifact وتشغيل لكل منصة.
