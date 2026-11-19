# P2 Evidence Register — 2026-11-20

## قاعدة الدليل

هذا السجل هو المرجع الواحد لحالة الأدلة. لا تُرفع نتيجة source inspection إلى runtime claim، ولا تُفسر نتيجة CI على أنها device/native/provider evidence.

- **SOURCE_VERIFIED**: تحقق من الكود أو العقد أو wiring أو الاختبارات الموجودة في المستودع.
- **RUNTIME_VERIFIED**: تحقق بتنفيذ فعلي على runtime المعني، مثل Android process/device، native GGUF/JNI، provider، أو lifecycle حقيقي.
- **CI_VERIFIED**: تحقق من build/test داخل CI أو Gradle، لكنه لا يساوي device/runtime verification.
- **HISTORICAL**: تقرير سابق محفوظ للمرجعية ولا يمثل الحالة الحالية وحده.
- **OPEN**: لا يوجد دليل كافٍ بعد.

## سجل P2 الحالي

| الموضوع | المصدر | نوع الدليل | الحالة | الحد |
|---|---|---|---|---|
| Chat/Tool telemetry redaction | `UniversalRuntimeTraceRecorder`, `RuntimeTraceRedaction`, `UniversalRuntimeTraceTest` | SOURCE_VERIFIED + CI_VERIFIED | مثبت في الكود واختبار JVM | ليس دليلاً على Android rotation أو provider runtime |
| Memory candidate vs durable admission | `MemoryExtractor`, `AgentMemoryDecision`, `MemoryExtractorTest`, `AgentMemoryDecisionTest` | SOURCE_VERIFIED + CI_VERIFIED | مثبت | لا يثبت consent أو persistence على جهاز حقيقي |
| Semantic RAG readiness | `EmbeddingService`, `RagRetriever`, `RagChronologicalFallback`, `LlamaNative` | SOURCE_VERIFIED | موثق، غير runtime-verified | يتطلب GGUF embedding محملاً وJNI يعمل فعلياً |
| Chronological fallback | `RagChronologicalFallback` وRAG tests | SOURCE_VERIFIED + CI_VERIFIED | مقصود ومحدود | لا يثبت semantic relevance |
| StateFlow trace lifecycle | `ExecutionStatusBusTest` | SOURCE_VERIFIED + CI_VERIFIED | assertions مضافة | rotation/process restart الحقيقي ما زال يحتاج Android instrumentation |
| OS sandbox isolation | `SandboxIsolationPolicy`, `SandboxExecutor` | SOURCE_VERIFIED | policy موجودة | لا تُسمى OS-isolated runtime دون device/OS evidence |

## أرشيف التقارير التاريخية

التقارير التي تصف مراحل أو بيئات سابقة موسومة هنا بـ`HISTORICAL`. تبقى مساراتها الأصلية محفوظة لتجنب كسر الروابط، ولا يجوز استخدامها وحدها كإثبات للحالة الحالية:

- `reports/FINAL-DELIVERY-REPORT.md` — `HISTORICAL`
- `reports/FINAL-FORENSIC-AUDIT-phases9-15.md` — `HISTORICAL`
- `reports/FORENSIC-VERIFICATION-REPORT.md` — `HISTORICAL`
- `reports/phase0-architecture-inventory.md` إلى `reports/phase8-final-verification-report.md` — `HISTORICAL`
- `reports/verification/*` — `HISTORICAL` unless a newer register explicitly promotes a result.
- `reports/INTEGRATION_AUDIT_2026-11-19.md` و`reports/P0_EXECUTION_STATUS_2026-11-19.md` — historical execution records; current code requires this register and current CI evidence.

لا يتم حذف التقارير التاريخية أو تعديل نتائجها بأثر رجعي. أي تصحيح أو تحقق جديد يُضاف كتقرير جديد مرتبط بـcommit وtest command وenvironment.

## متطلبات التقرير القادم

كل تقرير جديد يجب أن يذكر: commit، branch، command، environment، exact test/runtime target، نوع الدليل من التصنيفات أعلاه، والقيود. عبارات مثل `verified` وحدها غير مقبولة.
