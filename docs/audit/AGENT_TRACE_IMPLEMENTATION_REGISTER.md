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
| Execution ownership | `executionId` من admission حتى terminal state | PARTIALLY_DONE | AgentLoop وAdaptiveGraphEngine يملكان مساراً صريحاً؛ يجب أن تتبناه بقية مصادر Skill/Tool القديمة. |
| Safe trace | summaries عالية المستوى فقط، لا reasoning خام | MITIGATED | حجب مركزي للـtrace وAgentTrace وإسقاط AppEvent مع اختبارات secrets/fields؛ يلزم تدقيق كل producer وsink متبقٍ. |
| Tool tracing | event typed للبداية/النجاح/الفشل/الإلغاء والمدة من dispatcher القائم | PARTIALLY_DONE | AgentLoop ينشر actionId/executionId/sequence/duration، وgraph executor يملك overload واعياً بالـexecution؛ مسارات SkillService القديمة لا تزال بلا سياق تنفيذ. |
| Live event stream | StateFlow/SharedFlow bounded داخل التطبيق، لا SSE/WebSocket غير مبرر | PARTIALLY_DONE | sequence ordering وretention محدود ورفض lifecycle stale/duplicate مثبتة في JVM؛ لا يوجد بعد دليل rotation/process-death. |
| Progress tree | `TaskExecutionTracker` و`AgentPlanViewModel` | PARTIALLY_DONE | trace الحالي يظهر مع الخطة، والحالات مصدرها أحداث فعلية؛ يلزم دليل UI device/RTL وfont-scale. |
| Live log UI | تطوير السطح المدمج في Chat، فلترة وعرض تفاصيل وإيقاف auto-scroll | PARTIALLY_DONE | AgentPlanContent يعرض trace الحالي بالـfilters والتفاصيل والجديد/القفز؛ اختبارات policy على JVM فقط حتى الآن. |
| Cancellation | Chat → ViewModel → orchestrator → local/cloud backend | PARTIALLY_DONE | cancel يوقف retries/fallbacks ولا يسلم terminal مزدوج. |
| Persistence/recovery | إعادة استخدام مخازن execution history/checkpoint القائمة فقط حيث تناسب | OPEN | process/lifecycle evidence أو BLOCKED بوضوح. |
| LLM/connectors | `HybridOrchestrator` وRuntimeRouter وToolDispatcher دون duplicate abstraction | PARTIALLY_DONE | local/cloud/provider runtime matrix؛ provider live = external gate. |

## الارتباط بسجل 27 بنداً

هذا النظام يعالج مباشرةً البند 09 (planning)، 23 (terminal/workspace trace) و27B (AI response/thinking/execution)، ويدعم أدلة البنود 07 و08 و11 و12 و19 و24 و25. أما الترجمة/RTL/theme/OAuth/voice/profile فتظل دفعات مستقلة ولا تُعلن مغلقة عبر trace وحده.

## قاعدة عدم الادعاء

`MITIGATED ROOT CAUSE` لا تساوي `USER PROBLEM DONE`. يجب أن يذكر كل تحديث: root cause، الملفات، الاختبارات الإيجابية والسلبية، lifecycle/runtime evidence، CI، وأي بوابة خارجية متبقية.


## بوابة الإغلاق الملزمة

لا يستخدم هذا البرنامج حالة **DONE** إلا عندما تتحقق جميع الشروط التالية للمشكلة المعنية: سبب جذري مثبت، إصلاح مكتمل، اختبارات إيجابية وسلبية/انحدار، CI ناجح، دليل Runtime أو UI عند الانطباق، وعدم وجود بوابة خارجية مفتوحة مرتبطة بها. تعني **MITIGATED** إصلاحاً موضعياً أو معمارياً لا يكفي وحده لإغلاق تجربة المستخدم. ويجب أن تسجل البوابات التي تتطلب جهاز ARM64 أو provider حي أو OAuth أو Play Console كـ **BLOCKED / EXTERNAL_PENDING**، ولا تتحول إلى DONE بسبب وجود الكود أو نجاح CI.

| نطاق trace | بوابة DONE المحددة |
|---|---|
| Planning | admission حقيقي بعد validation؛ رفض empty/duplicate/cycle/dependency غير الصالح والـstale event؛ لا خطوات UI مصطنعة. |
| Trace الآمن | لا CoT خام أو system prompt أو credential/header/cookie؛ redaction مركزية واختبارات سرية وهمية سلبية. |
| Tool trace | start وterminal واحد لكل tool، executionId/actionId، sequence/timestamp، مدة وملخص sanitized، ورفض stale/duplicate callbacks. |
| Live stream | ترتيب deterministic، retention محدود، lifecycle وbackpressure وrotation آمنة، ولا يقتل collector التنفيذ. |
| Progress/UI | حالات queued/running/retrying/completed/failed/cancelled مصدرها أحداث تنفيذ فعلية، مع RTL/LTR وaccessibility وfont-scale UI evidence. |
| Ownership | snapshots صريحة لـsession/model/request/execution، ورفض stale/mismatch وعدم انتقال النتيجة أو attachment أو trace إلى session آخر. |

## أدلة التنفيذ الحالية

| Commit | النتيجة المثبتة | الاختبارات والبوابات |
|---|---|---|
| `e4559f2f` | أضاف lifecycle نقيّاً لأفعال الأدوات، وحقول `actionId`/`durationMs` في trace، وربط AgentLoop بالـexecutionId الصريح، وoverload متوافقاً خلفياً لمنفذ AdaptiveGraphEngine، وحجب AppEvent عند الإسقاط. | `ExecutionToolTraceLifecycleTest` و`ExecutionTraceBufferTest` و`GlobalAgentEventDispatcherTest`. Android CI `33118602936`، Deep `33118602894`، Architecture `33118602895`، Oracle `33118602886`: **success**. |
| `380c0a54` | أضاف presentation policy وواجهة trace داخل AgentPlanContent وموارد ar/en/es/zh. | كشف Deep Audit `33120815009` خطأ Compose حقيقياً: `stringResource` استُدعي داخل semantics lambda؛ لم يُعتمد هذا الرأس كنجاح. |
| `064f9034` | أصلح invocation غير الصحيح بنقل stringResource إلى قيمة composable محسوبة قبل semantics. | Android CI `33121217462`، Deep `33121217471`، Architecture `33121217381`، Oracle `33121217398`: **success**. |

> يبقى دليل العرض المرئي على جهاز ARM64، واختبارات RTL/font-scale الحقيقية، ودليل provider/OAuth الحي **BLOCKED / EXTERNAL_PENDING** إلى أن يُنفذ فعلاً. نجاح CI يثبت التجميع والاختبارات الداخلية ولا يحول هذه البنود إلى DONE.
