# AIRI — المرحلة الثانية
## ExecutionStatusBus & Trace Architecture

**التاريخ:** 2026-11-07  
**النطاق:** ملكية التنفيذ، التزامن، نشر الحالة، backpressure، وlifecycle  
**الفرع:** `phase2/execution-status-trace`

## 1. خلاصة التدقيق

لم يثبت التدقيق الحالي وجود اختناق يبرر استبدال `StateFlow` أو `traceLock` بــ Actor/Queue جديد. البنية الحالية تفصل بين:

- حالة UI الحالية: `_status: MutableStateFlow<AgentState>`، وهي projection واحدة للتنفيذ النشط الأخير حتى لا تتنافس عدة تنفيذات على شاشة واحدة.
- trace محفوظ لكل تنفيذ: `traceBuffers[executionId]` و`traceHistory[executionId]`.
- lifecycle للأدوات لكل تنفيذ: `toolTraceLifecycles[executionId]`.
- سياق الأداة لكل تنفيذ: `toolTraceContexts[executionId]`.
- حالة terminal لكل تنفيذ: `terminalExecutions`.

كل حدث يصل إلى `appendTrace` يحمل `executionId` صريحاً؛ لا يتم استنتاجه من حالة الواجهة. الأحداث القديمة بعد terminal أو cancellation تُرفض، مع استثناء terminal cancellation للأداة التي بدأت قبل طلب الإلغاء حتى لا يبقى trace معلقاً.

## 2. ملكية التنفيذ

| السؤال | النتيجة |
|---|---|
| هل لكل تنفيذ trace مستقل؟ | نعم، buffer/history/lifecycle/context keyed by `executionId`. |
| هل يمكن لحدث تنفيذ أن يدخل trace تنفيذ آخر؟ | حواجز `acceptsEvent`, `isOpenExecution`, و`ExecutionToolTraceLifecycle` ترفض ذلك. |
| هل توجد حالة global مشتركة؟ | نعم، `_status` يمثل العرض الحالي فقط؛ لا يُستخدم كمصدر ملكية للـtrace. |
| هل تؤثر الحالة global على trace الثانوي؟ | لا في الإغلاق والتخزين: التنفيذ الثانوي يغلق trace الخاص به دون الكتابة فوق UI status. |
| هل الأحداث مرتبة؟ | نعم، `ExecutionTraceBuffer` يخصص sequence monotonically لكل execution. |

هذه مفاضلة مقصودة: UI يحتاج حالة نشطة واحدة، بينما trace يحتاج عزل كل التنفيذات. لا ينبغي تحويل `_status` إلى قائمة عامة قبل إثبات حاجة UI لذلك.

## 3. التزامن ومواضع contention

- `traceLock` يحمي تحديث `_status` مع بنية trace وterminal ownership حتى لا يرى قارئ انتقالاً دون trace المقابل.
- `ConcurrentHashMap` يستخدم لجدول lifecycle، لكن عمليات lifecycle نفسها تظل محمية داخل الكائن.
- `MutableStateFlow` آمن من ناحية thread-safety، و`statusUpdate` يضمن أن التحويل والحراسة الخاصة بالملكية يحدثان تحت القفل نفسه.
- أكبر منطقة contention النظرية هي append المتزامن للأحداث داخل `traceLock`؛ لكنها bounded لأن كل buffer محدود، والحقول redacted/truncated قبل الحفظ.
- لا يوجد دليل بعد على أن contention يسبب تأخراً ملموساً؛ لذلك لم يتم استبدال القفل بــ Actor أو queue غير متزامنة قد تغير ترتيب الأحداث أو semantics الخاصة بالـterminal.

## 4. Backpressure وسياسة الاحتفاظ

- `maxTraceHistory` يحدد عدد التنفيذات المحتفظ بها، وقيمته الإنتاجية الحالية 32.
- كل `ExecutionTraceBuffer` bounded بسعة 150 حدثاً.
- الأحداث النشطة القابلة للتكرار يمكن اقتطاعها عند الامتلاء، لكن `FAILED`, `TIMED_OUT`, `CANCELLED`, recovery، وterminal evidence محمية من الاقتطاع العشوائي وفق اختبارات buffer الحالية.
- حفظ trace/history منفصل عن نشر `_trace` للواجهة؛ الواجهة ترى snapshot للتنفيذ النشط، بينما `traceSnapshot(executionId)` يحتفظ بالدليل الخاص بكل تنفيذ.
- لا توجد `Channel.UNLIMITED` داخل `ExecutionStatusBus`. توجد قناة غير محدودة في `LocalLlamaBackend`، وهي خارج نطاق هذه المرحلة وتحتاج تدقيقاً مستقلاً لأنها لا تمثل trace bus نفسه.

## 5. Lifecycle

تمت تغطية الحالات التالية في الاختبارات الحالية والجديدة:

- رفض event بلا execution owner.
- رفض event من تنفيذ آخر.
- منع duplicate tool start وduplicate terminal.
- منع late events بعد cancellation أو timeout أو approval terminal.
- بقاء terminal cancellation للأداة التي بدأت قبل طلب الإلغاء.
- تنظيف buffers/lifecycles/contexts/approvals عند `reset()`.
- عدم إعادة إنشاء trace محذوف من late finalize في trace manager.

## 6. اختبار الضغط المضاف

أضيف اختبار:

```text
ExecutionStatusBusTest.stressMatrix_preservesIsolationForOneTenAndFiftyExecutions
```

ويختبر المصفوفة:

| التنفيذات المتزامنة | أحداث الأدوات لكل تنفيذ | التحقق |
|---:|---:|---|
| 1 | 100 | ownership، ترتيب، terminal |
| 10 | 100 | ownership، عدم التداخل، terminal |
| 50 | 100 | ownership، عدم التداخل، terminal، bounded history |

لكل تنفيذ يتم إرسال 50 زوج `TOOL_STARTED/TOOL_COMPLETED` بالتوازي، أي 100 حدث أداة فعلي، ثم `COMPLETED`. الاختبار يقيس ويطبع `elapsedMs` و`heapDeltaBytes` في CI، ويتحقق من:

- عدم وجود حدث يحمل executionId خاطئاً.
- بقاء 50 حدث `TOOL_COMPLETED` لكل تنفيذ مع 100 حدث أداة إجمالاً.
- وجود terminal completion واحد في نهاية trace.
- عدم تكرار sequence داخل التنفيذ.
- عدم رجوع sequence إلى الخلف.
- إيقاف executor خلال مهلة محددة.

تم رفع حد history داخل الاختبار إلى 64 فقط حتى تبقى 50 هوية قابلة للفحص؛ تعاد القيمة الأصلية بعد الاختبار، ولا يتغير حد الإنتاج.

## 7. نتيجة التدقيق المحلي

```text
python3 tools/verify_core_changes.py
summary: 100/100 checks passed
```

لا يتوفر Android SDK/JDK صالح في بيئة الساندبوكس الحالية لتشغيل Gradle محلياً. لذلك يجب اعتماد Android CI لتشغيل اختبار Kotlin الجديد وقياسه على runner الرسمي.

## 8. قرار المرحلة

حتى هذه النقطة، الدليل يدعم الإبقاء على `StateFlow + traceLock + bounded per-execution buffers`. التحسين المطبق هو **إضافة اختبار ضغط واقعي**، لا تغيير التزامن تخميناً.

لا يتم إعلان المرحلة مكتملة قبل نجاح Android CI على الفرع. إذا أظهر الاختبار contention أو heap growth غير مقبول، ستكون الخطوة التالية تصميم queue/actor محدود النطاق مع الحفاظ على ترتيب الأحداث والـterminal evidence؛ وإلا تُغلق المرحلة دون إدخال تعقيد معماري غير مثبت.
