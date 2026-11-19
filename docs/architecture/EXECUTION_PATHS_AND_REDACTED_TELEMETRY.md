# Execution Paths and Redacted Telemetry

## الغرض

هذا المستند هو المرجع canonical لمسارات التنفيذ الحالية في AIRI. وجود class أو interface لا يعني أن المسار موصول بها؛ كل مسار أدناه يملك نقطة دخول ومالكاً وحدوداً مستقلة. لا يجوز دمج المسارات أو وصفها كمسار واحد إلا بعد إضافة adapter واختبار رأسي يثبت ذلك.

## المسارات والملكية

| المسار | نقطة الدخول | المالك التنفيذي | الأدوات والنتيجة | ما لا يملكه |
|---|---|---|---|---|
| Chat AgentLoop | `ChatViewModel` ثم `AgentLoop.run` | `AgentLoop` + `HybridOrchestrator` | `ToolDispatcher`، provider/local model، `ExecutionStatusBus` و`UniversalRuntimeTraceRecorder` | لا يمر تلقائياً عبر `CodingAgentRuntime` أو `ExecutionGraphRuntime` |
| Coding | `CodingAgentRuntime`/`CodingAgentOrchestrator` | `ProductionCodingRuntimeFactory` | `ToolExecutionPipeline`، approval، sandbox، ledger، coding verification | لا يثبت حماية Chat ما لم يستدعِ Chat نفس الـpipeline صراحة |
| Execution graph | `ExecutionGraphRuntime`/`AdaptiveGraphEngine` | graph runtime | DAG waves، dependency results، checkpoint/resume، terminal outcome adapter | لا يملك Chat history أو Voice lifecycle |
| Voice | `LiveVoiceService` وvoice routers | voice lifecycle/session owner | microphone/VAD/STT/TTS أو provider realtime | لا يُستدعى كامتداد صامت لمسار Chat |
| Scheduled | `ScheduledJobOrchestrator` ثم `ScheduledAgentWorker` | WorkManager + durable task manager | persistence، retry، reboot/constraints، durable outcome | لا يشارك foreground cancellation أو UI state تلقائياً |
| Sub-agent | `SubAgentRegistry`/`ProductionAgentOrchestrator` | sub-agent scope | typed events، scoped context، tool trace، cancellation | لا يرث كل أسرار أو ذاكرة الأب تلقائياً |

## سلسلة Chat المثبتة

```text
Composer
  -> ChatViewModel
  -> AgentLoop
  -> HybridOrchestrator
  -> LocalLlamaBackend أو Cloud adapter
  -> ToolDispatcher عند وجود tool call
  -> Tool result
  -> AgentLoop continuation أو terminal result
  -> persistence/UI
```

`ToolExecutionPipeline` و`CodingAgentRuntime` مساران إنتاجيان منفصلان حالياً. لا تستخدم الوثائق عبارات من نوع `AgentLoop -> ToolExecutionPipeline` إلا إذا كان call site الفعلي والاختبار الرأسي يثبتان ذلك.

## Redacted telemetry contract

يُسجل `UniversalRuntimeTraceRecorder` سلسلة correlation واحدة بواسطة `traceId` و`executionId` و`sessionId`، مع sequence monotonic داخل التنفيذ. السلسلة المقصودة لكل دورة tool هي:

```text
MODEL_REQUEST_BUILT
  -> MODEL_RESPONSE_RECEIVED
  -> TOOL_PARSE_COMPLETED / TOOL_SELECTED
  -> TOOL_DISPATCHED
  -> CONNECTOR_EXECUTION_COMPLETED (إن وُجد)
  -> RESULT_RETURNED
  -> MODEL_CONTINUATION_COMPLETED
  -> MODEL_REQUEST_BUILT (next model request)
```

القيم المسموح بها telemetry هي metadata bounded فقط: provider/model token، counts، lengths، hashes، tool names، execution path، status، error category، evidence kind، وredacted preview. لا يُحفظ prompt أو tool arguments أو provider response الخام. `RuntimeTraceRedaction` يزيل bearer/token/api-key/secret/password/authorization والبريد الإلكتروني، ويحد preview إلى 160 حرفاً؛ identifiers وأسماء الملفات تمر عبر token/hash عند الحاجة.

الـtrace تشخيصي وليس مصدر صلاحية: لا يمنح capability، ولا يقرر admission، ولا يستبدل result verification. عند غياب حدث `RESULT_RETURNED` لا يجوز تفسير `TOOL_DISPATCHED` كنجاح. وعند وجود `MODEL_CONTINUATION_COMPLETED` terminal يجب ألا يظهر طلب نموذج لاحق لنفس generation.

## قواعد التغيير

أي مسار جديد يجب أن يحدد owner وexecution identity وcancellation boundary وterminal outcome وredaction policy. يجب إضافة اختبار يثبت correlation order، وعدم تسريب secret/raw payload، وعدم خلط المسار مع Chat أو Coding أو Voice أو Scheduled أو Sub-agent.
