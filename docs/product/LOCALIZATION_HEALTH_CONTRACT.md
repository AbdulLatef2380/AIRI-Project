# عقد صحة الترجمة

**الحالة:** `IMPLEMENTATION_COMPLETE` لتكافؤ القيم المترجمة وفحص القيم الإنجليزية الظاهرة في موارد العربية والإسبانية والصينية. **`RUNTIME_VERIFICATION_PENDING`** لمراجعة لغوية بشرية كاملة ولقطات Android RTL/LTR وTalkBack. لا يخفي العقد مفاتيح مفقودة أو يضيف fallback صامتاً.

## المشكلة المغلقة

أظهر `scripts/airi_localization_health.py` وجود 252 قيمة واجهة مرجح أنها غير مترجمة: 126 في `values-es` و126 في `values-zh`. كانت هذه قيماً موجودة لكنها تطابق الإنجليزية، وبالتالي لم يكن فحص key parity وحده كافياً لإثبات جودة ترجمة المنتج.

## المسار المنفذ

> English resources → candidate generation with strict JSON and placeholder validation → human technical-label overrides → conservative XML application → XML parse + strict health check + Android compilation

تعتمد المرشحات على نموذج ترجمة منظم، ثم يتحقق السكربت من تطابق مجموعة المفاتيح ومن placeholders قبل كتابة ملف مرشح. لا يطبق `apply_localization_candidates.py` أي عنصر تغير محلياً بعد التوليد أو لا يطابق مورد الإنجليزية، ويحافظ على XML المحيط بدلاً من إعادة تنسيق ملف الموارد كله.

| دليل | النتيجة |
|---|---|
| `scripts/airi_localization_health.py --strict` | `likely_untranslated_values=0` |
| تحقق المرشحات | 126 إسبانية + 126 صينية، بلا placeholder أو key mismatch |
| `:app:compileDebugKotlin` | نجح بعد تطبيق XML |

## حدود الترجمة

يحافظ العقد على أسماء المنتجات والـprovider والمصطلحات التقنية مثل AIRI وGitHub وOAuth، لكنه يترجم labels المحيطة بها مثل access key أو legacy. لا يمثل الفحص الآلي حكماً أسلوبياً أو ضماناً أن كل سلسلة Compose hard-coded نقلت إلى الموارد؛ يبقى ذلك ضمن مراجعة UI وإتاحة الوصول اللاحقة.

## الملفات

| ملف | الدور |
|---|---|
| `scripts/airi_localization_health.py` | يرصد قيماً محلية تطابق الإنجليزية بشكل مرجح |
| `scripts/generate_localization_candidates.py` | ينشئ مرشحات منظمة مع validation، ولا يغير الموارد |
| `scripts/apply_localization_candidates.py` | يطبق مرشحات صحيحة فقط بعد التحقق من المصدر |

## فجوات الإغلاق الصريحة

ما زالت لغة الواجهة تحتاج اختباراً بصرياً على أجهزة ضيقة/واسعة، وتدقيق عربية حقيقية مع IME وRTL، ومراجعة بشرية كاملة للإسبانية والصينية والسياق الثقافي، وقراءة TalkBack لعناصر Compose. هذه حدود تحقق تشغيلية، لا مبرر لإعادة إدخال نص إنجليزي متطابق مع المورد المرجعي.
