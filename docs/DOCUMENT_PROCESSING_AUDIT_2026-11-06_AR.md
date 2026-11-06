# تدقيق قدرات AIRI في معالجة وإنشاء الملفات والمستندات

**التاريخ:** 2026-11-06  
**الفرع:** `main`  
**الـcommit:** `c1d42d0f17ad814bc34f9b7c1c30b28691aae5c0`  
**نطاق التدقيق:** Android chat attachments، Project Library، document skills، agent tools، artifact generation، التصدير، واختبارات CI والأداء.

## النتيجة الحالية

لا يثبت المصدر الحالي أن AIRI قادر على معالجة وإنشاء **كل** امتدادات المستندات. العبارة الدقيقة هي: يستطيع AIRI استيراد الملفات عبر Android Storage Access Framework، قراءة النصوص والشفرة ضمن حدود، استخراج نص محدود من PDF وOOXML المعبأ، إجراء PDF literal extraction وOCR للصور/صفحات PDF، إنشاء مخرجات نصية/شفرة/Markdown/JSON، وتصدير المحادثة إلى Markdown/JSON/PDF. أما إنشاء DOCX وXLSX وPPTX وODT وODS وODP وEPUB، والتحرير البنيوي لهذه الصيغ، واستخراج الجداول/التنسيق/الصور/البيانات الوصفية بشكل موثوق، فليست قدرات مكتملة.

ينبغي عدم تسمية أي امتداد "مدعوماً" لمجرد أن MIME أو الامتداد مصنف في `AttachmentPolicy`. الدعم الفعلي يتطلب مساراً كاملاً: اختيار/استيراد، التحقق من النوع والحجم، استخراج أو تحويل، تمرير مضبوط إلى النموذج، إنشاء/كتابة، مشاركة، اختبار، واسترداد عند الفشل.

## ما هو موجود ومكتمل وظيفياً

### 1. الاستيراد والتخزين المحلي

`ProjectFileManager` يستقبل `content://` عبر `ContentResolver`، ينسخ الملف إلى تخزين التطبيق، يحسب SHA-256، يمنع التكرار داخل المشروع، يملك دورة حياة واضحة، ويستخدم حدوداً للحجم. هذا يتماشى مع Android Storage Access Framework الذي يعرّف `ACTION_OPEN_DOCUMENT` و`ACTION_CREATE_DOCUMENT` و`ACTION_OPEN_DOCUMENT_TREE` كمسارات معيارية لاختيار وفتح وإنشاء الملفات [1] [2].

توجد أيضاً حدود للمرفقات في `core-domain`، ومنع للتكرار حسب URI، وتنظيف للملفات الخاصة عند حذف الجلسة، وحدود لحجم الصور وفك الضغط، ومسار موافقة دائم لعمليات كتابة ملفات المشروع.

### 2. النصوص والشفرة

المسار النصي يغطي `txt`, `md`, `markdown`, `csv`, `json`, `xml`, `yaml`, `yml`, `log`, `kt`, `java`, `py`, `js`, `ts`, `html`, `css`, `sql` وبعض صيغ الإعداد. توجد قراءة محدودة، تقسيم إلى chunks، اختيار حسب الصلة والميزانية، ووسم المحتوى كمادة غير موثوقة قبل إدخاله إلى سياق النموذج.

`AgentFileWriter` ينشئ ملفات نصية محددة الامتدادات مع اسم مسطح، منع المسارات، حد للمحتوى، حد لعدد الملفات، كتابة مؤقتة ثم rename، ومنع الاستبدال الافتراضي. هذه حماية جيدة، لكنها ليست مولداً عاماً للمستندات.

### 3. PDF والصور

يوجد `DocumentAnalysisSkill` لمسارين منفصلين:

- استخراج نص PDF حرفي محدود وعدّ الصفحات عبر `PdfRenderer`.
- OCR محلي لصور أو صفحات PDF، مع ML Kit للنص اللاتيني وTesseract للنص العربي عند الطلب.

يوجد أيضاً تصدير محادثة إلى PDF باستخدام `android.graphics.pdf.PdfDocument`. هذا التصدير نصي وبسيط، وليس مولداً لتنسيق PDF احترافي أو مستند قابل لإعادة التحرير.

### 4. OOXML كمستخرج نص جزئي

`AttachmentContentExtractor` يدعم PDF وDOCX/XLSX/PPTX للاستخراج المحدود. بالنسبة لـOOXML، يقرأ XML داخل ZIP ويستخرج عقد `<t>`. توجد اختبارات DOCX اصطناعية واختبار حدود الناتج. هذا مفيد للملخصات، لكنه لا يثبت دعماً كاملاً للصور أو الجداول أو التعليقات أو الحقول أو الترتيب المرئي أو المصنفات متعددة الأوراق.

### 5. المخرجات الداخلية والتتبع

`ArtifactManager` يحفظ artifacts بإصدارات وhash وprovenance مرتبطة بالمشروع/المهمة/التنفيذ/الخطوة، مع صلاحيات قراءة scoped. هذا مكتمل كطبقة تخزين داخلية لمحتوى نصي، لكنه لا يساوي تصدير كل نوع ملف للمستخدم.

## ما هو جزئي أو مضلل حالياً

### 1. تصنيف MIME أوسع من الاستخراج الحقيقي

`AttachmentPolicy` يصنف `doc`, `docx`, `rtf`, `odt`, `xls`, `xlsx`, `ppt`, `pptx` كـDOCUMENT، لكن `AttachmentContentExtractor` لا يدعم إلا PDF وDOCX/XLSX/PPTX، ولا يوجد مسار عام في `ProjectFileManager` يمرر PDF/OOXML إلى المستخرج عند إنشاء preview. بدلاً من ذلك، `ProjectFileManager.extractPreview` يفتح الملف كما لو كان UTF-8، بينما سياسة المشروع تضع معظم الملفات الثنائية في `NOT_APPLICABLE`.

هذا يعني أن وجود الملف في Library لا يثبت أن AIRI يستطيع قراءته، وأن بعض ملفات PDF/Office قد تنتهي بحالة جاهزة للتخزين دون preview قابل للاستخدام أو مع فشل استخراج غير واضح للمستخدم.

### 2. DocumentReaderSkill متأخر عن مسار التحليل

`DocumentReaderSkill` يقرأ النصوص مباشرة، لكنه يعيد رسالة إرشادية لـPDF بدلاً من استخراج النص فعلياً. يوجد مسار أحدث في `DocumentAnalysisSkill`، لكن توجيه المهارة لا يضمن دائماً أن كل طلب قراءة PDF سيصل إلى المسار الأحدث.

### 3. إنشاء الملفات محدود بنص UTF-8

`ArtifactManager` يكتب `String` فقط، و`AgentFileWriter` يسمح بامتدادات نصية/شفرة فقط. لا توجد بنية binary artifact عامة ولا مولد حقيقي لـDOCX/XLSX/PPTX/ODF/EPUB. لذلك لا يمكن للنموذج إنشاء مستند Office صالح لمجرد طلب امتداده.

### 4. Chat export محدود

`ChatExporter` يدعم JSON وMarkdown fallback وPDF. `exportToJson` القديم يرجع false لأنه ينتظر مسار الوجهة، بينما مسار الواجهة الحالي يستخدم launcher لـPDF فقط. لا يوجد تصدير DOCX أو XLSX أو PPTX، ولا تحويل Markdown إلى هذه الصيغ.

### 5. الأداء المقاس ليس أداء النموذج

`tools/stability/local_model_performance_sim.py` نجح، لكنه يعلن صراحة أنه **contract simulation وليس native inference**. أرقام مثل `128000 synthetic tokens/sec` لا تصلح كمؤشر أداء LlamaBridge أو OCR أو استخراج المستندات. يلزم benchmark على جهاز/محاكي مع نموذج GGUF فعلي قبل ادعاء أداء حقيقي.

## ما يجب بناؤه

### المرحلة A — توحيد عقد الصيغ

إنشاء سجل مركزي immutable للصيغ يحدد لكل امتداد وMIME:

- فئة المحتوى.
- هل هو نصي أو حاوية/ثنائي.
- هل القراءة المحلية مدعومة.
- هل OCR أو renderer مطلوب.
- هل الإنشاء مدعوم.
- هل التحرير البنيوي مدعوم.
- حدود الحجم والصفحات/الأوراق.
- سبب الرفض القابل للعرض عند عدم الدعم.

يجب أن تستخدمه `AttachmentPolicy` و`ProjectFilePolicy` و`AttachmentContentExtractor` و`ArtifactManager` وواجهات التصدير، بدلاً من قوائم متفرقة.

### المرحلة B — فصل الاستخراج عن preview

إضافة `DocumentExtractionService` مشترك، يعمل على `InputStream`/ملف مؤقت بحدود، ويعيد نتيجة typed تتضمن:

- `ExtractedText`.
- `MetadataOnly`.
- `Unsupported`.
- `Failed`.

ويجب أن يستعمله `ProjectFileManager` عند الاستيراد و`DocumentProcessorAgent` و`DocumentReaderSkill`. لا يجوز قراءة ملف ثنائي بـUTF-8 كحل بديل.

### المرحلة C — توسيع القراءة الآمنة

الأولوية العملية:

1. PDF نصي + PDF ممسوح عبر OCR، مع حدود صفحات وذاكرة.
2. DOCX/XLSX/PPTX عبر parser موثوق أو طبقة OOXML typed، مع اختبار الجداول والأوراق والشرائح.
3. ODT/ODS/ODP وRTF وEPUB بموصلات/مكتبات واضحة أو إعلان عدم الدعم، لا تصنيف صامت.
4. ZIP/TAR وغيرها كحاويات: فحص traversal، حد عدد الإدخالات والحجم بعد فك الضغط، وعدم تمرير الملفات التنفيذية للنموذج دون سياسة.
5. ترميزات النص (UTF-8 وUTF-16 وBOM وغيرها) بدلاً من افتراض UTF-8 دائماً.

Apache Tika يبيّن أن تغطية Office وOOXML وODF وPDF وEPUB وRTF والحاويات تتطلب parsers متخصصة، وأن PDF يعتمد على PDFBox وOffice يعتمد على POI [3]. لا ينبغي إعادة تنفيذ parser عام عبر regex XML فقط.

### المرحلة D — إنشاء وتصدير ثنائيين

إضافة `BinaryArtifact` مع MIME وextension وhash وsize وprovenance، وكتابة ذرية إلى app-private storage، ثم `ACTION_CREATE_DOCUMENT` للمكان الذي يختاره المستخدم. بعد ذلك يضاف كل generator على حدة مع round-trip test:

- PDF: generator + render/text verification.
- DOCX: OOXML package + unzip/relationship/content verification.
- XLSX: workbook/sheet/cell verification.
- PPTX: presentation/slide/text verification.
- ODT/ODS/ODP: ZIP package manifest/content verification.
- EPUB: container/META-INF/package/content verification.

لا يكفي تغيير الامتداد أو MIME؛ الملف يجب أن يفتح في parser مستقل.

### المرحلة E — توافق النموذج والـcloud

`ModelCapabilityEngine` وpayload contracts يدعمان بعض inline data وPDF/images، لكن القدرة يجب أن تكون قراراً لكل نموذج/provider. يجب ألا يرسل AIRI DOCX/XLSX/PPTX إلى مزود لا يعلن دعمه؛ بدلاً من ذلك يمرره عبر الاستخراج المحلي ثم يرسل النص/الجداول المصرح بها، مع أثر يوضح التحويل.

### المرحلة F — اختبارات CI والأداء

إضافة اختبارات JVM للصيغ المركزية وgolden fixtures صغيرة، واختبارات Android للاستيراد عبر URI، واختبارات round-trip للمولدات، واختبار security للـZIP bombs وpath traversal، وbenchmark device profile للاستخراج/OCR/الإنشاء.

فشل CI السابق في التشغيل `41253193119` لم يكن دليلاً على فشل بناء المصدر: البناء وdebug package نجحا، لكن API 29 instrumentation انتهى بـ`adb: device offline`، كما فشلت بوابة `testDebugUnitTest`/`lintDebug` في ذلك التشغيل. محلياً لا يتوفر Android SDK صالح لتشغيل Gradle كاملاً. كذلك نجحت محاكاة الأداء التعاقدية، لكنها ليست native benchmark.

## ترتيب التنفيذ المقترح

1. إصلاح فحص مسار المرفقات ليستخدم matching دلالياً لا مسافات ثابتة، ثم إضافة اختبار للفحص نفسه.
2. إدخال سجل الصيغ المركزي مع الحفاظ على API الحالي، وإزالة القوائم المكررة تدريجياً.
3. ربط `ProjectFileManager` بالمستخرج المشترك للـPDF وOOXML ومنع UTF-8 fallback للثنائي.
4. توحيد `DocumentReaderSkill` مع المسار نفسه وإضافة نتائج typed ورسائل عربية قابلة للترجمة.
5. إضافة binary artifacts وPDF round-trip أولاً، ثم DOCX/XLSX/PPTX/ODF/EPUB حسب مكتبات Android المتاحة وحجم التطبيق.
6. إضافة فحوص النموذج/provider ثم تشغيل Gradle وCI وinstrumentation matrix من جديد.

لا أوصي بإضافة كل مكتبات Office دفعة واحدة قبل المرحلة الأولى؛ ذلك سيزيد حجم التطبيق ووقت البناء ومخاطر native/Android compatibility، ولن يحل تضارب المسارات المتعددة. كل مرحلة يجب أن تنتهي ببناء واختبارات وcommit مستقل.

## حالة البوابات التي شُغّلت

- `tools/stability/local_model_performance_sim.py`: **PASS**، محاكاة تعاقدية فقط.
- `scripts/airi_attachment_security_scan.py`: **PASS**.
- `scripts/runtime_state_simulation.py`: **19/19 PASS**.
- `scripts/connector_lifecycle_static_scan.py`: **13/13 PASS**.
- `tools/verify_core_changes.py`: **100/100 PASS**.
- `scripts/airi_attachment_flow_health.py`: **FAIL** بسبب تطابق مسافات ثابتة في الفحص، مع أن المصدر الحالي يحتوي مسار `sendMessageWithAttachments` فعلياً.
- `tools/final_runtime_integration_audit.py`: **FAIL** في عقدي skill/connector bridge dispatch، وهو فشل معماري مستقل يجب ألا يخلط مع دعم المستندات.
- Gradle المحلي: **غير قابل للحسم** بسبب بيئة Android SDK/Gradle المحلية، وليس بسبب إصلاح مصدر جديد.

## المراجع

[1]: https://developer.android.com/guide/topics/providers/document-provider "Android Storage Access Framework document provider"
[2]: https://developer.android.com/training/data-storage/shared/documents-files "Android access, open, and create documents"
[3]: https://tika.apache.org/3.3.0/formats.html "Apache Tika supported document formats"

## نتيجة المرحلة الأولى المنفذة بعد التدقيق

تم تنفيذ المرحلة الأولى من الخطة على المصدر الحالي:

- رُبط `ProjectFileManager.extractPreview` بـ`AttachmentContentExtractor` للصيغ المحلية المدعومة: PDF وDOCX وXLSX وPPTX.
- لم يعد الملف الثنائي يُقرأ تلقائياً كـUTF-8 عندما يملك المستخرج المحلي مساراً مناسباً.
- أصبحت نتيجة الاستخراج الفارغة حالة `FAILED` برسالة bounded بدلاً من نجاح مضلل.
- أصبحت سياسة المشروع تعتمد على MIME والامتداد؛ فإذا أعاد مزود Android `application/octet-stream` لملف PDF أو OOXML ما زال يدخل مسار الاستخراج الآمن.
- أضيفت اختبارات JVM لسياسة PDF وDOCX وامتداد PDF مع MIME عام.
- أُصلح `scripts/airi_attachment_flow_health.py` ليطابق البنية بعد تطبيع whitespace، لأن الفشل السابق كان false negative ناتجاً عن مسافات ثابتة لا عن مسار تشغيل خاطئ.

التحقق بعد التعديل: فحص مسار المرفقات **PASS**، فحص أمن المرفقات **PASS**، محاكاة الأداء التعاقدية **PASS**، محاكاة الحالة **PASS**، و`git diff --check` **PASS**. يبقى اختبار Gradle المحلي غير قابل للتشغيل بسبب غياب Android SDK، ولذلك يلزم اعتماد CI بعد الدفع لإثبات البناء واختبارات Kotlin الفعلية.

## نتيجة CI للـcommit 5115c5be والتحليل الجذري

نجحت تشغيلات `Deep Audit` و`Architecture Audit` و`Oracle`. فشل `Android CI` لسببين مستقلين. أولاً، فشل `Verify source contracts and localization` لأن `tools/final_runtime_integration_audit.py` كان يبحث عن استدعاء قديم حرفياً (`bridge.invoke(toolName, args)`) بينما المصدر الفعلي يستدعي جسر المهارات عبر متغير `bridge` مع `executionId`، وجسر الموصلات مع بقية سياق التنفيذ. لم يكن هذا فشل wiring فعلياً؛ كان فحصاً ثابتاً غير متزامن مع العقد الحالي. تم إصلاح الفحص ليطبع المسافات ويتحقق من الاستدعاءات الفعلية.

ثانياً، فشلت اختبارات `AgentLifecycleTrackerPanelTest` في API 29 وAPI 34 وباللّغتين لأن لوحة العرض كانت ترسم `projection.groups` فقط وتتجاهل `projection.taskTree`. لذلك كانت خطوة حقيقية مثل `Read the inbox` وأداة `gmail_read` تختفي عند فتح السجل. كما كان وصف أداة بلا تفاصيل يضيف فاصلة دلالية فارغة، مما كسر اختبار الوصول للوصف المتوقع. تم إصلاح المسارين في `AgentLifecycleTrackerPanel.kt`: تُعرض الشجرة recursively عبر نفس قائمة التتبع، ويُبنى الوصف الدلالي من أجزاء غير فارغة فقط.

ظهرت أيضاً رسائل `adb: device offline` وتأخر إقلاع emulator و`Failed to start Emulator console`، لكنها ليست السبب الوحيد ولا تبرر تجاهل فشل assertions؛ فالسجل أثبت أن الاختبارات وصلت إلى 42 اختباراً وفشلت assertions محددة في اللوحة. بعد دفع الإصلاح الجديد يجب انتظار Android CI للتأكد من زوال فشل UI ومن بقاء مشكلة المحاكي إن تكررت منفصلة.

التحقق المحلي قبل الدفع: `final_runtime_integration_audit` **PASS**، `verify_core_changes` **PASS**، فحص المرفقات **PASS**، ومحاكاة الأداء التعاقدية **PASS**. اختبار Gradle المحلي ما زال غير قابل للتشغيل بسبب غياب Android SDK، لذا يبقى CI هو مصدر إثبات Kotlin/Compose النهائي.
