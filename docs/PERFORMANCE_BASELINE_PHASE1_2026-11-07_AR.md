# AIRI — تقرير المرحلة الأولى
## Observability & Performance Baseline

**التاريخ:** 2026-11-07  
**النطاق:** تدقيق منظومة القياس الحالية وتسجيل baseline قبل أي تحسين أداء  
**الفرع الأساسي قبل التغيير:** `main`  
**الالتزام قبل التغيير:** `cea7ad92`

## 1. منهجية التنفيذ

اتبعت المرحلة دورة:

> Audit → Evidence → Baseline → Minimal Change → Tests → Build → CI → Review → Commit

لم تتم إضافة Telemetry خارجية أو خدمة مدفوعة أو Dependency جديدة. أُعيد استخدام منظومة `UniversalRuntimeTraceRecorder` و`ExecutionStatusBus` و`ExecutionTraceBuffer` وقياسات `ChatViewModel` الحالية. التغيير البرمجي الوحيد في هذه المرحلة هو أداة inventory محلية قابلة لإعادة التشغيل؛ لا تسجل محتوى المستخدم ولا الأسرار ولا تتصل بأي خدمة خارجية.

## 2. الأدلة الموجودة في المصدر

تم تشغيل:

```bash
python3 tools/performance_baseline_inventory.py
```

والنتيجة الحالية:

| المؤشر | القيمة | مصدر الدليل |
|---|---:|---|
| ملفات Kotlin الإنتاجية | 788 | `app/src/main/java` |
| ملفات اختبارات Kotlin | 243 | `app/src/test` |
| مراجع أحداث runtime trace | 34 | `UniversalTraceEventType` |
| مراجع `ExecutionStatusBus` | 59 | مسارات runtime/UI/tests |
| مراجع duration/latency | 409 | القياسات الحالية في المصدر |
| السعة القصوى للـexecution trace | 150 حدثاً | `ExecutionTraceBuffer.DEFAULT_MAX_ENTRIES` |
| مراجع redaction | 22 | `PrivacyGuard` |
| مراجع `executionId` | 644 | correlation داخل المصدر |
| مراجع `stepId` | 208 | correlation داخل المصدر |
| مراجع `toolCallId` | 8 | correlation داخل المصدر |
| مراجع `traceId` | 103 | correlation داخل المصدر |
| مراجع `sessionId` | 880 | correlation داخل المصدر |

هذه أرقام inventory مصدرية، وليست ادعاءً لقياس أداء الجهاز أو النموذج.

## 3. القياسات المتوفرة فعلياً

المشروع يقيس أو يسجل حالياً:

- **TTFT:** `firstVisibleTokenLatencyMs` في `ChatViewModel`، محسوب من بداية الطلب حتى أول token ظاهر.
- **زمن التوليد الكلي:** `lastTotalLatencyMs` و`recordGenerationStats`.
- **P50/P90:** حقول `p50LatencyMs` و`p90LatencyMs` ومسار تحديث حالة التصحيح.
- **مراحل ما قبل أول token:** `PreFirstTokenTimeline` لمراحل تحميل التاريخ، RAG، بناء prompt، catalog الأدوات، وcapability snapshot.
- **زمن الأداة:** `ExecutionTraceEvent.durationMs` ومسار `UniversalRuntimeTraceRecorder.executionCompleted`.
- **حالة الأداة:** start/completed/failed/cancelled مع `actionId` و`executionId`.
- **حالة التنفيذ:** planning، steps، recovery، approval، timeout، completed، failed، cancelled.
- **حجم السياق:** `PromptSizeReport` وقياسات prompt/tool/history/RAG.
- **حدود الذاكرة/الأرشفة:** buffer bounded، trace archive، وقيود النصوص الحساسة.
- **الخصوصية:** redaction قبل كتابة تفاصيل trace، وعدم تسجيل payload الخام أو credentials.

## 4. نتائج التحقق القابلة للتشغيل

### التدقيق الساكن

```text
python3 tools/verify_core_changes.py
summary: 100/100 checks passed
```

### محاكاة الأداء المحلية

تم تشغيل:

```bash
python3 tools/stability/local_model_performance_sim.py \
  --runs 30 --output-tokens 128 --context 2048 \
  --prompt-tokens 256 --cancel-cycles 100
```

النتيجة:

```json
{
  "status": "PASS",
  "mode": "contract_simulation_not_native_inference",
  "runs": 30,
  "output_tokens": 128,
  "avg_latency_ms": 1,
  "p50_latency_ms": 1.0,
  "p95_latency_ms": 1,
  "synthetic_tokens_per_sec": 128000.0,
  "cancel_cycles": 100,
  "unique_terminal_digests": 1
}
```

هذه المحاكاة تتحقق من عقود admission/stream/cancellation فقط، ولا تمثل سرعة `llama.cpp` أو Gemini أو أي نموذج حقيقي.

### البناء والاختبارات المحلية

تعذر تشغيل Gradle محلياً في بيئة التدقيق الحالية لأن `javac` وAndroid SDK صالحاً غير متوفرين؛ يوجد `local.properties` يشير إلى `/home/ubuntu/android-sdk` لكن المسار غير موجود في هذه البيئة. لذلك لم تُسجل نتيجة بناء محلية غير متحققة، وسيكون CI هو مصدر التحقق الفعلي للبناء والاختبارات.

## 5. فجوات القياس المثبتة

| المجال | الحالة | التصنيف |
|---|---|---|
| TTFT/total/p50/p90 | موجودة في مسار المحادثة | مكتمل جزئياً |
| prompt prefill breakdown | موجودة | مكتمل |
| tool duration/outcome | موجودة | مكتمل |
| event count/ring bound | السعة ثابتة، لا يوجد snapshot metric موحد لكل تنفيذ | فجوة قابلة للتحسين |
| event rate | لا يوجد معدل أحداث/ثانية محفوظ | فجوة قياس |
| event publish processing time | لا يوجد قياس صريح | فجوة قياس |
| lost/duplicate events | توجد حواجز lifecycle، لكن لا يوجد counter موحد للتشخيص | فجوة قياس |
| Compose recomposition | غير مقاس | يحتاج Macrobenchmark/Perfetto على جهاز أو emulator |
| عرض الرسائل الطويلة والتمرير | غير مقاس | يحتاج instrumentation/device run |
| memory retrieval/write latency | غير مجمع كـdistribution موحد | فجوة قياس |
| native memory pressure | توجد مراقبة صحة/ضغط، لكن لا يوجد baseline جلسة موحد | يحتاج device run |
| provider/connector failure rates | أحداث الأخطاء موجودة، لا يوجد تجميع تاريخي موحد | فجوة قياس |
| RequestId → ExecutionId | `executionId` و`traceId` موجودان، لكن request identity ليست موحدة في كل المسارات | خطر قابل للتحسين |
| StepId → ToolCallId | `stepId` و`actionId` موجودان؛ `toolCallId` قليل الاستخدام | خطر قابل للتحسين |

## 6. الأولويات المبنية على الأدلة

1. **P1:** توحيد correlation contract من `requestId` إلى `executionId` ثم `stepId` ثم `toolCallId/actionId` في مسار AgentLoop، بدون تغيير payload أو UI API.
2. **P1:** إضافة counters منخفضة التكلفة داخل trace lifecycle للأحداث المقبولة والمرفوضة والمكررة، حتى يمكن قياس فقدان الأحداث بدلاً من استنتاجه.
3. **P1:** تسجيل عدد الأحداث وحجم snapshot وزمن `appendTrace` بشكل bounded وdebug-safe.
4. **P2:** إضافة قياسات memory retrieval/write وconnector request duration إلى نفس trace contract.
5. **P2:** تنفيذ Macrobenchmark/Perfetto على emulator أو جهاز حقيقي لقياس Compose، scrolling، وStreaming UI؛ لا يمكن استنتاج هذه القيم من المصدر.
6. **P3:** إنشاء تقرير تجميعي اختياري من trace archives بعد موافقة المستخدم، مع الالتزام بالـredaction وعدم إضافة خدمة خارجية.

## 7. قرار المرحلة

المرحلة الأولى حققت هدفها الأساسي: توجد صورة موثقة لما هو مقاس حالياً، وما هو غير مقاس، وما يمكن تحسينه بناءً على دليل. لم تُنفذ تحسينات أداء تخمينية ولم تُدّعَ نتائج native أو device غير متاحة.

**حالة الكود:** أداة baseline فقط، دون تغيير في عقود runtime الحالية.  
**حالة التحقق:** static audit ناجح 100/100؛ local contract simulation ناجحة؛ Gradle المحلي غير قابل للتشغيل بسبب نقص toolchain؛ CI مطلوب كتحقق نهائي.  
**القرار:** يمكن إغلاق baseline والانتقال لاحقاً إلى مرحلة correlation/counters بعد مراجعة CI، دون خلطها مع تحسينات أداء أخرى.
