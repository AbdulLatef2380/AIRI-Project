# Phase 9 — Repository Cleanup Review

**التاريخ:** 13 نوفمبر 2026
**القرار:** `INVENTORIED / NO_DESTRUCTIVE_CLEANUP`

هذا التقرير يوثق المراجعة المحافظة للمستودع. لم تُنقل أو تُحذف أي ملفات تشغيلية أو تاريخية.

## النتائج

- تم فحص `docs/` و`reports/` و`attached_assets/` وتسجيل **387 ملفًا** في [manifest lineage](../REPOSITORY_DOCUMENT_LINEAGE.md).
- تم إنشاء [فهرس التقارير العشرة](../REPORTS_INDEX.md) مع آخر commit وموضوعه لكل تقرير.
- لم تُنقل ملفات `SKILL.md`؛ مواقعها الحالية تحت `skills/` بقيت دون تعديل.
- `.gitignore` يغطي `__pycache__/` و`*.py[cod]` وGradle/build outputs. لا توجد إضافة جديدة مطلوبة في هذه الجولة؛ `.gitignore` لا يعالج بأثر رجعي الملف المتتبع.

## التكرارات byte-identical

تم العثور على تسع مجموعات، وكلها بقيت محفوظة لأن بعضها تاريخي أو يحمل دلالة مصدر/زمن مختلفة:

| SHA-256 prefix | الملفات | القرار |
|---|---|---|
| `75713b853438f958` | `docs/MULTI_AGENT_DESIGN.md` و`attached_assets/MULTI_AGENT_DESIGN_1783397036647.md` | إبقاء canonical في docs ونسخة المصدر المرفق حتى موافقة مالك المشروع على الأرشفة. |
| `5630f93c6c4f00d5` | `docs/engineering_report_phase2_tasks19_35.md` ونسخة `attached_assets/` | إبقاء النسخة المرفقة كسلسلة مصدر تاريخية. |
| `84976a5fbdeee44d` | `docs/localization_missing_es.md` و`docs/localization_missing_zh.md` | عدم الدمج؛ التطابق الحالي يحتاج مراجعة دلالية للغة/الهدف قبل أي إعادة تسمية. |
| `59194fbb905e1c10` | `docs/verification_report_phase2_tasks19_35.md` ونسخة `attached_assets/` | إبقاء lineage التاريخي. |
| `991074b87803e875` | أربع نسخ من Blueprint في `attached_assets/` | إبقاء الطوابع الزمنية لأنها تمثل مرفقات مستقلة؛ لا حذف دون موافقة. |
| `f4d4e815a2d72963` | أربع نسخ من Capability 9 في `attached_assets/` | إبقاء الطوابع الزمنية كمصادر تاريخية. |
| `20787d0bf4368f14` | أربع نسخ من Dependency Trees في `attached_assets/` | إبقاء الطوابع الزمنية كمصادر تاريخية. |
| `b3bc40ce3d8aeb67` | نسختان من محادثة اللغة في `attached_assets/` | إبقاء المصدرين حتى مراجعة مالك المشروع. |
| `0dfc6eaec3871c94` | نسختان من handoff audit في `attached_assets/` | إبقاء المصدرين كسجل تاريخي. |

## ملف pyc المتتبع

` scripts/__pycache__/build_validation.cpython-312.pyc ` متتبع في Git. آخر lineage ظاهر هو commit `25cec2f2` بتاريخ 2026-06-03. لم يُحذف في هذه المرحلة لأن الطلب يشترط تقريرًا مستقلًا ومراجعة history/owner قبل الحذف. وجوده لا يعني أن `.gitignore` ناقص؛ الملف متتبع بالفعل، و`.gitignore` لا يلغي tracking.

**التوصية:** قرار مستقل من مالك المشروع: إما إزالة الملف مع commit واضح بعد التأكد من أن `build_validation.py` هو المصدر الوحيد، أو إبقاؤه لأسباب reproducibility تاريخية. لا تُتخذ الخطوة ضمن تنظيف P3 تلقائي.

## الملفات الثنائية وJAR/ZIP/BIN

| الفئة | أمثلة | القرار |
|---|---|---|
| Gradle state المتتبع | `.gradle/**/executionHistory.bin`, `fileHashes/*.bin`, `buildOutputCleanup/outputFiles.bin` | لا تُضاف قاعدة عامة ولا تُحذف آليًا؛ تحتاج قرارًا مستقلاً لأن الملفات متتبعة وقد تكون ناتجة عن تاريخ المستودع. |
| أدوات البناء | `gradle/wrapper/gradle-wrapper.jar` | احتفاظ؛ مورد تشغيل أساسي للـwrapper. |
| مكتبات runtime | `libs/kotlinx-coroutines-*.jar` | احتفاظ؛ تُراجع عبر dependency/supply-chain policy لا عبر حذف امتدادات. |
| أصل تاريخي | `attached_assets/AIRI-Project-architecture-refactor_1782708067209.zip` | احتفاظ؛ مرفق تاريخي، لا يُعامل كـbuild output. |

لم تُضف قواعد عامة لـ`*.jar` أو`*.zip` أو`*.bin`، ولم يحدث removal قبل فحص الغرض.

## فحص المراجع والشجرة

- لا توجد عمليات نقل أو حذف، ولذلك لم تنشأ روابط مكسورة بسبب Phase 9.
- تم فحص صيغ Markdown والروابط المرجعية أثناء الجرد، وتظل الروابط التاريخية الخارجية كما هي؛ لا يعاد تفسيرها على أنها دليل حالي.
- يجب تشغيل حارس Phase 9 قبل commit النهائي، ثم التأكد من `git status` و`git diff --check`.
