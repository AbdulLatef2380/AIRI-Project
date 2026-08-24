# الذاكرة وRAG

تملك هذه الحزمة سجل المحادثة المدعوم بـRoom، admission الذاكرة طويلة المدى، embeddings، والاسترجاع الذي يغذي سياق النموذج. وهي تعمل داخل AIRI Android على `cp-foundation`؛ لا تدّعي هذه الوثيقة مزامنة cloud أو classifier كامل للبيانات الحساسة.

## الحدود ومصادر الحقيقة

| نوع البيانات | المالك والحد |
|---|---|
| سجل المحادثة | history محلي مقيد بالجلسة. حذف session يزيل الصفوف والملفات التابعة وفق manager المالك. |
| الذاكرة الدائمة | `MemoryAdmissionPolicy` يرفض المحتوى العابر والضخم والحساس. الحقائق durable تحتاج مسار admission صريحاً؛ لا تُستنتج هوية أو صلاحية أو حقيقة من نص المحادثة. |
| المعرفة | معرفة المشروع وملفاته المدارة ليست صفوف memory عشوائية. تدخل retrieval عبر ownership وسياق المشروع، ويزيل تغيير/حذف الملف المعرفة القديمة وفق العقد المخصص. |
| embeddings وRAG | البحث الدلالي مقيد بالجلسة المناسبة، ثم يمر `RagRetriever` بحدود scope/privacy/prompt-safety وترتيب محدود. يحقن `ProjectContextResolver` فقط موارد المشروع المملوكة ضمن budget. كل النص المسترجع **بيانات تاريخية غير موثوقة** لا تعليمات. |
| الحذف | erase local data يمسح بيانات AIRI المحلية المدارة فقط؛ لا يعلن حذف حساب Firebase أو مزود أو تنزيل نموذج خارج نطاقه. |

## الخصوصية والحدود

التصنيف الحساس heuristic وليس بديلاً عن PII classifier كامل. لا ينبغي أن تدخل secret أو URI مصدر أو محتوى مرفق غير محدود إلى memory أو prompt أو evidence. لا تحل RAG محل permission أو approval أو project ownership.

## التحقق

الحارس والاختبارات تغطي admission، session-scoped retrieval، RAG prompt framing، ترتيب/dedup بعد scope filters، والعزل في fixtures ذات الصلة. يعلن المصدر Room schema v9 مع migrations مخصصة، لكن migration/performance/accessibility على real device، وفهارس embedding الحقيقية، وسلوك restore عبر إصدار فعلي تبقى `RUNTIME_VERIFICATION_PENDING`.
