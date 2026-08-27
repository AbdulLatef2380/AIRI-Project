# AIRI — تقرير التقدم المدقق لنظام Agent Planning & Execution Trace

> **التصنيف الحالي: PARTIALLY_DONE / MITIGATED.** يثبت هذا التقرير ما جرى اختباره فعلاً داخل المصدر وCI، ولا يحوّل غياب جهاز ARM64 أو حساب موفر حي أو دليل RTL مرئي إلى نجاح افتراضي.

## النطاق المعماري المعتمد

يعتمد النظام على الطبقات القائمة بدلاً من بناء قناة موازية: يملك `ExecutionStatusBus` سجل trace محدوداً ومتسلسلاً، ويُنقل إليه سياق التنفيذ صراحة من `AgentLoop` أو من overload واعٍ بهوية التنفيذ في `AdaptiveGraphEngine`. يراقب `AgentPlanViewModel` السجل ويطبّق سياسة عرض نقية، بينما تعرض `AgentPlanContent` الحالة فقط ولا تنفذ عملاً وكيلياً أو تستنتج هوية التنفيذ من الواجهة.

| طبقة | النتيجة المثبتة | الحد المتبقي |
|---|---|---|
| ملكية التنفيذ | الأحداث المعروضة من حالة التنفيذ تحمل `executionId`، ويرفض lifecycle الخاص بالأداة الأحداث الفارغة أو المتقادمة. | ما زالت مسارات `SkillService` القديمة لا تمرر identity صريحة. |
| سجل trace | مخزن محدود بـ150 حدثاً، sequence متزايد، مع `actionId` و`durationMs` لأحداث الأدوات. | لا دليل بعد على process death أو rotation. |
| دورة حياة الأدوات | `AgentLoop` ينشر start ثم terminal وحيداً: completed أو failed أو cancelled، مع مدة آمنة. | منفذ graph يملك الآن overload بثلاثة معاملات؛ لا يوجد بعد producer tools فعلي يستخدمه. |
| الخصوصية | الحجب مركزي في trace وAgentTrace وإسقاط AppEvent إلى النشاط. لا تُعرض مخرجات الأداة الخام في trace. | يلزم تدقيق باقي producers وanalytics/crash sinks في دفعات مستقلة. |
| واجهة المستخدم | لوحة الخطة تعرض trace التنفيذ الحالي زمنياً، مع All/Planning/Steps/Tools/Issues وتفاصيل موسعة مُنقّاة وإيقاف/استئناف المتابعة وقفز إلى الأحدث. | لا دليل مرئي بعد على ARM64/RTL/font-scale أو اختبار Compose مخصص. |
| التوطين | أضيفت المعرفات الجديدة بالتوازي إلى ar/en/es/zh، وفحص parity النصي نجح. | يلزم دليل لقطة/قارئ شاشة واتجاه فعلي. |

## الدفعات والأدلة

| Commit | التغيير | نتيجة التحقق |
|---|---|---|
| `19befb7d` | احتفظ `ActivityEvent` بـ`executionId` وربط أحداث الحالة به. | Android CI `33090117930`، Deep `33090117913`، Architecture `33090117902`، Oracle `33090117983`: **success**. |
| `5dfc7afb` / `3b04224e` | أنشأ حجب trace وأصلح حجب قيم الحقول الحساسة بالاسم بعد كشف سيناريو cookie في الاختبار. | Android CI `33113695184`، Deep `33113695208`، Architecture `33113695300`، Oracle `33113695280`: **success**. |
| `e4559f2f` | أضاف lifecycle مملوكاً للأداة، sequence/action/duration، تمرير executionId إلى ToolDispatcher، overload واعياً بالـexecution في graph، وحجب AppEvent عند projection. | Android CI `33118602936`، Deep `33118602894`، Architecture `33118602895`، Oracle `33118602886`: **success**. |
| `380c0a54` | أضاف presentation policy وواجهة trace داخل لوحة الخطة وموارد اللغات الأربع. | Deep Audit `33120815009` كشف خطأ Compose حقيقياً؛ لا يعتمد هذا الرأس كنجاح. |
| `064f9034` | أصلح استدعاء `stringResource` غير المسموح داخل semantics lambda عبر حساب النص قبل lambda. | Android CI `33121217462`، Deep `33121217471`، Architecture `33121217381`، Oracle `33121217398`: **success**. |

## الاختبارات الانحدارية

تغطي اختبارات JVM إضافة الحدث للـtrace مع sequence وbounded eviction وتطبيع المدة السالبة؛ وتغطي lifecycle للأداة رفض executionId/actionId الفارغين أو المتقادمين ومنع البداية أو terminal المكرر؛ وتغطي سياسة العرض العزل حسب executionId، الترتيب، filters، وعدّ الأحداث الجديدة؛ كما تختبر إسقاط AppEvent عدم تسريب input أو cookie أو password أو API key إلى activity feed. أثبتت Android CI النهائي compile وunit/lint وrelease packaging وinstrumentation الموجودة ضمن سير العمل.

## الفشل الذي عولج من جذره

كشف `33120815009` خطأ تجميع في `AgentPlanContent.kt`: استُدعي `stringResource` داخل lambda غير composable خاص بـsemantics. لم تُخفَ التغطية ولم يُصنّف الرأس ناجحاً. نقل `064f9034` النص المترجم إلى قيمة محسوبة داخل composable قبل lambda، ثم اجتازت جميع بوابات CI الأربع.

## بوابات غير مغلقة

| البوابة | الحالة | السبب الدقيق |
|---|---|---|
| Structured planner validation | **OPEN** | لا parser/validator مثبت بعد لحالات JSON غير صالح أو خطة فارغة أو IDs مكررة أو cycles أو dependencies غير قابلة للحل أو حدود الخطوات. |
| SkillService legacy tool tracing | **OPEN** | المسارات القديمة تصدر events بلا executionId/actionId ومدة/terminal كاملة؛ لا يجوز إسنادها تخمينياً إلى واجهة نشطة. |
| Process recreation/recovery | **OPEN** | trace في الذاكرة عمداً؛ لم يُثبت بعد checkpoint/history مناسب للحالة المباشرة. |
| UI/RTL/accessibility runtime | **BLOCKED / REAL_DEVICE_ACCESS_REQUIRED** | لا يتوفر جهاز ARM64/محاكي Android محلي قادر على تقديم دليل لقطة وfont-scale وقارئ شاشة. |
| Local/cloud/provider runtime | **BLOCKED / EXTERNAL_PENDING** | لا تتوفر credentials أو تفويض provider حي للتحقق من local/cloud tools أو OAuth، ولا يُنشأ ادعاء نجاح من CI. |

## الخطوة التقنية التالية

تُغلق الدفعة التالية parser/validator لخطة التنفيذ قبل توسيع واجهة المستخدم أكثر، مع اختبارات invalid JSON وempty/duplicate/cycle/dependency/limit والدخول الفعلي إلى التنفيذ. بعد ذلك فقط يُراجع تصميم ledger الائتمانات M4 كعقد معاملات idempotent موحد؛ لا يُنفذ تعديل requestId جزئي.
