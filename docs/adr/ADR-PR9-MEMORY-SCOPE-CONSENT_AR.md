# ADR-PR9: نطاق الذاكرة والموافقة

- **الحالة:** مقبول للتنفيذ المحلي فقط
- **التاريخ:** 2026-10-09
- **النطاق:** PR-9.1 إلى PR-9.5

## القرار

تعتمد AIRI نموذج **Hybrid محدوداً**:

1. **SESSION هو النطاق الافتراضي والإلزامي** لسياق المحادثة وRAG والـfallback الزمني.
2. **PROJECT مسموح فقط عند تمرير `projectId` صريح** من الطلب، ولا يعبر إلى مشروع آخر.
3. **USER معطّل في هذه المرحلة**؛ لا يوجد بعد `ownerId` موثق ومربوط بهوية Firebase داخل صفوف Room، ولذلك لا يجوز إنشاء ذاكرة مستخدم عابرة للجلسات أو استرجاعها.
4. المفتاح المنطقي المستهدف عند إضافة user-scoped memory هو:
   `ownerId + projectId + sessionId + memoryScope + contentHash`
   ولا يجوز اعتبار `sessionId` وحده هوية مستخدم.

## الموافقة

- الذاكرة المحلية لا تحفظ محتوى طويل الأجل تلقائياً؛ الحفظ الدائم يتطلب طلباً صريحاً ويمر عبر admission policy.
- RAG المحلي يظل محدوداً بالنطاق الممرر إلى الاسترجاع، ويعامل النص المسترجع كبيانات غير موثوقة.
- Firebase memory sync **opt-in مستقبلي فقط وموقوف حالياً قسراً**. لا يكفي أن تكون preference `cloudSyncEnabled` صحيحة لتفعيل memory sync.
- لا تُرسل ذاكرة إلى السحابة قبل إثبات: consent، owner-only Firestore rules، outbox durable، tombstones، cursor قابل للاستئناف، idempotency، وحالات offline/restart.
- سحب الموافقة في هذه المرحلة يمنع أي عملية sync جديدة؛ مسح البيانات المحلية يبقى مساراً مستقلاً ويجب أن يشمل embeddings والـcontext والـoutbox عند إضافته.

## حدود الإطلاق

هذه الدفعة لا تعلن Firebase memory sync كميزة جاهزة، ولا تضيف صلاحيات سحابية، ولا تنفذ migration destructive. تبقى الذاكرة محلية، ويُرفض أي fallback عام يعبر الجلسات.

## أدلة القبول لهذه الدفعة

- كل fallback وRAG query يستقبل `sessionId` صريحاً.
- لا يوجد DAO query عام لاسترجاع محتوى الذاكرة من جميع الجلسات.
- USER memory لا تظهر في الاسترجاع قبل وجود owner identity موثقة.
- كتابة الذاكرة تنتظر نتيجة Room transaction قبل إرجاع `Stored`.
- Cloud memory sync موقوف خلف feature gate ثابت حتى اكتمال PR-9.7/PR-9.8.
