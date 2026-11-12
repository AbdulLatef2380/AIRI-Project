# AIRI — Phase 8 Release Readiness Evidence

**الحالة:** `SOURCE_CONTRACTED / EXTERNAL_DEVICE_GATE_REQUIRED`

هذا المستند يغلق البنود 6–9 من المرحلة 8 على مستوى العقود والتوثيق، ولا يحول الاختبارات التي تحتاج جهازًا أو متجرًا أو خدمة حية إلى ادعاءات نجاح.

## 6. حذف الحساب والمسح المحلي وإعادة التشغيل

### العقد المثبت في المصدر

`DataDeletionCoordinator` هو المالك الوحيد لمسار الحذف. يبدأ بإيقاف WorkManager، ويفصل حذف بيانات الخادم عن حذف الحساب، ولا يلمس التخزين المحلي قبل نجاح حذف البيانات البعيدة وحذف Firebase في المسار الكامل. أما `eraseLocalData()` فينفذ مسحًا محليًا صريحًا دون ادعاء حذف حساب أو بيانات مزود خارجي.

بعد نجاح الحذف المحلي، يغطي المسار Room، الملفات والآثار والمرفقات و`mmproj_active.gguf`، `SecureStorage`، كل SharedPreferences، cache، ثم تسجيل الخروج المحلي. كل خطوة مستقلة ومحمية بـ`runStep`، والنتيجة إما `Success` أو `PartialSuccess` مع أسماء الخطوات الفاشلة؛ لذلك يمكن إعادة المحاولة بأمان ولا يُخفى الفشل.

### دليل المصدر

- `app/src/main/java/com/airi/assistant/domain/auth/DataDeletionCoordinator.kt`
- `DataDeletionCoordinator.eraseLocalData()` و`deleteAccount()`
- `Step`: `STOP_BACKGROUND_WORK`, `ROOM_DATA_WIPE`, `FILESYSTEM_WIPE`, `CREDENTIAL_WIPE`, `PREFERENCE_RESET`, `CACHE_WIPE`, `LOCAL_SIGN_OUT`
- `app/src/test/java/com/airi/assistant/ui/viewmodel/SessionDeletionPolicyTest.kt`
- `app/src/androidTest/java/com/airi/assistant/execution/security/SecureApiKeyStoreInstrumentedTest.kt`

### بوابة الجهاز المطلوبة

يجب تنفيذ المسح مرتين، ثم قتل العملية وإعادة فتح التطبيق، والتحقق من عدم ظهور الجلسات أو المفاتيح أو الملفات أو آثار trace، ومن عدم إعادة جدولة Worker قديم. كما يجب اختبار فشل كل من Room وfilesystem وkeystore منفردًا والتأكد من ظهور `PartialSuccess` مع إمكانية retry.

## 7. التحديث والرجوع

`UpdateAvailabilityPolicy.automaticChecksAvailable = false` عمدًا. لا يوجد تفعيل للتحديث التلقائي قبل توفر release catalog موثق وموقع، والتحقق من hash/signature، وتسليم installer مصادق، وقاعدة rollback قابلة للاختبار. `UpdateScreen` لا يجوز أن يعرض التحديث كميزة جاهزة اعتمادًا على وجود build فقط.

### عقد الإصدار المطلوب قبل التفعيل

1. catalog موقع يحتوي الإصدار، package/application id، version code، artifact hash، signer fingerprint، minimum supported version وrollback target.
2. تحقق محلي من التوقيع وhash قبل أي تثبيت، مع رفض fail-closed عند الغموض أو downgrade غير مصرح.
3. installer hand-off واضح، مع نتيجة نجاح/فشل قابلة للتسجيل دون تسريب URL أو token.
4. rollback موثق ومختبر على جهاز: تثبيت المرشح، فشل الترقية، العودة إلى artifact سابق متوافق، ثم استعادة البيانات.
5. release notes وmigration evidence لكل schema أو preference change.

**الحالة الحالية:** `NOT_IMPLEMENTED / SAFE_DISABLED`، وليست `PARTIAL` أو `READY`.

## 8. الأداء والحرارة والذاكرة

يوفر التطبيق قياسًا داخليًا عبر `RuntimeProfiler` و`ThermalProfiler` وواجهات `PerformanceScreen` و`ModelPerformanceScreen`. هذه القياسات diagnostics وليست بديلًا عن benchmark ميداني. يجب حفظ كل نتيجة مرتبطة بـcommit وdevice/build/model/quantization/temperature/battery/network.

### مصفوفة القياس الخارجية

| المحور | القياس | شرط القبول المقترح | الدليل |
|---|---|---|---|
| Startup | cold/warm launch حتى أول frame مفيد | p95 على كل فئة جهاز ضمن ميزانية الإصدار | Perfetto/Macrobenchmark |
| الذاكرة | RSS وJava/native heap أثناء idle و10 رسائل | لا تسرب متزايد بعد 10 دورات | dumpsys meminfo + trace |
| الحرارة | حرارة البطارية وthermal status أثناء 10 دقائق inference | لا emergency throttle غير مبرر ولا crash | `ThermalProfiler` + logcat |
| latency | first token، decode، total latency | p50/p95 لكل quantization | `RuntimeProfiler` وModelPerformance |
| الطاقة | battery delta أثناء inference وidle | مقارنة baseline موثقة | batterystats |
| Compose | frame time/jank أثناء streaming وscroll | لا regression عن baseline | Macrobenchmark/Perfetto |

الأجهزة المطلوبة: جهاز منخفض، متوسط، وعالي الأداء، مع API 26 وAPI حديث، وarm64 حقيقي. يجب تسجيل العربية والإنجليزية، local/cloud، portrait/RTL، وfont scale 1.0 و1.3.

## 9. توثيق الإصدار والقيود

README ومصفوفة القدرات وسجل القيود يجب أن تبقى متسقة. أي ميزة لا تملك دليلًا من المصدر والاختبار والجهاز/المزود تُصنف `Not Implemented`, `Prototype`, أو `External Gate Required`، ولا تُعرض للمستخدم كقدرة مكتملة.

## بوابة التحقق

شغّل:

```bash
python3 scripts/airi_phase8_release_readiness.py
python3 scripts/airi_core_health.py
python3 scripts/airi_cross_platform_health.py
python3 tools/security_scan.py
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
```

النجاح الساكن لا يغلق بوابات الجهاز أو المزود الحي أو المتجر؛ بل يثبت فقط أن التصنيف والعقود لا تتناقض مع المصدر.
