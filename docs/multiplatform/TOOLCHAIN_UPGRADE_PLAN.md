# خطة ترقية Toolchain التدريجية

## نطاق التحول

هذه الخطة تفصل **إثبات toolchain** عن **إضافة Desktop**. لا يتغير `architecture-refactor`، وتعمل جميع commits على `cp-foundation` مع checkpoint `cp-toolchain-baseline` كمرجع رجوع منشور. لا ينتقل Android UI أو Room schema أو JNI/llama.cpp أو providers إلى النواة ضمن هذا المسار.

| الخطوة | التغيير المعزول | الاختبارات المحلية | شرط التقدم | rollback | الحالة |
| --- | --- | --- | --- | --- | --- |
| A | baseline + وثائق + حراس | Gradle metadata، core، unit، lint، debug، AndroidTest APK، release حيث تسمح الموارد | أدلة baseline ومسار CI سابق ناجح | `cp-toolchain-baseline` | `COMPLETED` |
| B | Kotlin/KMP 2.2.21 + KSP 2.2.21-2.0.5 + Compose Compiler plugin 2.2.21 + Room 2.8.4 + Compose BOM 2025.08 | core، Android unit/lint/debug/release/AndroidTest APK، static/security | Android وcore ينجحان؛ تحذير KMP/AGP القديم يختفي | commit A/checkpoint | `COMPLETED` |
| C | تحديث CI لأي syntax/toolchain لازم | نفس البوابات + CI remote | Android CI وDeep/Architecture Audit تنجح | commit B | `COMPLETED` |
| D | إضافة Compose Multiplatform 1.8.2 و`app-desktop` minimal | compile/package Desktop + Android gates | لا regression Android وDesktop artifact يبني | commit C | `COMPLETED`؛ راجع `GATE_DESKTOP_LINUX.md` |
| E | Linux runtime acceptance | launch/render/input/deterministic response/basic persistence | دليل runtime Linux محفوظ | commit D | `COMPLETED`؛ Linux `RUNTIME_VERIFIED` لنطاق محلي محدود |
| F | Windows CI/package وexternal runtime proof | package وchecks platform | evidence Windows أو `EXTERNAL_VERIFICATION_REQUIRED` صريح | commit E | `COMPLETED` للحزمة؛ runtime Windows `EXTERNAL_VERIFICATION_REQUIRED` |

## B: التغيير المقترن الأدنى

Kotlin 2.0+ يطلب Compose Compiler Gradle plugin في Android [1]؛ لذلك بدأت الخطوة بالعناصر Kotlin/KSP/Compose Compiler. أثبتت البوابة أن Room 2.6.1 ينهار مع KSP2 وأن Compose BOM 2024.01 لا يستطيع lint فيها قراءة metadata Kotlin 2.2، فأضيف Room 2.8.4 وCompose BOM 2025.08 كعلاجات مباشرة مثبتة. بقي Gradle وAGP وSDK وNDK وCMake ثابتة. قبل تعديل الكتالوج، تحفظ نسخة الملفات والمخرجات في checkpoint؛ بعد التعديل لا تنتقل خطوة C أو D إلا عند اجتياز جميع gates.

## gates بعد كل خطوة toolchain

```bash
python3 scripts/airi_toolchain_health.py
./gradlew :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid
./gradlew :app:compileDebugKotlin :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_cross_platform_health.py
python3 scripts/airi_core_health.py
```

تستخدم مهام release ميزانية الذاكرة التي تسمح بها البيئة. إن قتل daemon في `minifyReleaseWithR8` بسبب حد موارد sandbox، يسجل كـ`EXTERNAL_VERIFICATION_REQUIRED` محلياً ولا يخفف R8 أو إعدادات release؛ يظل CI البعيد هو دليل release الحاكم إلى أن تتاح بيئة محلية ذات مورد كافٍ.

## معايير قبول Desktop اللاحقة

لا تكفي `desktopTest` أو target declaration. تتطلب المرحلة D/E نافذة، render، إدخال keyboard/mouse، مسار response محدد، تخزين واستعادة سجل محدود، artifact، ودليل تشغيل على Linux. لا تصبح Windows أو Linux `RUNTIME_VERIFIED` من بناء JVM العام.

## المراجع

[1]: https://developer.android.com/jetpack/androidx/releases/compose-kotlin "Compose to Kotlin Compatibility Map"
