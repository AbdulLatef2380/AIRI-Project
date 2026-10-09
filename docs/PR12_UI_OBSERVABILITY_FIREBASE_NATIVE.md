# PR-12 — واجهة ومراقبة وموافقة Firebase/native

## الحالة

تم تنفيذ نطاق PR-12 على آخر نقطة من `origin/pr-0-pr-3` بعد إغلاق PR-0 إلى PR-11.

## ما تم إغلاقه

- إضافة قسم Privacy & telemetry في `PrivacyDataSettingsScreen` بثلاثة مفاتيح مستقلة:
  - Firebase Analytics
  - Firebase Crashlytics
  - Agent diagnostics
- ربط المفاتيح مباشرةً بـ`TelemetryConsentStore` و`StateFlow`، مع تفعيل/تعطيل جمع Firebase فور تغيير المستخدم.
- إبقاء الافتراضي `false` لكل الفئات، وعدم تفعيل Analytics أو Crashlytics في `AIRIApplication` دون موافقة مخزنة صريحة.
- تحويل `ExecutionHistoryStore` إلى مصدر Flow واحد محفوظ بحد أقصى 200 إدخال، مع تحديث حي ومسح ذري من الذاكرة والتخزين.
- إضافة `runId` إلى سجل التاريخ عند توفر trace id.
- منع تخزين مقتطفات الإدخال الخام، والحد من رسائل الأخطاء والروابط في التاريخ المحلي.
- ربط `ObservabilityScreen` بمصدر Flow المستمر بدلاً من لقطة قراءة منفصلة.
- تقليل قيم Analytics قبل الإرسال: إزالة الروابط والسطور والقيم الحرة الطويلة، مع حد طول ثابت.
- إضافة فحص خاص لـ`SCHEDULE_EXACT_ALARM` عبر `AlarmManager.canScheduleExactAlarms()` وفتح شاشة Android الرسمية عند الحاجة؛ لا يُعتبر تصريح manifest منحة runtime.
- إضافة ترجمة عربية/إنجليزية لعناصر الموافقة وexact alarm.
- جعل دليل native runtime ديناميكياً عبر `Build.SUPPORTED_ABIS` بدلاً من ادعاء ABI ثابت.

## التحقق

نجح:

- `git diff --check`
- تحليل XML لـ`AndroidManifest.xml` وملفات `strings.xml` العربية والإنجليزية.
- التحقق الساكن من عدم وجود `Input:` أو `rawPrompt` أو `toolArgs` في مسارات التاريخ والتليمترية المعدلة.

تعذر تشغيل Gradle في البيئة الحالية لأن Android SDK غير موجود:

```text
SDK location not found. Define a valid SDK location with ANDROID_HOME
or by setting sdk.dir in local.properties.
```

لذلك لا يُسجل هذا PR ادعاء نجاح `testDebugUnitTest` أو `lintDebug` حتى يُعاد تشغيلهما في بيئة تحتوي Android SDK.

## حدود الإثبات المتبقية

- اختبار Compose RTL/TalkBack يحتاج Android emulator.
- JNI smoke واختبار ABI يحتاج APK مبنياً وجهازاً/محاكياً فعلياً.
- Firebase staging collection يحتاج مشروع staging مصرحاً به؛ لم يتم إرسال أي بيانات إلى مشروع إنتاج.
