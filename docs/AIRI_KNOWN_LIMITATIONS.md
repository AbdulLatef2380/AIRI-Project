# سجل التحقق الخارجي لـ AIRI

**آخر تحديث:** 18 أغسطس 2026

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

[1]: https://developer.android.com/about/versions/16/setup-sdk "إعداد Android 16 SDK"
[2]: https://developer.android.com/google/play/requirements/target-sdk "متطلب target API في Google Play"
