# عقد Database Lab

**الحالة:** `IMPLEMENTATION_COMPLETE` لمسار فحص قاعدة Room المحلية للقراءة فقط داخل Developer Center. **`RUNTIME_VERIFICATION_PENDING`** لتشغيل الواجهة على جهاز Android حقيقي وقياس الاستعلامات على قواعد كبيرة. لا توجد كتابة SQL أو migration أو backup أو اتصال خارجي في هذا العقد.

## المسار الحي

> `Developer Center → Database tab → DatabaseLabManager → DatabaseLabQueryPolicy → AiriDatabase.openHelper.readableDatabase → bounded UI result + AuditRepository`

يعرض التبويب استعلاماً افتراضياً لمخطط SQLite ويشغّل الاستعلام في IO. تعرض النتيجة أسماء الأعمدة والصفوف، مع حد 100 صف و1,000 حرف للخلية، ولا تسجل النتائج أو SQL الخام في سجل التدقيق.

| طبقة | الضمان المنفذ |
|---|---|
| `DatabaseLabQueryPolicy` | يقبل `SELECT` و`EXPLAIN QUERY PLAN SELECT` وschema PRAGMA المحدودة فقط |
| المدخلات | حد 2,000 حرف؛ يمنع التعليقات وsemicolon وmulti-statement |
| المنع | يرفض DML وDDL وATTACH/DETACH/VACUUM/transactions وPRAGMA المتغير |
| `DatabaseLabManager` | يستخدم `readableDatabase` ويقيد الصفوف والقيم قبل العرض |
| التدقيق | يسجل outcome وعدد الصفوف/الزمن/طول الاستعلام فقط، من دون query أو data |

## الأمان والخصوصية

المدير لا يملك API للكتابة. ويمنع policy أنماط SQL التي تغير قاعدة البيانات حتى لو وصل النص من Compose مباشرة. لا يمنع هذا قراءة البيانات الموجودة للمستخدم المحلي عبر Developer Center؛ لذلك تبقى الشاشة مساراً تقنياً محلياً لا feature مشاركة أو export. لا يرسل query أو results إلى نموذج أو provider أو جهاز آخر.

## الأدلة

| الدليل | التغطية |
|---|---|
| `DatabaseLabQueryPolicyTest` | SELECT/schema PRAGMA المسموحة؛ رفض writes/comments/multi-statement/oversize |
| `:app:compileDebugKotlin` | تكامل Room وaudit وCompose Developer Center |

## فجوات الإغلاق الصريحة

لا يوجد query history يعرض SQL، query planner مرئي، جدول schema كامل، backup/restore، export، role-based developer access، أو مسار write مرتبط بالموافقات الدائمة. هذه الأمور ليست مفعلة ضمناً: كتابة البيانات لا تزال محظورة بالكامل من Database Lab حتى يتوافر عقد منفصل للموافقة والاستئناف والتدقيق والنسخ الاحتياطي.
