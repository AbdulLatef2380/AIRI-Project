# المرحلة 3.3 — الأسرار وTelemetry

## التغييرات المنفذة

تم جعل تهيئة `SecretVault` متزامنة في `AIRIApplication.onCreate` مباشرة بعد تثبيت `ServiceLocator.context`. لم تعد التهيئة تؤجل إلى coroutine الخلفية؛ وهذا يمنع مساراً مبكراً من استخدام broker قبل إنشاء مخزن Keystore.

تم تقوية `SecureStorage` ضد فشل القراءة بعد إبطال مفتاح Keystore أو فشل restore. القراءات الحساسة الآن تفشل مغلقاً وتعيد قيمة غياب آمنة بدلاً من إسقاط التطبيق أثناء cold start: لا token، لا API key، ولا حالة اتصال موجبة. كما أصبحت قراءة Google surface grants محمية بالطريقة نفسها.

تم إصلاح حد حذف الحساب/تسجيل الخروج في `FirebaseCrashReporter.clearUserData()`: يغلق collection gate داخل الذاكرة، يعطل Crashlytics، يمسح user identifier، ويحذف التقارير غير المرسلة. هذا يمنع استمرار الإرسال بعد انتهاء جلسة الحساب أو سحب الموافقة.

## لماذا هذا هو السلوك الصحيح

توضح Android أن Keystore يحمي مادة المفتاح من الاستخراج، وأن بعض انتقالات lock-screen/biometric قد تجعل المفتاح غير قابل للاستخدام. لذلك لا يجوز تحويل فشل الوصول إلى fallback نصي أو إبقاء credential state مؤثراً. [1]

توصي Android باستخدام نظام النسخ الاحتياطي القياسي مع استبعاد البيانات الحساسة أو اشتراط تشفير طرف-إلى-طرف عند عدم إمكانية الاستبعاد. Manifest الحالي يعلن `android:allowBackup="false"`؛ لذلك لا تدخل أسرار AIRI في Auto Backup أو D2D من إعداد التطبيق الحالي. [2]

تدعم Firebase نمط opt-in عبر تعطيل الجمع التلقائي ثم تفعيله بعد موافقة المستخدم. التطبيق يطبق ذلك افتراضياً، والتغيير الحالي يضيف إغلاقاً صريحاً عند الحذف/السحب، مع إبقاء `code`, `state`, tokens, prompts وPII خارج custom keys/logs. [3]

## حدود التحقق

- `git diff --check`: ناجح.
- `python3 tools/verify_core_changes.py`: **101/101**.
- `python3 scripts/static_chat_runtime_audit.py`: **35/35**.
- اختبارات Android الفعلية لــ Keystore invalidation وbackup/restore وprocess death تحتاج emulator/device وAndroid SDK؛ Sandbox الحالية لا توفر SDK، ولذلك تبقى هذه الحالات `RUNTIME_PENDING` وليست `VERIFIED`.
- يجب تنفيذ اختبار device لاحقاً: حفظ credential، تغيير lock-screen/استعادة backup، محاولة قراءة provider، التأكد من fail-closed، ثم إعادة مصادقة المستخدم وحفظ credential جديد.

## مراجع رسمية

[1]: https://developer.android.com/privacy-and-security/keystore "Android Keystore system — Android Developers"
[2]: https://developer.android.com/privacy-and-security/risks/backup-best-practices "Security recommendations for backups — Android Developers"
[3]: https://firebase.google.com/docs/crashlytics/android/customize-crash-reports "Customize crash reports for Android — Firebase"
