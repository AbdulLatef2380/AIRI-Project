# Gate Desktop Windows: بناء الحزمة والتحقق الخارجي

## النطاق المثبت

ينفذ workflow `AIRI Desktop Windows` على `windows-2025` باستخدام JDK 17 وWiX Toolset، ثم يشغّل `:app-desktop:test` و`:app-desktop:packageMsi`. يثبت هذا أن الوحدة المشتركة وواجهة Compose Desktop وحزمة MSI تتوافق مع runner Windows. لم يجر workflow أي تشغيل مرئي للواجهة أو اختبار إدخال مستخدم؛ لذلك لا يثبت runtime Windows.

| القدرة في Windows | الحالة | الدليل |
| --- | --- | --- |
| Gradle wrapper Windows | `IMPLEMENTED` | أضيف `gradlew.bat` لاستدعاء `gradle-wrapper.jar` نفسه المستعمل في Linux. |
| اختبار adapter Desktop | `TESTED` | [AIRI Desktop Windows #32442555546](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32442555546) نجح في `:app-desktop:test`. |
| حزمة MSI | `BUILDS` | workflow نفسه نجح في `:app-desktop:packageMsi` ورفع artifact `airi-desktop-windows-msi`. |
| artifact Windows | `BUILDS` | GitHub Actions سجّل artifact غير منتهي الصلاحية بحجم 92,077,656 bytes وقت التحقق. |
| [MSI install/process smoke test #32446833717](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32446833717) | `PROCESS_VERIFIED` | ثُبّت `AIRI-1.0.0.msi` بصمت بنجاح، وسُجل launcher في `%LOCALAPPDATA%\\AIRI\\AIRI.exe`، وبقيت العملية حية خلال 8 ثوانٍ ثم أُزيلت الحزمة بنجاح. |
| [AIRI Android CI #32442548721](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32442548721) | `TESTED` | نجح مسار Android الكامل على revision الذي أضاف wrapper Windows. |
| [AIRI Deep Audit #32442548714](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32442548714) | `TESTED` | نجح تدقيق lint والنواة على revision wrapper. |
| [AIRI Architecture Audit #32442548730](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32442548730) | `TESTED` | نجح تدقيق البنية واتجاه التبعيات على revision wrapper. |
| نافذة وتفاعل واستجابة واستعادة | `EXTERNAL_VERIFICATION_REQUIRED` | يتطلب Windows host تفاعلياً لتشغيل النافذة وإدخال keyboard/mouse والتحقق من الاستجابة والسجل بعد restart. |

يبني Compose Multiplatform صيغ `.msi` و`.exe` على Windows فقط؛ لا يدعم plugin cross-compilation لحزم المنصات الأخرى. يحتاج `jpackage` إلى JDK 17 أو أحدث، ويوفر runner `windows-2025` JDK 17 وWiX Toolset في بيئته المنشورة. [1] [2]

## إصلاحات التوافق التي ظهرت في التحقق

أظهر أول تشغيل أن مسار `.github/workflows/oracle.yml.` غير صالح على Windows بسبب النقطة الختامية؛ أعيدت تسميته إلى `oracle.yml` من دون تغيير محتواه. ثم أظهر التشغيل التالي غياب `gradlew.bat`، فأضيف wrapper Windows مطابق للـwrapper الموجود. بعد هذين الإصلاحين نجح checkout وWiX وGradle والاختبارات وتغليف MSI ورفع artifact. أضيف smoke test لاحق يثبت MSI بصمت، يكتشف launcher من registry/install location، يشغله مدة محدودة، ثم ينظف التثبيت؛ نجح هذا الاختبار في runner Windows.

> **لا يعني تثبيت MSI أو بقاء العملية حية نجاح runtime Windows التفاعلي.** يثبت smoke test أن الحزمة والlauncher ومسار process يعملون في CI، لكنه لا يثبت render أو DPI أو input أو response أو persistence. تبقى واجهة AIRI Desktop على Windows `BUILDS` مع `PROCESS_VERIFIED` إلى أن يثبت اختبار قبول على جهاز Windows: launch، render، إدخال keyboard/mouse، رد محلي، وسجل يعاد تحميله بعد تشغيل جديد.

## قبول خارجي مطلوب

| الاختبار المطلوب | النتيجة المطلوبة |
| --- | --- |
| تثبيت أو تشغيل MSI | ظهور نافذة `AIRI Desktop` بلا خطأ Java أو Skiko. |
| إدخال المستخدم | كتابة طلب بلوحة المفاتيح وإرساله بـEnter وبزر Send. |
| الاستجابة | ظهور رسالة AIRI المحلية الحتمية بعد كل مسار إرسال. |
| الاستعادة | إغلاق التطبيق وتشغيله مع ظهور سجل الجلسة السابق. |
| سلوك النافذة | resize، focus، close، وclear local history تعمل بصورة سليمة. |

## المراجع

[1]: https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html "Native distributions | Kotlin Multiplatform Documentation"
[2]: https://github.com/actions/runner-images/blob/main/images/windows/Windows2025-Readme.md "Windows Server 2025 runner image"
