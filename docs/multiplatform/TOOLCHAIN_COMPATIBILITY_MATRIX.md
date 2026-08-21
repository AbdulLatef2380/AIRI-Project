# مصفوفة توافق Toolchain

تلتقط هذه المصفوفة baseline الالتزام `eb4714d2` وتحدد **المرحلة المستهدفة الأولى** فقط. لا تعني target values أن كل تبعية ستتغير في commit واحد. وفق جدول Kotlin الرسمي، يدعم Kotlin Multiplatform `2.2.21` Gradle حتى `8.14` وAGP حتى `8.11.1`، ولذلك يقع Gradle `8.11.1` وAGP `8.10.1` الحاليان داخل نطاق مدعوم رسمياً [1]. تستخدم الترقية هذا الإصلاح الأدنى أولاً بدلاً من تحديث AGP وGradle بلا ضرورة.

| المكوّن | الحالي | target المرحلة الأولى | مطلوب من | التوافق | الخطر | ملاحظات الهجرة |
| --- | --- | --- | --- | --- | --- | --- |
| Kotlin / KMP plugin | 1.9.22 | 2.2.21 | core-domain وK2 وCompose Desktop لاحقاً | مدعوم مع Gradle 8.11.1 وAGP 8.10.1 [1] | مرتفع | يرتفع مع Compose Compiler plugin في الخطوة المقترنة فقط. |
| KSP | 1.9.22-1.0.17 | 2.2.21-2.0.5 | Room code generation | إصدار رسمي مقابل Kotlin 2.2.21 [2] | مرتفع | كشف KSP2 قصور Room 2.6.1؛ يرافقه Room 2.8.4 من دون ترحيل schema. |
| Compose Compiler Gradle plugin | composeOptions 1.5.10 | 2.2.21 | Compose Android وCompose MPP لاحقاً | مطلوب لـKotlin 2.0+ وفق Android [3] | مرتفع | يستبدل extension version؛ لا يضاف Compose MPP بعد. |
| Compose Multiplatform plugin | غير موجود | 1.8.2 كـمرحلة Desktop لاحقة | app-desktop | يتطلب Kotlin 2.1+ ابتداءً من Compose 1.8 [4] | متوسط | لا يضاف حتى تنجح خطوة Kotlin/Compose Compiler. |
| Gradle | 8.11.1 | 8.11.1 | البناء وCI | مدعوم مع Kotlin 2.2.21 [1] | منخفض | ثابت في المرحلة الأولى. |
| Android Gradle Plugin | 8.10.1 | 8.10.1 | Android app وnative | مدعوم مع Kotlin 2.2.21 [1] | متوسط | ثابت لإزالة churn؛ يعاد تقييمه بعد Desktop foundation. |
| JDK | 17.0.19 | 17 | Android وDesktop packaging | JDK 17+ مطلوب لتغليف Desktop [4] | منخفض | ثابت. |
| Android SDK | compile/target 36، min 26 | ثابت | Android | baseline ناجح | منخفض | لا تغيير. |
| NDK | 25.2.9519653 | ثابت | JNI/llama.cpp | baseline native ناجح | متوسط | لا تغيير أثناء toolchain gate. |
| CMake | 3.22.1 | ثابت | JNI/llama.cpp | baseline native ناجح | منخفض | لا تغيير. |
| Jetpack Compose BOM | 2024.01.00 | 2025.08.00 | Android UI وCompose lint | Compose 1.9 stable [6] وKotlin 2.x عبر Compose Compiler plugin [3] | متوسط | BOM 2026.08 يفرض AGP 9.1/compileSdk 37؛ اختير 2025.08 لاجتياز AGP 8.10/compileSdk 36. |
| Room | 2.6.1 | 2.8.4 | Android persistence وKSP | إصدار Room المستقر الرسمي [5] | مرتفع | يعالج crash KSP الفعلي؛ لا نقل إلى KMP ولا schema change. |
| Kotlinx Coroutines | 1.7.3 | ثابت | Android concurrency | يحتاج build/test فقط | متوسط | لا تحديث dependency الآن. |
| Coil | 2.6.0 | ثابت | Android image UI | Android-only | منخفض | لا يدخل commonMain. |
| Vosk | 0.3.47 | ثابت | STT Android | Android/native binding | مرتفع | لا تغير أثناء migration. |
| Porcupine | 3.0.1 | ثابت | wake word Android | Android/native binding | مرتفع | لا تغير أثناء migration. |
| llama.cpp integration | NDK/JNI محلي | ثابت | local inference | Android ABI-specific | حرج | يبقى Android implementation حتى spike Desktop native. |
| OkHttp / Gson / Firebase | 4.12.0 / 2.10.1 / BOM 32.8.0 | ثابت | providers/auth/analytics | Android baseline | متوسط | لا يعاد تصميمها في toolchain gate. |

## قرار الإصدار

اختير Kotlin `2.2.21` بدلاً من أحدث Kotlin متاح لأن جدول التوافق الرسمي يثبت علاقته المباشرة مع **Gradle 8.11.1 وAGP 8.10.1** الحاليين، ما يسمح بإزالة تحذير KMP مع أقل تغيير ممكن. أظهر الاختبار أن Room 2.6.1 يفشل في KSP2، وأن Compose BOM 2024.01 لا يستطيع lint فيها قراءة Kotlin metadata 2.2؛ لذلك أصبحت Room 2.8.4 وCompose BOM 2025.08 جزءاً من الإصلاح المقترن. لا تختبر هذه الخطوة Desktop UI بعد؛ شرطها هو استعادة Android وcore إلى حالة النجاح على toolchain مدعوم. بعد ذلك فقط يضاف Compose Multiplatform وDesktop shell.

## المراجع

[1]: https://kotlinlang.org/docs/gradle-configure-project.html "Configure a Gradle project | Kotlin Documentation"
[2]: https://github.com/google/ksp/releases/tag/2.2.21-2.0.5 "KSP 2.2.21-2.0.5 release"
[3]: https://developer.android.com/jetpack/androidx/releases/compose-kotlin "Compose to Kotlin Compatibility Map"
[4]: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html "Compatibility and versions | Kotlin Multiplatform"
[5]: https://developer.android.com/jetpack/androidx/releases/room "Room release notes | Android Developers"
[6]: https://developer.android.com/jetpack/androidx/releases/compose "Compose release notes | Android Developers"
