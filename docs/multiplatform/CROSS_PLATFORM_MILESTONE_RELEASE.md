# ملخص ميلستون AIRI متعدد المنصات

## قرار الميلستون

أُغلق ميلستون **أساس AIRI متعدد المنصات** على فرع `cp-foundation` عند commit `80341a9a4b6d7b100a999251c268e59a67122970`. ظل فرع `architecture-refactor` عند `1027dee20511b294437c4f47f08e9c2f54050eaf` من دون تعديل، وتبقى نقطة الرجوع `cp-toolchain-baseline` عند `eb4714d244465a8e462b7d30ddb3fb30f865ec4e`.

لا يعني إغلاق هذا الميلستون اندماج الفروع أو إعلان إصدار تجاري شامل. يعني أن النواة المشتركة المحدودة وCompose Desktop Linux وWindows MSI وقرارات Web الأمنية اجتازت أدلتها المحددة في هذه الوثائق.

| نطاق المنتج | الحالة النهائية | الدليل الحاكم |
| --- | --- | --- |
| AIRI Android المرجعي | `RUNTIME_VERIFIED` بصورة جزئية محددة | Android CI الحالي نجح ببناء debug/release وunit/lint وinstrumentation والتحقق native. |
| `core-domain` | `TESTED` و`BUILDS` للنطاق الحالي | سياسات الذاكرة والمرفقات وعقود التخطيط تبني وتختبر على Android وJVM Desktop. |
| AIRI Desktop Linux foundation | `RUNTIME_VERIFIED` لنطاق محلي محدود | نافذة حقيقية، keyboard/mouse، استجابة حتمية، سجل واستعادة بعد restart، وحزمة DEB. |
| AIRI Desktop Windows | `BUILDS` و`TESTED` | runner Windows نجح في اختبارات Desktop وMSI artifact؛ runtime الفعلي `EXTERNAL_VERIFICATION_REQUIRED`. |
| AIRI Web | `PLANNED` | مسار Wasm/Compose وحدود الأمن موثقة؛ لا يوجد target أو artifact أو قبول متصفح. |

## ما تم تنفيذه

أُضيفت وحدة `core-domain` متعددة المنصات، ثم نُقلت إليها `MemoryAdmissionPolicy` و`MemoryTextNormalizer` و`AttachmentPolicy` وعقود `ActionPlan` و`AgentGoal` و`PlanStep`. حدّثت Kotlin/KMP إلى 2.2.21 مع KSP 2.2.21-2.0.5 وRoom 2.8.4 وCompose Compiler plugin 2.2.21، بينما بقي Gradle وAGP وSDK/NDK المرجعية مضبوطة كما في baseline.

أضيفت `app-desktop` كتطبيق Compose مستقل يستعمل النواة المشتركة من دون Android APIs أو JNI أو provider شبكي. ينتج Linux `.deb` وWindows `.msi`. صحح مسار portability أيضاً اسم workflow غير الصالح في Windows وأضيف `gradlew.bat` لاستدعاء wrapper المشروع نفسه في runner Windows.

| الدليل | النتيجة |
| --- | --- |
| Linux runtime | راجع `GATE_DESKTOP_LINUX.md`: نافذة AIRI، إدخال keyboard وmouse، Send وEnter، عرض response، وإعادة تحميل السجل. |
| Windows package | راجع `GATE_DESKTOP_WINDOWS.md`: [AIRI Desktop Windows #32442555546](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32442555546) نجح في `:app-desktop:test` و`:app-desktop:packageMsi` ورفع artifact MSI. |
| Android CI الحالي | [AIRI Android CI #32444498485](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32444498485) نجح. |
| Deep Audit الحالي | [AIRI Deep Audit #32444498523](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32444498523) نجح. |
| Architecture Audit الحالي | [AIRI Architecture Audit #32444498495](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32444498495) نجح. |

## بوابات الإصدار المحلية

نفذت البوابات التالية بنجاح على Linux مع JDK 17 وAndroid SDK المحلي، وكل مهمة Gradle كبيرة في عملية منفصلة لتجنب حد ذاكرة البيئة.

```bash
:core-domain:desktopTest :core-domain:compileDebugKotlinAndroid
:app:compileDebugKotlin
:app:testDebugUnitTest
:app:assembleDebugAndroidTest
:app-desktop:test :app-desktop:packageDeb
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_cross_platform_health.py
python3 scripts/airi_toolchain_health.py
```

أنتج `:app-desktop:packageDeb` الملف `airi_1.0.0-1_amd64.deb` بحجم 84,920,896 bytes في آخر تحقق محلي. نجحت `verify_core_changes.py` في 41/41 فحصاً، و`security_scan.py` في 8/8، ولم يجد حارس الحدود أي `commonMain` leakage.

## الحدود الباقية والخطوة التالية

> لا تنقل نجاحات Android أو Linux إلى Windows أو Web. لكل target دليل مستقل، ولا يصبح package غير مشغّل `RUNTIME_VERIFIED`.

| البند | الحالة | شرط الإغلاق |
| --- | --- | --- |
| Windows runtime | `EXTERNAL_VERIFICATION_REQUIRED` | تشغيل MSI على Windows: launch/render، keyboard/mouse، response، persistence/restart، resize/focus/close. |
| Web | `PLANNED` | Gate Wasm مستقل يبدأ بفحص `commonMain` وartifact ثم قبول browser وأمن CORS/CSP/auth/storage. |
| cloud/local models على Desktop | `PLANNED` | provider/runtime adapters واختبارات credentials أو hardware مستقلة. |
| Desktop auth/attachments/skills/scheduler/voice | `PLANNED` | adapter لكل قدرة مع policy وpermissions وacceptance على النظام المعني. |
| Web local runtime | `BLOCKED` | دراسة WASM/WebGPU وأداء وترخيص وsecurity منفصلة؛ لا يوجد JNI في المتصفح. |

تظل المصفوفة التفصيلية في `PLATFORM_MATRIX.md`، وخارطة Web في `WEB_FEASIBILITY_DECISION.md`، وسجل مخاطر toolchain في `TOOLCHAIN_RISK_REGISTER.md`.
