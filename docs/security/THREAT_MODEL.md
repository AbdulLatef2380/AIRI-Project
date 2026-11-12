# AIRI Core Threat Model

## النطاق والأصول

| الأصل | قيمة الحماية | موقعه المتوقع |
|---|---|---|
| محادثات وذاكرة المستخدم | خصوصية وسلامة | Room محلياً |
| ملفات المرفقات | خصوصية وسلامة التخزين | `filesDir/attachments` |
| مفاتيح مزودي الذكاء وOAuth | سرية وقدرة تنفيذ | تخزين مفاتيح آمن وOAuth PKCE |
| تنفيذ الوكيل | سلامة الأفعال والتحكم | AgentLoop، Skills، Tools، Connectors |
| النموذج المحلي | سلامة توافر الجهاز والنتيجة | ملفات النموذج وJNI |
| بيانات المزود السحابي | خصوصية النقل والتحكم | adapter مزود مهيأ صراحة |

## حدود الثقة

```text
User input / attachment / web or connector data
                │ untrusted
                ▼
Composer + attachment policy + prompt boundaries
                │ validated metadata / explicit permission
                ▼
Agent execution, skills, tools and providers
                │ minimized request
                ▼
Local store or configured external provider
```

يُعامل نص المرفقات والسياق المسترجع من RAG والمحتوى الخارجي كبيانات غير موثوقة. لا تصبح تعليمات تنفيذ تلقائياً لمجرد وصولها إلى النموذج.

## التهديدات والضوابط

| التهديد | الضابط الموجود في المصدر | حالة الدليل | التحقق الخارجي المتبقي |
|---|---|---|---|
| تسريب سر في المستودع | `tools/security_scan.py` يفحص الأسرار عالية الثقة؛ لا تُسجل مفاتيح المزود الخام. | **BUILD_VERIFIED** | مراجعة secrets في CI وإعدادات نشر المؤسسة. |
| قراءة أو حذف مسار مرفق خارج التطبيق | تحقق حجم/نوع، نسخ إلى التخزين الخاص، عدم حفظ URI المصدر، وحذف يطابق اسم الملف فقط. | **BUILD_VERIFIED** | اختبار fuzzing لمنتقيات ملفات على أجهزة فعلية. |
| حقن prompt عبر ذاكرة أو ملف نصي | حد للمحتوى ووسم صريح للبيانات غير الموثوقة في RAG والمرفقات. | **SOURCE_VERIFIED** | تقييم adversarial للمزود والنموذج المحدد. |
| استجابة متأخرة بعد الإلغاء | بوابة جيل تنفيذ تقبل callbacks للجيل النشط فقط. | **BUILD_VERIFIED** | قياس تحت انقطاع شبكة ومزود فعلي. |
| OAuth state/code interception | state registry وربط PKCE S256 واستهلاك state مرة واحدة. | **BUILD_VERIFIED** | بيانات اعتماد OAuth ونطاقات redirect الإنتاجية. |
| مكون Android مكشوف | فاحص الأمان يتحقق من FileProvider غير المصدّر وURI grants. | **BUILD_VERIFIED** | مراجعة manifest النهائي بعد أي إضافة SDK أو deep link. |
| تنفيذ مهارة أو موصل غير موثوق | فصل المهارة عن الموصل، والتحقق من الصلاحية/الحالة قبل التنفيذ. | **SOURCE_VERIFIED** | مراجعة manifests المهارات الخارجية وسياسة السوق. |
| أثر خارجي من وكيل | مسارات التنفيذ تتطلب قرار صلاحية؛ لا تفترض AIRI أن النموذج يمنح ذاته الامتيازات. | **SOURCE_VERIFIED** | سيناريوهات فعلية لكل موصل أو أداة تمنح أثراً خارجياً. |
| حقن تعليمات في صفحة ويب أو مرفق أو RAG أو مهارة مثبتة من المستخدم | محتوى هذه المصادر يُسلسل داخل bounded JSON envelope؛ تُحجب أنماط شائعة من المفاتيح/الرموز، وتُضاف قاعدة صريحة بعدم اعتبار هذه البيانات تعليمات. | **CODE_ADDED; TEST_PENDING** | لا يثبت التسلسل امتثال النموذج؛ يلزم red-team على كل model/provider ومسار tool مع بقاء authorization مستقلاً. |
| SSRF عبر مصدر مجتمع أو endpoint مهارة ديناميكية | تحقق URL عام/HTTPS، DNS يرفض الإجابات الخاصة/المختلطة، والـredirects مرفوضة. | **CODE_ADDED; TEST_PENDING** | يلزم controlled DNS rebinding وredirect runtime test على Android/CI. |
| أمر محقون يصل إلى terminal | policy argv/path allowlist مغطاة بفِكسشرات؛ shell execution محجوب في Release إلى حين OS-level isolation. | **DEBUG_POLICY_ONLY; RELEASE_BLOCKED** | لا يُعد `SandboxExecutor` عزلاً عن نظام التشغيل؛ FD inheritance وTOCTOU وprocess-tree على الجهاز غير مثبتة. |

## مبادئ التنفيذ الإلزامية

1. لا يُعامل محتوى ويب أو مرفق أو RAG كتعليمات موثوقة.
2. لا تدخل مفاتيح أو رموز أو رسائل خام حساسة إلى logs أو exports.
3. كل موصل جديد يحتاج نطاق وصول محدد، تخزين سر آمن، وإلغاء/فشل قابل للتفسير.
4. كل نوع مرفق جديد يحتاج سياسة حجم وتخزين خاص وتنظيف عمر حياة.
5. لا تضف مكوناً مصدّراً أو cleartext traffic بلا مبرر واختبار مراجعة manifest.
6. يجب أن يطلب الفعل الخارجي ذي الأثر موافقة ملائمة قبل التنفيذ.

## حدود هذا النموذج

هذا نموذج مصدر وCI، وليس تقرير اختبار اختراق أو شهادة امتثال. فحص الشبكة، مفاتيح الإنتاج، الحسابات الخارجية، النسخ الاحتياطية من Android، وسلوك كل مزود حقيقي عناصر **EXTERNAL** حتى تُختبر في بيئة إنتاج معتمدة.

### Phase 3.4 supplemental boundary

الـJSON envelope وredaction يقللان خلط التعليمات بالبيانات وتسرب أنماط مفاتيح معروفة، لكنهما **ليسا** حدّ تفويض ولا ضماناً ضد كل تسريب أو امتثال النموذج. كل tool/connector/terminal request يظل خاضعاً لسياسة runtime مستقلة. حتى إثبات عزل نظامي جديد، تبقى أوامر shell في Release معطلة بواسطة `SandboxIsolationPolicy`؛ نجاح unit tests وحده لا يرفع هذا الحاجز.
