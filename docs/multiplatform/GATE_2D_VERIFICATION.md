# Gate 2D: عقود التخطيط المشتركة

## النطاق

نقل هذا gate **عقود التخطيط الخالصة فقط** إلى `core-domain/commonMain`:

| العقد | الموقع المشترك | المستهلكون الذين بقوا في Android |
| --- | --- | --- |
| `ActionPlan` | `com.airi.core.planning` | حلقة الإدراك والتنفيذ وامتدادات الخطة. |
| `AgentGoal` | `com.airi.core.planning` | مولد الخطة وvalidator وrecovery. |
| `PlanStep` | `com.airi.core.planning` | generator و`CommandRouter` وruntime graph. |

تم حذف التعريفات القديمة من `app/src/main` وتحويل المستهلكين إلى النواة المشتركة، ولذلك لا توجد نسختان للنماذج. بقي `PlanGenerator` و`CommandRouter` وruntime graph في Android لأن توليد JSON والسجلات وتنفيذ أوامر accessibility حدود منصة حقيقية.

## دليل الحدود

| الفحص | النتيجة |
| --- | --- |
| `:core-domain:desktopTest` | `BUILDS` — اختبارات عقود التخطيط المشتركة نجحت على JVM Desktop. |
| `:core-domain:compileDebugKotlinAndroid` | `BUILDS` — النواة تبني كاعتماد Android. |
| `:app:compileDebugKotlin` | `BUILDS` — مستهلكو Android يترجمون ضد الحزمة المشتركة. |
| `airi_platform_dependency_scan.py` | `TESTED` — 824 ملفاً، 134 مرشح مشاركة؛ العقود المشتركة بلا إشارات Android/JNI/JVM. |
| `airi_cross_platform_health.py` | `TESTED` — صفر أخطاء leakage. |
| `airi_toolchain_health.py` | `TESTED` — اكتشف ستة ملفات Kotlin في `commonMain` بلا API منصة محظور. |
| [AIRI Android CI](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32437378361) | `TESTED` — نجح بناء واختبار `core-domain` ثم debug وunit/lint وrelease وinstrumentation والتحقق من مكتبة Android native. |
| [AIRI Deep Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32437378415) | `TESTED` — نجح lint وتحقق النواة. |
| [AIRI Architecture Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32437378371) | `TESTED` — نجح تدقيق بنية التبعيات بعد نقل العقود. |

## الحالة الدقيقة

عقود التخطيط المشتركة `BUILDS` على JVM وAndroid، مع تحقق CI كامل من Android بعد النقل. هذا لا ينشئ دعماً لمنتج Desktop، ولا ينقل تنفيذ الأوامر أو مزودي النماذج أو التخزين. سيستخدم Desktop هذه العقود لعرض خطة المستخدم، ثم يحصل على adapters حقيقية منفصلة للإدخال والتخزين والاستجابة.
