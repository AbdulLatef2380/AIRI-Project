# Gate ترقية Kotlin 2.2.21

## النطاق

نُفذت الترقية على `cp-foundation` فقط بعد baseline المنشور `cp-toolchain-baseline` عند `eb4714d2`. لم يُعدّل `architecture-refactor`. بقي Gradle `8.11.1` وAGP `8.10.1` وJDK 17 وcompileSdk/targetSdk 36 وNDK/CMake ثابتة.

| المكوّن | من | إلى | سبب التغيير |
| --- | --- | --- | --- |
| Kotlin/KMP | 1.9.22 | 2.2.21 | إزالة نطاق KMP/AGP غير المدعوم ودعم أساس Compose Desktop لاحقاً. |
| KSP | 1.9.22-1.0.17 | 2.2.21-2.0.5 | مطابق لـKotlin 2.2.21. |
| Compose Compiler | `composeOptions` 1.5.10 | plugin 2.2.21 | Kotlin 2.x يدير compiler plugin مع Kotlin. |
| Room | 2.6.1 | 2.8.4 | Room 2.6.1 فشل داخل KSP2 بـ`unexpected jvm signature V`. |
| Compose BOM | 2024.01.00 | 2025.08.00 | detector القديم لم يقرأ Kotlin metadata 2.2؛ BOM 2026.08 تجاوز حدود AGP/SDK، وCompose 1.9 هو التوافق المختبر. |

## المشكلات الجذرية والحلول

| الملاحظة | السبب المثبت | العلاج | النتيجة |
| --- | --- | --- | --- |
| `unexpected jvm signature V` في `:app:kspDebugKotlin` | stacktrace داخل Room 2.6.1 processor عند `InsertionMethodProcessor`. | ترقية Room runtime/ktx/compiler/test إلى 2.8.4. | KSP debug نجح. |
| lint لا يقرأ metadata 2.2 | Compose coroutine lint detector القديم يدعم metadata حتى 2.0. | Compose Compiler plugin وCompose BOM 2025.08.00. | compile وlint نجحا. |
| BOM 2026.08 يفشل AAR metadata | Compose 1.12 يطلب AGP 9.1 وcompileSdk 37. | الرجوع المقصود إلى BOM 2025.08.00/Compose 1.9. | لا ترقية AGP أو SDK غير لازمة. |
| daemon اختفى عند جمع مهام كبيرة | ضغط مورد sandbox، لا خطأ source مثبت. | تشغيل unit وAndroidTest APK كبوابات منفصلة مع heap متوازن. | كلتا البوابتين نجحتا. |
| `FilterChip` لا يترجم | Compose 1.9 يطلب `enabled` و`selected` في `filterChipBorder`. | تحديث موضعي لاستدعاءَي شاشة التخصيص. | compile نجح، والسلوك السابق محفوظ. |

## أدلة التحقق المحلية

| البوابة | الحالة | الأمر أو الدليل |
| --- | --- | --- |
| shared core JVM/Android | `BUILDS` | `:core-domain:desktopTest :core-domain:compileDebugKotlinAndroid` نجح. |
| KSP Room | `BUILDS` | `:app:kspDebugKotlin` نجح بعد Room 2.8.4. |
| Android compile/lint | `BUILDS` | `:app:compileDebugKotlin :app:lintDebug` نجح. |
| Android unit tests | `TESTED` | `:app:testDebugUnitTest` نجح. |
| debug APK/native | `BUILDS` | `:app:assembleDebug` ضمن البوابات؛ تحقق `libairi_native.so` مرئي. |
| AndroidTest APK | `BUILDS` | `:app:assembleDebugAndroidTest` نجح مستقلاً. |
| static/security/platform | `TESTED` | toolchain/cross-platform/platform/core health و41/41 وsecurity scan نجحت. |
| local release R8 | `EXTERNAL_VERIFICATION_REQUIRED` | حد ذاكرة sandbox قتل daemon أثناء `minifyReleaseWithR8`؛ لا تخفيف R8. |
| remote CI | `RUNTIME_VERIFIED` لـAndroid فقط | [Android CI](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32435349886) نجح، بما في ذلك release وinstrumentation وnative verification؛ نجح أيضاً [Deep Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32435349833) و[Architecture Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32435349919). |

لا يمثل `desktopTest` أو compile JVM دعماً لمنتج Linux أو Windows. يظل Desktop `ARCHITECTED` إلى أن توجد نافذة وتشغيل وتفاعل وحزمة مثبتة لكل نظام. نجح Android CI البعيد بمرحلة release وinstrumentation، لذلك أصبحت Android بعد Gate B `RUNTIME_VERIFIED` ضمن نطاق الترقية فقط.
