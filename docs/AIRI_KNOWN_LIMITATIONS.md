# سجل التحقق الخارجي لـ AIRI

**آخر تحديث:** 13 نوفمبر 2026

> أغلقت الشفرة وبوابات البناء المحلية: API 36، Debug، lint، JVM، Release، AAB، R8، وJNI. العناصر الآتية هي تحقق ميداني أو عملية نشر سرية لا يمكن تنفيذها داخل بيئة البناء المحلية.

| المعرف | المجال | ما تم إثباته | التحقق الخارجي المطلوب |
|---|---|---|---|
| FV-01 | توقيع upload | مسار CI يقرأ أسرار keystore ويحذف الملف المؤقت؛ AAB المحلي يبنى بنجاح بلا مفاتيح. | تشغيل workflow بعد إدخال أسرار upload في GitHub وفحص شهادة AAB. |
| FV-02 | رحلة المحادثة | تم بناء المسارات واختبار routing والإلغاء على JVM. | على جهاز Android: onboarding، تنزيل نموذج، local/cloud، streaming، إيقاف، مرفق، restart. |
| FV-03 | Room | اختبار v1→v6 مترجم ضمن AndroidTest ويستخدم migrations الإنتاجية. | تشغيل `connectedDebugAndroidTest` على محاكي أو جهاز وتأكيد migration مع بيانات فعلية. |
| FV-04 | التخزين المشفر | اختبار مخزن مفاتيح API مترجم ضمن AndroidTest؛ مفاتيح API/OAuth لا تمر عبر تفضيلات نصية عادية. | تشغيل AndroidTest والتأكد من استمرار keystore بعد restart على أجهزة مستهدفة. |
| FV-05 | الصوت | Vosk وTTS وhotword تترجم وتدخل artifact. | اختبار إذن الميكروفون، المقاطعة، Bluetooth، العربية والإنجليزية، وإيقاف wake word. |
| FV-06 | خدمات cloud وOAuth | اختبارات HTTP المحلية تشمل 200 و401 و429 و500، واختبارات PKCE state موجودة. | استخدام مفاتيح اختبار لمزوّدين حقيقيين، callback deep link، timeout وتبديل endpoint. |
| FV-07 | الجدولة | WorkManager يستخدم أعمالاً فريدة وحالات محفوظة في المصدر. | اختبار Doze وreboot والمنطقة الزمنية والإشعارات على جهاز. |
| FV-08 | الإتاحة والواجهة | الموارد متكافئة بين اللغات و`supportsRtl` مفعّل وlint ناجح. | مراجعة TalkBack وfont scale وRTL المرئي والسمتين على Android. |
| FV-09 | الأداء | R8 وresource shrinking نجحا، وحجم AAB المحلي نحو 23 MB. | قياس startup وRAM وCPU وjank والتخزين على أجهزة ممثلة. |
| FV-10 | Desktop | Linux/Windows foundation يبني ويختبر history/attachments/preferences/pairing مع capability gating صريح. | model adapter، remote adapter، tool execution، picker التفاعلي، restart/recovery على أنظمة حقيقية. |
| FV-11 | realtime cloud voice | توجد عقود/state machine فقط؛ المسار النشط محلي Vosk + Android TTS ولا يختار cloud transport بصمت. | full-duplex microphone → transport → model → AudioTrack مع privacy/credentials واختبار مقاطعة. |
| FV-12 | حذف الحساب والمسح المحلي | `DataDeletionCoordinator` يميز بوضوح بين `eraseLocalData()` وحذف الحساب، ويعيد `PartialSuccess` عند فشل خطوة محلية. | اختبار جهاز بعد تكرار المسح وقتل العملية، وفشل Room/filesystem/keystore منفردًا. |
| FV-13 | التحديث والرجوع | التحديث التلقائي محجوب fail-closed عبر `UpdateAvailabilityPolicy`; لا يوجد ادعاء بوجود catalog أو installer مصادق. | catalog موقع، تحقق hash/signature، installer hand-off، وrollback على جهاز حقيقي. |
| FV-14 | الأداء والحرارة | `RuntimeProfiler` و`ThermalProfiler` وواجهات الأداء موجودة للتشخيص؛ لا توجد أرقام قبول ميدانية مدعاة. | Perfetto/Macrobenchmark وdumpsys/batterystats عبر أجهزة منخفضة ومتوسطة وعالية. |

## سلسلة التحقق المحلية

| العنصر | النتيجة |
|---|---|
| AGP / Gradle / JDK | 8.10.1 / 8.11.1 / 17 |
| SDK | `compileSdk` و`targetSdk` 36؛ `minSdk` 26 |
| اختبارات JVM | 26/26 ناجحة؛ 0 failures؛ 0 errors |
| المتحقق الثابت | 25/25 ناجح |
| lintDebug | ناجح بلا أخطاء |
| Debug APK | ناجح مع `libairi_native.so` |
| Release APK وAAB | ناجحان عبر R8 مع R8 mapping |

## مراجع

التفاصيل التشغيلية الجديدة موجودة في [مصفوفة قدرات المنتج](product/AIRI_PRODUCT_CAPABILITY_MATRIX.md)، و[دليل Desktop للمرحلة 8](multiplatform/DESKTOP_PHASE8_EVIDENCE.md)، و[مصفوفة قبول الجدولة](product/AIRI_SCHEDULE_ACCEPTANCE_MATRIX.md)، و[سجل جاهزية المرحلة 8](product/AIRI_PHASE8_RELEASE_READINESS.md).

[1]: https://developer.android.com/about/versions/16/setup-sdk "إعداد Android 16 SDK"
[2]: https://developer.android.com/google/play/requirements/target-sdk "متطلب target API في Google Play"
