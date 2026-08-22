# عقد أدلة البحث

**الحالة:** `IMPLEMENTATION_COMPLETE` لحدود evidence في `ResearchAgent` لمسار DuckDuckGo Instant Answers. **`RUNTIME_VERIFICATION_PENDING`** لاختبار مزودات البحث على جهاز Android وشبكة حقيقية. لا يدعي هذا العقد source graph أو cross-check متعدد المصادر أو snapshot دائم.

## الغرض

تدخل نتائج البحث إلى LLM بوصفها **بيانات خارجية غير موثوقة** وليست أوامر. يضع `ResearchEvidencePolicy` حدّاً لحجم النص، وينظف محارف التحكم، ويقبل عنوان المصدر فقط إن مرّ عبر `BrowserNavigationPolicy` كعنوان HTTP(S) عام للقراءة.

| خطوة | السلوك المنفذ |
|---|---|
| البحث | `ResearchAgent` يستدعي DuckDuckGo Instant Answers بعد privacy gate |
| بناء evidence | `ResearchEvidencePolicy.fromSearchResult` يقيد النص إلى 3,000 حرف ويعطي citation ثابتاً |
| مصدر citation | يقبل URL العام فقط؛ localhost والنطاقات الخاصة وغير HTTP(S) لا تظهر في prompt |
| synthesis | evidence محاط بـ`<research_evidence trust="untrusted_external">` مع تعليمات صريحة بعدم اتباع أي أمر داخله |
| غياب المصدر | يعرض agent عدم وجود دليل قابل للاستخدام ولا يفتح متصفح المستخدم تلقائياً |

## الخصوصية والتحكم

عند `PRIVACY_MAXIMUM` لا يجري طلب شبكة، ويبقى الرد local-LLM مع وصف حدّ المعرفة. في المستويات الأخرى، تمر الاستجابة الخارجية كبينة مقيدة فقط. العنوان العام يصل إلى LLM كـcitation قابل للعرض، أما العنوان المحجوب فلا يعبر. لا تقوم طبقة البحث بتنزيل ملف أو تسجيل دخول أو إرسال نموذج أو فتح intent خارجي كحل تلقائي.

## الأدلة

| الدليل | التغطية |
|---|---|
| `ResearchEvidencePolicyTest` | public citation، حجب private URL، حد النص ورفض النص الفارغ |
| `:app:compileDebugKotlin` | ربط policy بـResearchAgent وBrowserNavigationPolicy |

## فجوات الإغلاق الصريحة

ما زال `ResearchAgent` يعتمد مصدر Instant Answer واحداً في المسار المنفذ. لا توجد بعد سياسة تنويع مصادر، score تناقض، source graph، snapshot دائم للصفحة أو واجهة citations قابلة للنقر. لا تعني citation في prompt تحققاً من صحة المحتوى؛ إنها تسمية منشأ محدود فقط. يظل الوصول إلى Brave/Jina والصفحات الحية مقيداً بعقود browser/search الأخرى ومفاتيح مزود صالحة.
