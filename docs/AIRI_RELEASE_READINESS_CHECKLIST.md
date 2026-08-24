# قائمة جاهزية إصدار AIRI Android

**تاريخ المراجعة:** 24 أغسطس 2026
**الفرع التنفيذي:** `cp-foundation`
**الحالة المعتمدة:** `FEATURE_FREEZE / INTERNAL_CANDIDATE_EVIDENCED / SIGNING_SECRETS_BLOCKED`

> هذه القائمة لا تمنح موافقة نشر. إنها تفصل ما أثبتته CI والمصدر عن متطلبات التوقيع والجهاز والمزود والناشر والقانون التي لم تُنفذ بعد.

## البوابات الداخلية المثبتة

| بوابة القبول | الحالة | الدليل والحد |
|---|---|---|
| Android API/toolchain | **CI_VERIFIED** | `compileSdk=36` و`targetSdk=36` وAGP 8.10.1 وGradle 8.11.1 وJDK 17؛ لا يثبت ذلك توافق أجهزة فعلية. |
| عقود المصدر والترجمة | **CI_VERIFIED** | core verifier 77/77 وترجمات en/ar/es/zh الصارمة اجتازت في CI. |
| Debug وJVM وlint | **CI_VERIFIED** | Android CI اجتازت build debug واختبارات JVM وlint؛ ليست دليلاً للتوزيع. |
| Android instrumentation | **CI_VERIFIED** | API 29/x86_64 emulator اجتاز المسارات المتاحة، بما فيها اختبار الاستعادة المحلي المحدد. |
| Release APK/AAB مع R8 | **CI_UNSIGNED_PACKAGE_VERIFIED** | run [`32720458806`](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32720458806) أنتج APK/AAB غير موقّعين و`mapping.txt` وAPK badging وAAB ZIP و`UNSIGNED_SHA256SUMS`. |
| الحزمة الأصلية JNI | **CI_VERIFIED** | CI تحققت من `libairi_native.so` ضمن مخرجات APK المناسبة. |
| مسار Feature Freeze التجاري | **CI_VERIFIED** | أسطح الدفع/Stripe/الفوترة/Marketplace/Community Skills محجوبة fail-closed، وإذن Billing المدمج محذوف. |
| توقيع upload | **SIGNING_SECRETS_BLOCKED** | run [`32742046966`](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32742046966) اجتازت كل البوابات غير الموقعة على `main`، لكن `RELEASE_SIGNING_READY=false`؛ لا APK/AAB موقّعة أو `apksigner` أو SHA-256 نهائية. |

## هوية التوقيع

التحقيق الآمن في شجرة العمل وتاريخ Git وأسماء artifacts وassets المنشورة لم يجد keystore أو هوية إصدار قابلة للاستعادة. الأصل العام السابق الوحيد `v0.1.0-alpha/airi-debug.apk` يحمل شهادة **Android Debug** ولا يصلح كهوية نشر. لا تُنشأ بدائل debug ولا يُفترض أن GitHub secrets نسخة استرداد وحيدة.

| القرار المطلوب قبل signing | الشرط |
|---|---|
| حفظ الهوية | يحدد مالك الإصدار backup خاصاً ومشفراً خارج GitHub وManus. |
| إنشاء أو استيراد key | يستعمل هوية ثابتة غير debug تحت ملكية الجهة الناشرة فقط بعد تثبيت backup. |
| تهيئة CI | تحفظ فقط `KEYSTORE_BASE64` و`STORE_PASSWORD` و`KEY_ALIAS` و`KEY_PASSWORD` في secrets الآمنة؛ لا تظهر القيم في Git أو issue أو log. |
| دليل النجاح | تشغيل `main` محمي ينتج APK/AAB موقعة، `mapping.txt`، `SHA256SUMS`، و`apksigner verify --verbose` و`--print-certs`. |

## البوابات الخارجية غير المكتملة

| البوابة | الحالة | الحد المطلوب |
|---|---|---|
| جهاز Android حقيقي | **RUNTIME_VERIFICATION_PENDING** | API 26 وAPI 35/36، arm64، الصلاحيات، الملفات، WorkManager/Doze، TalkBack، RTL/LTR، والاستعادة وlocal erase. |
| مزودون حيّون | **EXTERNAL_VERIFICATION_REQUIRED** | Firebase/OAuth/Calendar/GitHub فقط إذا أعلنوا ضمن الإصدار؛ إثبات consent/cancel/revoke/failure/recovery ببيانات منزوعة الحساسية. |
| الخصوصية والقانون | **EXTERNAL_VERIFICATION_REQUIRED** | سياسة خصوصية، Data Safety، declarations، مراجعة التبعيات والنماذج وشروط المزود. |
| Google Play | **EXTERNAL_VERIFICATION_REQUIRED** | حساب ناشر، artifact موقع، listing، internal track، pre-launch، وقرار rollout. |

## مصادر الحقيقة

تُقرأ هذه القائمة مع [إغلاق الإصدار](product/AIRI_RELEASE_CLOSURE.md)، و[سجل التدقيق](product/RELEASE_AUDIT_REGISTER.md)، و[handoff النشر](product/RELEASE_PUBLICATION_HANDOFF.md)، و[مصفوفة الجهاز والمتجر](product/RELEASE_DEVICE_AND_STORE_MATRIX.md). عند التعارض، تكون وثائق `docs/product/` هي السلطة التنفيذية.
