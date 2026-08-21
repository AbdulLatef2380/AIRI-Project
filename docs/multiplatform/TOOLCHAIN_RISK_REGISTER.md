# سجل مخاطر Toolchain

| ID | الخطر | الاحتمال | الأثر | التخفيف | معيار الإغلاق | الحالة |
| --- | --- | --- | --- | --- | --- | --- |
| TC-01 | Kotlin 1.9.22 لا يدعم AGP 8.10.1 رسمياً في KMP. | واقع قائم | مرتفع | ترقية Kotlin/KMP إلى 2.2.21 مع إبقاء AGP ثابتاً ضمن نطاقه الرسمي. | core وAndroid وCI ينجحان بلا تحذير compatibility القديم. | مفتوحة |
| TC-02 | KSP قد يفشل مع Kotlin الجديد أو Room compiler. | متوسط | مرتفع | استعمال KSP 2.2.21-2.0.5 وربط Room بلا تغيير. | KSP debug/release وRoom tests/schema ينجحان. | مفتوحة |
| TC-03 | Compose Android قد يفشل بعد Kotlin 2.x. | متوسط | مرتفع | تطبيق Compose Compiler Gradle plugin المطابق؛ إبقاء BOM ثابتاً أولاً. | compile/lint/UI tests Android تنجح. | مفتوحة |
| TC-04 | R8 release المحلي قد يُقتل عند حد sandbox 3.8GiB. | مرتفع | متوسط | حفظ logs، عدم تعطيل minify، استخدام remote CI كدليل release، وإعادة المحاولة فقط في مورد مناسب. | release محلي أو CI remote ناجح مع R8. | مفتوحة |
| TC-05 | JNI/NDK وllama.cpp قد يتأثران بتغير Kotlin/AGP. | منخفض | حرج | إبقاء AGP/NDK/CMake ثابتة، والتحقق من native library في debug/CI. | `airiVerifyNativeInDebugApk` وCI native check ينجحان. | مفتوحة |
| TC-06 | plugin Compose Multiplatform يسبب churn في Compose Android. | متوسط | مرتفع | لا يضاف قبل نجاح Kotlin compiler gate؛ milestone مستقل. | Android gates وDesktop compile ينجحان. | مفتوحة |
| TC-07 | حزم Windows/Linux تتطلب بيئات OS مستقلة. | مرتفع | متوسط | Linux runtime هنا؛ Windows CI أو host حقيقي قبل رفع الحالة. | artifact وruntime evidence لكل OS. | مفتوحة |
| TC-08 | فقد rollback أو خلط تغييرات متعددة. | منخفض | حرج | checkpoint منشور وcommit لكل gate وlogs baseline. | branch/checkpoint صالحان وworktree نظيفة. | مخففة |

## أدلة baseline ذات الصلة

نجحت بوابة baseline debug/core/unit/lint/AndroidTest APK محلياً. فشل release المحلي مرتين عند `:app:minifyReleaseWithR8` مع اختفاء daemon؛ لا يوجد فشل source أو dependency محدد، والذاكرة المتاحة للحاوية 3.8GiB. هذه ليست مبرراً لتخفيف R8 أو تغيير كود المنتج. تثبت Android CI البعيدة من Gate 2 نجاح مسار release وinstrumentation قبل بدء الترقية؛ سيعاد تشغيل CI بعد كل commit toolchain.
