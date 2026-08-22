# برنامج تحويل AIRI إلى Product Release Candidate

## القرار والنطاق

يحوّل هذا البرنامج AIRI من **Release Candidate هندسي** إلى **Product Release Candidate** يمكن إعداده للتوزيع التجاري. نطاق العمل هو فرع `cp-foundation` فقط. يبقى `architecture-refactor` مرجعاً محمياً؛ لا يُعدّل ولا يندمج قبل أن يجتاز `cp-foundation` بوابات المنتج الجديدة.

> معيار الإنجاز ليس «ظهور واجهة لميزة ما»، ولا «نجاح build واحد». الميزة لا تعد مدعومة إلا إذا امتلكت عقداً واضحاً، ومسار تنفيذ قابلاً للوصول من المستخدم، وسياسة أمن، واختباراً، ودليل قبول مناسباً للمنصة.

## ما استُخرج من النقاش وخط الأساس

يركز البرنامج على تسع مجموعات مترابطة: الحماية والثقة، تنفيذ الذكاء الاصطناعي، الذاكرة وRAG، المهارات والوكلاء، الجدولة، المرفقات والوسائط، تجربة الدردشة والتصميم، جودة المنصات، وهندسة الإصدار التجاري. لا يشمل ذلك نسخ واجهات منتجات منافسة؛ بل بناء هوية AIRI فوق ما يعمل بالفعل في Android وDesktop والنواة المشتركة.

تثبت الشفرة الحالية أن AIRI لا يبدأ من الصفر. فـ`MemoryManager` يحدد نافذة محادثة مقيدة، ورفضاً للمحتوى الحساس، واستخراج حقائق فقط مع طلب ذاكرة صريح، وبحثاً دلالياً اختياريًا. كما يستدعي `ChatViewModel` RAG والمرفقات والمهارات في المسار الحقيقي. ويوجد scheduler يعتمد WorkManager مع حفظ الحالة وإعادة المحاولة. إلا أن مراجعة مسارات التنفيذ أظهرت فجوات منتج قابلة للإغلاق: المعرفة المختارة في الدردشة تعتمد حالياً صفوف الذاكرة الطويلة لا مكتبة معرفة مستقلة؛ scheduler موجود كبنية خلفية لكنه ليس أداة من الدرجة الأولى في المسار الوكيلي؛ و`SkillToolBridge` يفرض model access لكنه لا يطبق بعد `requiredPermissions` و`memoryAccess` المعلنين في عقد المهارة.

| القدرة | الحالة الحالية الموثقة | قرار البرنامج |
|---|---|---|
| الذاكرة | admission، حساسية، pruning، وsemantic retrieval موجودة. | تثبيت provenance، موافقة صريحة، delete/export، ومكتبة معرفة منفصلة. |
| RAG | `RagRetriever` موصول بمسار الدردشة. | إضافة أنواع مصادر، citation، lifecycle للمستند، وفصل session attachment عن knowledge. |
| المهارات | registry وtool bridge وتنفيذ بمهلة زمنية موجودة. | إنفاذ permissions/approvals/manifest/audit قبل توسيع الواجهة. |
| الجدولة | WorkManager وحفظ حالة وretry موجودان. | ربط جدولة المهمة بمحادثة/أداة/واجهة وإضافة ownership وconsent ونتيجة قابلة للعرض. |
| المرفقات | تخزين خاص، سياق نص غير موثوق، ورفض vision عندما لا تكون القدرة جاهزة. | توحيد policy وmetadata ومسح المحتوى وحدود الحجم وUX مصدر المرفق. |
| الصوت والنماذج | مسارات محلية وسحابية ومزودات صوت موجودة. | بطاقة قدرة لكل provider/model، وسيناريوهات offline/network/cancellation قابلة للاختبار. |
| Android وDesktop | Android CI وDesktop Windows MSI حققا أدلة بناء/اختبار. | قبول runtime منفصل لكل منصة؛ لا نقل دليل منصة إلى أخرى. |

## مقارنة معيارية موجزة

المرجع ليس «من يتشابه شكله مع AIRI»، بل كيف يتحقق منتج ناضج من قدراته. توثق Jan تطبيق سطح مكتب يجمع المحادثة والنماذج وموصلات MCP وواجهة API محلية، مع فصل عامل الوكيل عن تطبيق سطح المكتب [1]. وتعرض AnythingLLM نموذج تطبيق خاص يجمع RAG والوكلاء، مع تركيز على رحلة معرفة منخفضة الإعداد [2]. وتوثق Open WebUI عقوداً مركبة للمحادثة والمعرفة والأدوات والمزودات والأتمتة والإدارة، بما يوضح فائدة عقد موحد قبل زيادة الواجهات [3]. ويعرض LocalAI مزوداً محلياً بواجهة موحدة ومحركات قابلة للتبديل وقدرات صوت/رؤية/أدوات/MCP، ما يدعم تصميم capability profile بدلاً من افتراض أن كل نموذج يدعم كل زر [4].

| محور المقارنة | الدرس المنتجى لـAIRI | الاستجابة التنفيذية |
|---|---|---|
| محلي وسحابي | لا تجعل provider اختيار واجهة فقط. | `ModelCapabilityProfile` موحد ومختبر لstreaming، tools، vision، context، offline، وقيود التكلفة. |
| RAG والذاكرة | المعرفة ليست transcript طويلًا. | فصل `KnowledgeSource` و`MemoryFact` و`SessionAttachment` بنماذج تخزين وسياسات حذف مختلفة. |
| المهارات والأدوات | الوصف النصي ليس sandbox. | manifest وصلاحيات وموافقة وtimeout وaudit ونتيجة typed. |
| الوكيل والمهام | الخطط والـtool calls تحتاج إلغاء وحالة ثابتة. | lifecycle موحد: queued → running → approval-needed → cancelled/failed/succeeded. |
| تجربة الدردشة | الدردشة مركز المنتج وليست شاشة نص فقط. | composer متدرج، queue، stop، مصادر/مرفقات، history actions، وحالات فارغة وخطأ وإتاحة. |
| التشغيل | artifact لا يساوي منتجاً موزعاً. | signing، SBOM، hashes، release notes، privacy disclosures، smoke tests، وقناة تحديث. |

## هيكل التنفيذ وترتيب الأولويات

### P0 — حدود الثقة والحماية

يبدأ التنفيذ بحماية الحدود التي يمكن أن تتسبب في أثر خارجي أو كشف بيانات. يضاف حارس مركزي للمهارات في **المسار الإنتاجي** `SkillToolBridge` لا في طبقة غير مستدعاة. يرفض الحارس الاستدعاء عندما لا توجد الموافقة أو الصلاحية أو مستوى الوصول المناسب، ويعيد نتيجة واضحة قابلة للعرض والتدقيق. توضع الاختبارات حول حالات السماح والرفض والمهلة والتسجيل، ثم يصبح أي توسع في المهارات تابعاً لهذا العقد.

تراجع كذلك الحدود التالية في تسلسل قصير قابل للاختبار: الأسرار والسجلات، import/export، تنظيف المرفقات عند حذف الرسالة أو المحادثة، الملفات النموذجية، مسارات IPC/Remote Control، والـupdate metadata. لا يعني وجود حارس سابق أن هذه البنود معتمدة تلقائياً؛ لكل بند دليل مصدر أو build أو runtime مستقل.

| التسليم | الملفات/المسار المستهدف | الاختبار | الدليل |
|---|---|---|---|
| حارس تفويض المهارة | `ai/skills` و`SkillToolBridge` | unit tests للحالات deny/allow | `SOURCE_VERIFIED` |
| سجل قرار المهارة | Event/audit مقنن من دون أسرار أو محتوى حساس | test للـredaction | `SOURCE_VERIFIED` |
| نموذج خطورة المرفق | policy مشتركة قبل التخزين والإرسال | policy tests | `TESTED` |
| سجل حدود المنصات | تحديث حدود Desktop/Android/Remote | حارس حدود | `SOURCE_VERIFIED` |

### P1 — دورة تنفيذ الذكاء الاصطناعي الحقيقية

يوحّد AIRI دورة الطلب من composer إلى provider إلى streaming أو tool إلى persist أو cancellation. لا يُعلن عن دعم model أو modality إلا بعد بطاقة قدرة معلنة واختبار مسار. يتضمن ذلك local/cloud routing، fallback مكتوب السبب، إلغاء صالح أثناء streaming، معالجة خطأ لا تدعي نجاحاً، وإظهار معلومات capability للمستخدم قبل إرسال مرفق أو تشغيل صوت.

أول تعديل وظيفي بعد الحماية هو جعل **الجدولة قدرة مستخدم ووكيل حقيقية**. يستخدم `ScheduledJobOrchestrator` القائم، لكن يضاف عقد جدولة يمثل owner وsession وpayload مقنن وقيود شبكة والموافقة وnext run وlast outcome. ثم تضاف أداة scheduling محمية بالموافقة، وربط في composer/chat، وقائمة إدارة تسمح بالتعديل والإيقاف وتشغيل test preview. لا تستخدم الخدمة الحسابات أو الويب أو الإشعارات الخارجية من دون تفويض صريح.

### P2 — معرفة وذاكرة قابلة للسيطرة

تتحول المعرفة إلى كيان منفصل عن حقائق الذاكرة وسجل المحادثة. يملك مصدر المعرفة اسماً ونوعاً ومالكاً وسياسة retention وحالة فهرسة ومرجعاً للمصدر. يتمكن المستخدم من إضافته من المرفق أو إدخاله بـ`@`، ويرى ما استدعي بالفعل في الإجابة. تبقى الذاكرة طويلة الأجل مقصورة على facts المقبولة صراحةً؛ لا تترقى المرفقات أو كل رسالة إلى معرفة تلقائياً.

يتضمن تسليم P2 migration مدروساً واختبارات قبول وإزالة المصدر ومسح embeddings وتأثير الحذف على prompt. يضيف تقييم retrieval ثابتاً بمجموعة fixtures خصوصية بحيث يمنع regression في relevance أو تسريب محتوى بين الجلسات.

### P3 — المهارات والوكلاء والمرفقات

بعد الحارس، تنشأ مهارات AIRI كوحدات ذات manifest صغير: هوية وإصدار وناشر ووصف وparameter schema وصلاحيات ووصول ذاكرة/نموذج وخطورة وأثر خارجي. لا يشغّل إنشاء مهارة من المستخدم أي code أو connector تلقائياً؛ يبدأ بطلب موافقة، ثم skeleton، ثم preview للمدخلات/المخرجات والصلاحيات، ثم enabling صريح. وتصبح `/` لاختيار مهارة و`@` لاختيار معرفة طبقتي اقتراح فوق عقود حقيقية.

توحد المرفقات في عقد واحد يرافق النص أو الصورة أو الملف. يصف الحجم وMIME والاسم الآمن والتخزين الخاص وhash اختياري وحالة extraction وpolicy الإرسال للـprovider. يجب أن يفشل أي نوع غير مدعوم قبل بدء طلب النموذج، وأن يعرض السبب للمستخدم بدلاً من إرسال fallback وهمي.

### P4 — UX وهوية AIRI وإتاحة الاستخدام

يبنى design system صغير قبل تغييرات الشاشات المتفرقة: tokens للون والمسافة والحجم والحالات، عقد Dark/Light وRTL/LTR، نصوص accessibility، وحركات قصيرة تحترم تقليل الحركة. ثم تنفذ تحسينات الدردشة بالترتيب: composer متعدد الأدوات، حالة الإرسال/الإلغاء، queue، معاينة المرفقات، إجراءات المحادثة، إجراءات الرسالة، بطاقات الخطة القابلة للطي، الاقتراح التالي القائم على نتيجة حقيقية، ومراكز واضحة للذاكرة والمهارات والمهام والنماذج والتحكم المقترن.

تكون الهوية AIRI مستقلة عن واجهات المنتجات المرجعية: لا نسخ لأسماء أو تركيب ChatGPT أو Claude أو Manus، ولا حشو animation أو iconography لتغطية مسار ناقص. كل animation يشرح انتقال حالة حقيقي مثل الفهرسة أو streaming أو approval أو completion.

### P5 — الأداء والاستقرار وإزالة الدين التقني

تطبق مراجعة موجهة لمسارات الضغط فقط: main-thread I/O، cancellation leaks، `delay` غير المبرر، 상태 وهمية، callbacks فارغة، managers متكررة، compatibility hacks بلا سبب، والمسارات غير المستدعاة. لا يحذف code لمجرد الاسم أو التعليق؛ يلزم دليل call graph واختبار regression. يتم استبدال تعليق المنتج غير الواضح أو البصمة الاصطناعية بوثيقة قرار أو اسم مسؤول مختصر، لا بإزالة المعرفة المفيدة من الكود.

### P6 — الإصدار والتوزيع التجاري

ينشأ release manifest موحد يحوي revision وversion وartifacts وSHA-256 وSBOM وdependency verification ونتائج CI. يظل توقيع Android وWindows خارج المستودع. تتطلب Android حزمة AAB موقعة بمفتاح upload منفصل وتهيئة Play App Signing وتسجيل بصمات شهادات Play لدى OAuth/API providers واختبار Internal App Sharing [5] [6]. تتطلب Windows توقيع MSI والتحقق من التوقيع وقناة تنزيل ثابتة، مع قرار تجاري واضح بين Microsoft Store أو توقيع خارجي؛ إذ لا يلغي توقيع ملف جديد احتمال تحذير SmartScreen مباشرة [7].

## بوابات القبول

| البوابة | الحالة التي يمكن تحقيقها داخل المستودع | الدليل الخارجي الذي لا يدّعى داخلياً |
|---|---|---|
| Android | `BUILD_VERIFIED` و`TESTED` عبر CI والمحاكي | `RUNTIME_VERIFIED` على أجهزة/شبكات/ABI فعلية. |
| Windows | `BUILD_VERIFIED` و`TESTED` عبر MSI CI | `RUNTIME_VERIFIED` بعد تثبيت MSI موقع على Windows حقيقي. |
| Linux | `BUILD_VERIFIED` للحزمة والاختبارات | قبول runtime وطرق التوزيع الفعلية. |
| Remote Control | `IMPLEMENTED` و`TESTED` عبر emulator والحراس | Firebase/OAuth production وعمليات أجهزة حقيقية. |
| Local AI | `SOURCE_VERIFIED` و`TESTED` لكل model profile | أداء وذاكرة وحرارة على العتاد المستهدف. |
| الصوت والرؤية | `TESTED` للوحدات المتاحة | صلاحيات الجهاز، نموذج حقيقي، جودة واتصال واقعي. |
| الترخيص والبيع | `SOURCE_VERIFIED` للمصفوفة والـinventory | مراجعة قانونية وحقوق النماذج والعلامات التجارية. |

## مسار النشر الخارجي بعد إغلاق الكود

| الترتيب | إجراء النشر | المالك | شرط البدء | الدليل المطلوب |
|---:|---|---|---|---|
| 1 | تجميد release candidate ووضع tag | مالك الإصدار | CI أخضر وlicense matrix مراجعة | changelog وmanifest وhashes. |
| 2 | إعداد مفاتيح Android وPlay App Signing | مالك حساب Play | هوية الناشر وحساب Play | fingerprint وسجل إعداد بلا مفاتيح خاصة. |
| 3 | internal Android track | QA Android | AAB موقع | نتائج install/upgrade/voice/attachments/remote smoke. |
| 4 | توقيع MSI وتوزيع beta | مالك Windows release | هوية توقيع موثقة | SignTool verification وSHA-256 ونتيجة تشغيل. |
| 5 | Linux beta | مالك Linux release | حزمة موثقة | install/update/remove smoke على توزيعات محددة. |
| 6 | Firebase/OAuth production smoke | مالك البنية السحابية | projects وredirects وsecrets | pairing/auth/replay/revocation/deletion evidence. |
| 7 | privacy/legal/store disclosures | المنتج والقانوني | inventory ومصادر البيانات | سياسة خصوصية وإقرار store/licenses. |
| 8 | rollout محدود ومراقبة | المنتج وQA | إغلاق P0–P6 | crash/error/feedback rollback criteria. |

## مراجع المقارنة والنشر

[1]: https://www.jan.ai/docs "Jan Docs"
[2]: https://docs.anythingllm.com/introduction "AnythingLLM Introduction"
[3]: https://docs.openwebui.com/features/ "Open WebUI Features"
[4]: https://localai.io/ "LocalAI"
[5]: https://developer.android.com/studio/publish/app-signing "Android app signing"
[6]: https://support.google.com/googleplay/android-developer/answer/9842756?hl=en "Use Play App Signing"
[7]: https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation "SmartScreen reputation for Windows app developers"
