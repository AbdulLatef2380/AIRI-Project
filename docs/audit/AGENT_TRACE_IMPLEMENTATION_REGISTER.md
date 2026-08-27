# AIRI — سجل تنفيذ Agent Planning & Execution Trace

> هذا السجل يحول المتطلبات المرفقة إلى نطاق تنفيذي ملزم. لا تعني خانة «مدمج جزئياً» أن تجربة المستخدم أُغلقت؛ لا يُرفع أي بند إلى **DONE** من دون دليل source + tests + CI + runtime/UI حيث ينطبق.

## الهدف المعماري

يبنى النظام فوق مكونات AIRI القائمة ولا يستبدلها: `AgentLoop` و`AdaptiveGraphEngine` و`HybridOrchestrator` و`ExecutionStatusBus` و`AgentActivityBus` و`AgentPlanViewModel` وواجهة `ChatScreen`. تنفذ طبقات الوكيل، وتبقى Compose مراقباً للحالة فقط.

```text
ChatScreen → ChatViewModel → AgentLoop / AdaptiveGraphEngine
                           → HybridOrchestrator → Local / Cloud LLM
                           → ToolDispatcher / connectors
                           → ExecutionStatusBus + bounded Activity trace
                           → AgentPlanViewModel → Task Progress + Live Trace UI
```

## بوابات الدليل

| البوابة | الشرط |
|---|---|
| Root cause | عيب مثبت في المصدر أو التشغيل، لا تخمين UX فقط. |
| Contract | نموذج typed يملك executionId وsessionId وmodelId عند الحاجة، مع transitions مقيدة. |
| Security | لا CoT خام، ولا credentials أو tokens أو headers في trace أو UI أو logs. |
| Tests | unit/negative/lifecycle tests للسلوك القابل للعزل. |
| CI | Android CI وDeep/Architecture/Oracle تمر بعد كل دفعة. |
| Runtime | يظل BLOCKED إن احتاج جهاز ARM64 أو provider/OAuth حي؛ لا يتحول إلى DONE بفضل CI فقط. |

## المتطلبات ومسار التنفيذ

| المجال | التكامل المعتمد | الحالة الحالية | معيار الإغلاق |
|---|---|---|---|
| Structured planning | توسيع graph/planner القائم؛ validation للخطط غير الصحيحة والاعتمادات والدورات | PARTIALLY_DONE | parsing + validation + invalid/empty/duplicate/cycle tests. |
| Execution ownership | `executionId` من admission حتى terminal state | PARTIALLY_DONE | جميع الأحداث تُرفض عند id فارغ أو stale؛ test وCI. |
| Safe trace | summaries عالية المستوى فقط، لا reasoning خام | OPEN | redaction policy + negative tests للـsecrets والـCoT. |
| Tool tracing | event typed للبداية/النجاح/الفشل/المدة من dispatcher القائم | OPEN | tool invocations يظهر لها executionId وduration وsanitised summary. |
| Live event stream | StateFlow/SharedFlow bounded داخل التطبيق، لا SSE/WebSocket غير مبرر | PARTIALLY_DONE | sequence ordering وbounded retention وlifecycle tests. |
| Progress tree | `TaskExecutionTracker` و`AgentPlanViewModel` | PARTIALLY_DONE | running/retry/fail/cancel/complete، بلا placeholder steps. |
| Live log UI | تطوير السطح المدمج في Chat، فلترة وعرض تفاصيل وإيقاف auto-scroll | OPEN | Compose/UI tests للفلترة وjump-to-latest والتفاصيل. |
| Cancellation | Chat → ViewModel → orchestrator → local/cloud backend | PARTIALLY_DONE | cancel يوقف retries/fallbacks ولا يسلم terminal مزدوج. |
| Persistence/recovery | إعادة استخدام مخازن execution history/checkpoint القائمة فقط حيث تناسب | OPEN | process/lifecycle evidence أو BLOCKED بوضوح. |
| LLM/connectors | `HybridOrchestrator` وRuntimeRouter وToolDispatcher دون duplicate abstraction | PARTIALLY_DONE | local/cloud/provider runtime matrix؛ provider live = external gate. |

## الارتباط بسجل 27 بنداً

هذا النظام يعالج مباشرةً البند 09 (planning)، 23 (terminal/workspace trace) و27B (AI response/thinking/execution)، ويدعم أدلة البنود 07 و08 و11 و12 و19 و24 و25. أما الترجمة/RTL/theme/OAuth/voice/profile فتظل دفعات مستقلة ولا تُعلن مغلقة عبر trace وحده.

## قاعدة عدم الادعاء

`MITIGATED ROOT CAUSE` لا تساوي `USER PROBLEM DONE`. يجب أن يذكر كل تحديث: root cause، الملفات، الاختبارات الإيجابية والسلبية، lifecycle/runtime evidence، CI، وأي بوابة خارجية متبقية.
