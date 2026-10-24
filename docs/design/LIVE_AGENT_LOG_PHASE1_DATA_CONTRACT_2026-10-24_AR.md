# عقد بيانات Live Agent Log — المرحلة الأولى

**النطاق:** توحيد إسقاط بيانات التنفيذ وتثبيت العقد والاختبارات فقط.
**الفرع:** `main`
**حالة الواجهة:** لم تتغير في هذه المرحلة.
**حالة التنفيذ:** محلي فقط، دون commit أو push.

## الخلاصة التنفيذية

أثبت الفحص أن AIRI لا يملك مصدرين مستقلين للحقيقة. المسار الأساسي الحالي هو:

```text
Runtime / Orchestrator
        ↓
ExecutionStatusBus
        ↓
ExecutionTraceBuffer
        ↓
ExecutionStatusBus.trace
        ↓
AgentPlanViewModel / ChatScreen
        ↓
UnifiedExecutionTraceProjector
        ↓
ExecutionTraceProjection
        ├── events: التسلسل الزمني القانوني للعرض
        ├── groups: تجميع مشتق حسب الفئة
        ├── summary: إحصاءات مشتقة
        └── taskTree: علاقة بنيوية مشتقة
```

يوجد مسار توافق ثانٍ من `AgentActivityBus.recentEvents` إلى `UnifiedExecutionTraceProjector`. هذا ليس مصدر حقيقة بديلًا؛ فهو يضيف أحداث نشاط مملوكة لنفس `executionId` بعد التنقيح وإزالة التكرار. أحداث lifecycle وtool compatibility القديمة تستبعد عندما يوجد trace منظم مكافئ.

## الأدلة من الكود

### مالك الأحداث

`ExecutionStatusBus` يملك `MutableStateFlow<List<ExecutionTraceEvent>>` باسم `trace`، ويستخدم `ExecutionTraceBuffer` داخليًا. يبدأ buffer عند `onGraphStarted(executionId, sessionId)`، ويرفض الأحداث التي لا تخص التنفيذ النشط.

تنتج دورة الأداة أحداثًا مرتبطة بـ`actionId`:

- `TOOL_STARTED`.
- `TOOL_COMPLETED`.
- `TOOL_FAILED`.
- `TOOL_CANCELLED`.

ويمنع `ExecutionToolTraceLifecycle` التكرار غير الصالح قبل النشر.

### عقد الهوية

كل `ExecutionTraceEvent` يملك:

- `executionId` لربط الحدث بالتنفيذ.
- `sequence` للترتيب داخل التنفيذ.
- `eventId = executionId:event:sequence` كهوية مستقرة.
- `actionId` لأحداث دورة الأداة.
- `sessionId` لربط الحدث بجلسة المحادثة.

أحداث الأدوات التي لا تحمل `actionId` تُرفض في `ExecutionTraceBuffer`. الأحداث التي تخص تنفيذًا آخر تُرفض من `ExecutionStatusBus` أو تُستبعد من الإسقاط.

### الترتيب

`UnifiedExecutionTraceProjector`:

1. يرشح `traceEntries` حسب `executionId`.
2. يستبعد الملخصات الفارغة.
3. يرتب المصدر حسب `sequence` قبل تجميع دورة الأداة.
4. يدمج Activity events المملوكة للتنفيذ.
5. يرتب الناتج القانوني حسب `timestampMs` ثم `sequence`.

اختبار المرحلة يثبت أن الأحداث ذات الوقت نفسه تستخدم `sequence` ككاسر تعادل مستقر، وأن تجميعات `groups` تحتوي نفس هويات الأحداث المشتقة من `events` دون مصدر منفصل.

### إزالة التكرار

يوجد مستويان:

- أحداث structured ذات `actionId` الواحد تختزل إلى صف عرض واحد، وتظهر حالة الحدث terminal إن وصلت.
- Activity events التوافقية تستبعد بواسطة `machineTag` عندما يوجد structured trace مكافئ، والأحداث غير المنظمة المتشابهة تُدمج داخل نافذة `1_500ms` إذا تطابقت الفئة والعنوان.

لا ينبغي توسيع المقارنة النصية في مرحلة لاحقة بحيث تدمج حدثين مختلفين لهما عنوان متشابه خارج سياق `executionId` أو `actionId`.

### حدود السجل

`ExecutionTraceBuffer` محدود السعة. عند تجاوز السعة يحاول الاحتفاظ بالأحداث غير النهائية أطول، مع إزالة الأقدم وفق السياسة الحالية. تسلسل الأحداث لا يعاد ترقيمه بعد الإزالة؛ لذلك تبقى الهوية المرتبطة بـ`sequence` مستقرة ضمن التنفيذ.

الاختبار الحالي يغطي السعة، الاحتفاظ بالفشل النهائي، رفض الأحداث القديمة، والكتابة المتزامنة.

### التنقيح

التنقيح يحدث قبل نشر trace من `ExecutionStatusBus` عبر:

- `PrivacyGuard.redactForTrace`.
- `CommandRedactor.redact` داخل projection.
- تطبيع `errorCode` إلى صيغة محدودة.
- حد أقصى للنص المعروض داخل `safeTraceText`.

أضيف اختبار يثبت أن تفاصيل Activity event التي تحتوي `Authorization: Bearer ...` لا تدخل نموذج العرض بصورتها السرية.

### snapshot

`PlanSnapshotStore` يحتفظ بخطة نشطة صغيرة فقط، وليس سجل trace كاملًا. `PlanSnapshotPolicy` يمنع الاستعادة إذا كانت الخطة غير صالحة، ويفحص المحتوى بحثًا عن مؤشرات الأسرار مثل `api_key` و`authorization:` و`bearer` و`password` و`secret` ومواد الشهادات.

الاستعادة الحالية لا تثبت أن التنفيذ ما زال حيًا؛ فهي استعادة projection محفوظة. يجب أن تبقى هذه النقطة قاعدة للمرحلة الرابعة، ولا يجوز للواجهة المستقبلية عرض «جارٍ» اعتمادًا على snapshot وحده.

## التعديلات المنفذة في الدفعة الأولى

### اختبار الترتيب واشتقاق المجموعات

تمت إضافة اختبار إلى:

`app/src/test/java/com/airi/assistant/ui/trace/UnifiedExecutionTraceProjectorTest.kt`

ويثبت أن:

- الأحداث المبعثرة زمنيًا تعود إلى ترتيب `timestampMs` ثم `sequence`.
- كل event ظاهر في `groups` له هوية موجودة في canonical `events`.
- لا تنشئ المجموعات مصدرًا موازيًا للحدث.

### اختبار التنقيح قبل العرض

تمت إضافة اختبار يمرر تفصيلًا يحوي Bearer token إلى Activity event، ثم يثبت عدم ظهور قيمة السر أو ترويسة التفويض في `detailLog` الناتج.

لم يتم إنشاء `ExecutionUiState` جديد، ولم يتغير نموذج runtime، ولم تتغير Compose UI، ولم تضاف اعتماديات أو animations أو قوائم جديدة.

## ما لم يتغير عمدًا

- لم يتم تغيير `AgentLifecycleTrackerPanel`.
- لم يتم تغيير `InlineExecutionTrace`.
- لم يتم تغيير `AgentPlanOverlay` أو `AgentPlanContent`.
- لم يتم حذف `groups` أو `taskTree`؛ كلاهما ما زال مشتقًا ومغطى باختبارات قائمة.
- لم يتم ربط زر الإيقاف في هذه المرحلة؛ ذلك جزء من مرحلة لاحقة لأن إخفاء اللوحة ليس إلغاءً للـruntime.
- لم يتم إضافة `CANCELLING` لأن عقد runtime الحالية لا تثبت وجود حالة مستقلة لها.

## بوابة الانتقال للمرحلة الثانية

لا تبدأ إعادة تصميم السطح قبل تحقق ما يلي:

1. نجاح اختبارات الإسقاط ومخزن trace المتأثرة.
2. مراجعة diff والتأكد من عدم تعديل UI في المرحلة الأولى.
3. عدم وجود تسريب في projection أو snapshot.
4. توثيق نتيجة تشغيل Gradle أو العائق البيئي بدقة.
5. موافقة بشرية مستقلة على الانتقال إلى توحيد سطح العرض.

## حالة الدليل

- **مؤكد:** مصدر trace، عزل `executionId`، هوية `eventId`، تجميع tool lifecycle، حدود buffer، قواعد snapshot، والاختبارات الجديدة من الكود الحالي.
- **مرجح:** أن ازدحام الصورة ناتج جزئيًا عن تعدد مستويات العرض؛ هذا استنتاج بصري لا يثبت وحده مشكلة أداء أو تمرير.
- **غير محسوم:** أثر الأداء الفعلي للقائمة على جهاز الهاتف، وحجم تحديثات trace أثناء البث، وإمكان الإلغاء الحقيقي لكل نوع أداة. هذه تحتاج قياسًا واختبارات Android في مراحلها المخصصة.
