# قائمة جاهزية إصدار AIRI

**تاريخ التحقق:** 18 أغسطس 2026
**الفرع:** `architecture-refactor`

| بوابة القبول | الحالة | الدليل |
|---|---|---|
| Android API | PASS | `compileSdk = 36` و`targetSdk = 36` باستخدام AGP 8.10.1 وGradle 8.11.1 وJDK 17. [1] [2] |
| اختبارات JVM | PASS | 26 اختباراً؛ 0 failures؛ 0 errors. |
| المتحقق الثابت | PASS | 25/25 فحصاً. |
| lint Debug | PASS | `:app:lintDebug` بلا أخطاء. |
| Debug APK | PASS | `:app:assembleDebug` وتحقق JNI للـ`arm64-v8a`. |
| Android test APK | PASS | `:app:assembleDebugAndroidTest` نجح، ويضم Room migration ومخزن المفاتيح المشفر. |
| Release APK | PASS | `:app:assembleRelease` نجح عبر R8 وتحقق JNI. |
| Release AAB | PASS | `:app:bundleRelease` نجح؛ AAB نحو 23 MB. |
| R8 mapping | PASS | mapping مُنشأ مع إصدار Release. |
| توقيع upload | CI CONFIGURED | workflow يمرر أسرار keystore إلى Gradle ويحذف الملف المؤقت بعد البناء. |

## إغلاق مسار الإصدار

تنتج البيئة المحلية artifacts غير موقعة عند غياب keystore، وهو السلوك المقصود كي لا تدخل مادة توقيع إلى المستودع. لإغلاق مسار upload في CI، تُضبط الأسرار الأربعة: `KEYSTORE_BASE64` و`STORE_PASSWORD` و`KEY_ALIAS` و`KEY_PASSWORD`. يبني workflow بعد ذلك APK وAAB موقّعين ويحتفظ بتقارير البناء.

## التحقق الميداني المخصص

رحلة Android الحقيقية هي خطوة الاختبار المتبقية: chat وstreaming وStop وملحقات، استرداد Room، تخزين keystore، الصوت، الموفرات وOAuth، WorkManager في Doze/reboot، وRTL/TalkBack. لا تعدّل هذه الخطوة الشفرة أو artifacts، بل تؤكد سلوكها على أجهزة مستهدفة.

## المراجع

[1]: https://developer.android.com/about/versions/16/setup-sdk "إعداد Android 16 SDK"
[2]: https://developer.android.com/build/releases/agp-8-10-0-release-notes "توافق Android Gradle Plugin 8.10"
