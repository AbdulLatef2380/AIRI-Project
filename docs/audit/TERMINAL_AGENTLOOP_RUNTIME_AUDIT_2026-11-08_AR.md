# تقرير تدقيق Terminal من AgentLoop إلى Runtime وActiveWorkStopController والحوكمة

**التاريخ:** 2026-11-08  
**النطاق:** `AgentLoop` → `ToolDispatcher` → `TerminalExecutionGateway` → `SandboxExecutor`، مع `TerminalRuntime` و`ActiveWorkStopController` و`PermissionGovernanceLayer` و`AuditRepository`.  
**طريقة التدقيق:** قراءة ثابتة وتتبع استدعاءات واختبارات موجودة فقط. **لم يتم تعديل الشيفرة ولم يتم تشغيل build أو اختبارات محلية.**

## الخلاصة التنفيذية

المسار الحالي لا يستخدم `/system/bin/sh -c`؛ التنفيذ الفعلي في `SandboxExecutor` يمرر قائمة argv إلى `ProcessBuilder` بعد تفكيك whitespace ورفض محارف shell. لذلك لا يوجد في المسار المفحوص bypass مؤكد من نوع `;`/`&&`/`$()` إلى shell.

لكن الجاهزية الأمنية ليست مكتملة:

1. **P0/P1 — حدود التنفيذ قابلة للالتفاف معمارياً:** `SandboxExecutor` و`SandboxTask` و`TaskType.SHELL_COMMAND` عامة، وتطبق allowlist جزئية مستقلة عن `TerminalExecutionPolicy`؛ الاستدعاء المباشر يمكنه تجاوز حوكمة الصلاحيات وقواعد الخيارات، حتى إن لم يوجد حالياً استدعاء إنتاجي مباشر من UI/Agent.
2. **P1 — غياب correlation كامل في Terminal agent path:** `AgentLoop` يملك `executionId/actionId/traceId/sessionId` وينشئ `taskId/runId/stepId`، لكن `ToolDispatcher` يمرر إلى `executeForAgent` الأمر و`agentId` فقط. `TerminalExecutionGateway` لا يستقبل ولا يثبت `projectId/taskId/runId/stepId/executionId/actionId` ولا يربط قرار الحوكمة أو جلسة sandbox بها.
3. **P1 — terminal evidence غير قابل للاعتماد:** `executeForAgent` ينشئ جلسة مؤقتة ثم يغلقها في `finally`. سجل `SandboxSession.execLog` داخل الذاكرة فقط، ولا يكتب `AuditRepository` أو trace دائم؛ وبإغلاق الجلسة تُحذف workspace، فتضيع أدلة الأمر والنتيجة.
4. **P1 — تسريب output/أخطاء محتمل:** الأمر المدخل يُعرض بصيغة redacted، لكن ناتج `cat`/`git` والأخطاء يعادان خاماً إلى `ToolDispatcher` ثم إلى نموذج AgentLoop أو واجهة `TerminalRuntime`. لا يوجد output secret-redaction مركزي قبل العرض/إرجاعه للسياق.
5. **P1 — late callbacks وحالة الإلغاء غير ذرّية:** `ActiveWorkStopController` يرسل cancel ثم يعيد تقريراً فوراً، بلا انتظار quiescence أو fence لكل execution. `AgentLoop` لا يفرض `ensureActive`/generation gate قبل `onStepComplete` وبعد عودة dispatcher، و`TerminalRuntime` قد يضيف `Command cancelled` إلى الحالة بعد طلب الإلغاء؛ لا يوجد عقد موحد يمنع كل callbacks المتأخرة عبر طبقة Terminal.
6. **P2 — التدقيق fail-open:** `AuditRepository.log` fire-and-forget، وفشل DB يسجل إلى logcat فقط. هذا مقبول للتشغيل الاعتيادي لكنه لا يحقق متطلب terminal evidence؛ لا يوجد outbox أو نتيجة فشل تمنع إعلان التنفيذ موثقاً.
7. **P2 — عقد الموافقة غير متسق:** `TerminalExecutionPolicy` يعيد `requiresApproval=true` للأوامر destructive، لكن Gateway يعيد `Disabled` ولا ينشئ approval مربوطاً بـ durable task/run/step. النتيجة الحالية fail-closed (لا bypass)، لكنها لا توفر دورة موافقة قابلة للاستئناف أو دليلاً يثبت سبب الرفض.

## مسار التنفيذ المرصود

- `AgentLoop.kt:945-1087`: يفرض وجود `AgentLoopExecutionContext` للأداة `terminal_execute`، ثم يستدعي `ToolDispatcher`.
- `AgentLoop.kt:1189-1217`: بعد التنفيذ يستدعي `onStepComplete` ويسجل النتيجة في history/trace.
- `ToolDispatcher.kt:367-392`: يستخرج `command` ويمرره إلى `ServiceLocator.terminalExecutionGateway.executeForAgent(command, agentId)`؛ بقية هويات التنفيذ لا تمرر.
- `TerminalExecutionGateway.kt:32-40`: agent path ينشئ جلسة ephemeral ويغلقها دائماً.
- `TerminalExecutionGateway.kt:55-75`: يفحص policy ثم governance ثم ينشئ `SandboxExecutor` وينفذ `SHELL_COMMAND`.
- `SandboxExecutor.kt:132-224`: tokenize، reject shell metacharacters، allowlist binary، validate arguments، ثم `ProcessBuilder(argv)` مع environment scrubbed وoutput cap.
- `TerminalRuntime.kt:127-211`: يعرض input redacted، لكنه يعرض output/error الخام؛ الإلغاء يعتمد على `Job.cancel` فقط.
- `ActiveWorkStopController.kt:30-50`: يلغي orchestrator/tasks/jobs/terminal ويسجل ملخصاً غير مرتبط بـ execution IDs.

## النتائج التفصيلية

### F-01 — قابلية تجاوز الحوكمة عبر API التنفيذ المباشر

**الشدة:** P0 إذا أصبح API متاحاً لأي runtime غير موثوق، وإلا P1 كخطر معماري.  
**الأدلة:** `SandboxExecutor.kt:38-49,132-163,240-263`؛ `TerminalExecutionPolicy.kt:25-47,115-179`.

الـ Gateway يطبق `TerminalExecutionPolicy` قبل `SandboxExecutor`. لكن `SandboxExecutor.execute(SandboxTask)` عامة، و`SHELL_COMMAND` عامة، كما أن قواعد executor لا تطابق قواعد policy بالكامل. مثلاً policy تمنع خيارات الكتابة/التنفيذ الخاصة بـ `find` و`sed` و`sort` و`awk`، بينما executor يكتفي بفحص محدود (`-exec/-delete` وبعض المسارات). استدعاء مباشر يستطيع تنفيذ argv لا يمر بـ governance أو approval أو policy الكاملة، مثل خيارات تحرير/كتابة داخل workspace.

**الإصلاح:**

- اجعل `SandboxExecutor` وconstructor و`SandboxTask` internal/private خلف `TerminalExecutionGateway`، أو قدم `SandboxCommand` typed لا يقبل string.
- اجعل parser/argv validator واحداً مشتركاً (مصدر الحقيقة الوحيد)، واستدعِه داخل executor لا في Gateway فقط.
- لا تسمح بأي `TaskType.SHELL_COMMAND` خارج gateway؛ أضف architectural test/grep check يمنع الإنشاء المباشر في production.
- لا تعتمد على comments مثل “No UI or agent may instantiate…” كضبط أمني.

### F-02 — لا يوجد bypass shell مؤكد في المسار الحالي، لكن الضمان ليس محصوراً

**النتيجة:** لا يوجد دليل على `/system/bin/sh -c` في المسار المفحوص. `SandboxExecutor.kt:162` يستخدم `ProcessBuilder(argv)`، و`META_CHARS` تشمل `; & | \` $ > <` والاقتباسات وglobs.

**المخاطر المتبقية:**

- tokenizer whitespace-only (`SandboxExecutor.kt:236-238`) لا يدعم quoting؛ هذا آمن من ناحية shell injection لأنه يرفض quotes، لكنه يعامل كل الإدخالات كسلسلة ويزيد احتمال اختلاف policy/executor.
- allowlists مكررة بين `TerminalExecutionPolicy` و`SandboxExecutor`، ما يسمح بالانحراف مستقبلاً.
- `SandboxExecutor` direct path يتجاوز policy كما في F-01.

**الإصلاح والاختبار:** استخدم parser typed واحداً يعيد `ArgvCommand(binary, args)`، ثم اختبر رفض `;`, `&&`, `||`, backticks، `$()`, newline، quotes، glob، absolute path، symlink canonical escape، و`git` subcommands الخطرة عبر Gateway **وعبر executor**.

### F-03 — غياب correlation بين Terminal وproject/task/run/step

**الشدة:** P1.  
**الأدلة:**

- `AgentLoopExecutionContext.kt:10-17` يعرف `taskId, missionId, projectId, runId, stepId, agentId, sourceSessionId`.
- `AgentLoopTaskRuntime.kt:34-69` ينشئ هذه الهويات ويبدأ durable run.
- `AgentLoop.kt:193-195,243-258,1035-1087` يحتفظ أيضاً بـ execution/trace/action/session.
- لكن `ToolDispatcher.kt:76-87,367-371` يمرر فقط command وagentId إلى Gateway.
- `TerminalExecutionGateway.kt:33-75` لا يحتوي أياً من task/run/step/project/execution/action، و`PermissionGovernanceLayer.evaluate` لا يستقبلها (`PermissionGovernanceLayer.kt:103-108`).

بالتالي يمكن أن يظهر تنفيذ terminal صحيحاً في activity feed لكن لا يمكن إثبات أنه تابع لمهمة/مشروع/خطوة محددة، ولا منع إعادة استخدام جلسة/قرار من run آخر على مستوى Gateway.

**الإصلاح:** عرّف `TerminalExecutionRequest` إلزامياً:

```kotlin
data class TerminalExecutionRequest(
    val command: ArgvCommand,
    val projectId: String?,
    val taskId: String,
    val runId: String,
    val stepId: String,
    val executionId: String,
    val actionId: String,
    val agentId: String,
    val sourceSessionId: String,
)
```

تحقق داخل Gateway من durable ownership (`task.projectId`, `currentRunId`, `currentStepId`, status RUNNING)، ومن تطابق agent/session، ثم مرر نفس correlation إلى governance، session label، runtime trace، audit، ونتيجة التنفيذ. ارفض أي field ناقص؛ لا تستخدم defaults مثل `terminal` أو string فارغ في agent path.

### F-04 — terminal evidence مفقود بعد التنفيذ

**الشدة:** P1.  
**الأدلة:** `TerminalExecutionGateway.kt:34-40` يغلق الجلسة في `finally`؛ `SandboxSession.kt:12-15,24` يحتفظ بالسجل في الذاكرة ويحذف workspace؛ `SandboxExecutor.kt:60-74` يسجل command redacted وresult type فقط؛ `AuditRepository.kt:44-85` ليس مستخدماً من Gateway.

لا يوجد سجل دائم يتضمن decision، argv hash، binary، exit code، duration، output hash/length، timeout/cancel، وcorrelation. كما أن `ActiveWorkStopController.kt:44-49` يسجل counters فقط. لذلك لا يمكن بعد process death أو stop إثبات ما بدأ وما انتهى وما أُلغي.

**الإصلاح:**

- أنشئ `TerminalEvidence` immutable قبل dispatch وبعده، مع `evidenceId`, full correlation، policy decision، approval id، argv hash (لا raw secret)، start/end، outcome، exit code، output length/hash، truncation، cancellation state.
- اكتب start/terminal event إلى durable audit outbox قبل التنفيذ وبعده. إذا فشل الحفظ، لا تعلن `evidence_complete=true`؛ استخدم حالة `AUDIT_UNAVAILABLE` واضحة.
- احتفظ بسجل agent المنزوع الأسرار لمدة retention محددة، ولا تعتمد على `SandboxSession.execLog` المؤقت.
- أضف terminal-specific trace event إلى `UniversalRuntimeTraceRecorder` و`ExecutionStatusBus` مع ownership validation.

### F-05 — output وsecrets

**الشدة:** P1.  
**الأدلة:** `TerminalRuntime.kt:137-143,181-206` يرّشح command فقط ويضيف output/error الخام إلى UI؛ `ToolDispatcher.kt:380-391` يعيد `execution.output` و`execution.error`؛ `SandboxExecutor.kt:209-223` يعيد stdout/stderr المدمجين.

حتى مع environment scrubbed (`SandboxExecutor.kt:166-175`)، يمكن لأمر مسموح مثل `cat` أو `git show` إعادة بيانات حساسة من workspace إلى UI أو model context. `CommandRedactor` لا يكفي لأنه يطبق على command في المواضع الظاهرة ولا يثبت redaction على output. كذلك رسائل exception قد تتضمن مسارات أو بيانات من subprocess.

**الإصلاح:**

- طبّق `SecretRedactor` مركزي على output/error قبل UI وbefore model context وقبل activity/audit، مع قواعد token/API key/PEM/password وproject secret values المعروفة.
- افصل `rawOutput` الداخلي عن `safeOutput` الخارجي؛ لا تسمح لأي `ToolResult.Success` terminal بإرجاع raw.
- حد أقصى بعدد الأسطر/البايتات، وسجّل hash/length لا النص في audit.
- أضف tests فيها fake secret في stdout/stderr وenv/path وتتحقق من عدم ظهوره في `TerminalRuntime.lines`, `ToolResult`, `AgentActivityBus`, وaudit.

### F-06 — late callbacks بعد الإلغاء وstop

**الشدة:** P1.  
**الأدلة:**

- `ActiveWorkStopController.kt:30-42` يستدعي cancel ويعيد report بلا await أو generation/fence.
- `TerminalRuntime.kt:134-136,214-219` يحتفظ بـ Job واحد ويلغيه، لكن لا يوجد generation token يمنع نتيجة قديمة من تحديث `_lines` أو `_isRunning`.
- `AgentLoop.kt:1189-1217` يستدعي `onStepComplete` ويحدث history/trace بعد dispatch؛ لا يوجد `ensureActive` صريح قبل callback أو بعد نتيجة tool.
- توجد حماية جيدة في `ExecutionStatusBus` (`onToolStarted` يرفض owner غير المفتوح/المُلغي، وlifecycle يمنع terminal المكرر)، لكنها لا تحمي `TerminalRuntime` ولا `ToolDispatcher` ولا `AuditRepository` من callbacks المتأخرة.

**الإصلاح:**

- أضف `ExecutionGenerationGate`/`CancellationToken` إلزامياً إلى `TerminalExecutionRequest`، وافحص `accepts(generation)` قبل dispatch، بعد return، وقبل كل UI/trace/audit callback.
- عند stop: mark-cancelled atomically أولاً، ثم cancel jobs، ثم await bounded quiescence، ثم اكتب terminal outcome واحداً. التقرير يجب أن يميز `cancel_requested` عن `cancel_confirmed`.
- لا تسمح لـ `onStepComplete` أو `history.add` إذا لم يعد execution owner صالحاً.
- في `TerminalRuntime` استخدم generation لكل command؛ callbacks من generation قديمة تُهمل، ولا تغيّر `_isRunning` إلا إذا كانت owner الحالية.
- أضف اختبار race: block fake executor، اطلب stop، أطلق completion متأخراً، وتحقق من عدم ظهور output/Success بعد CANCELLED.

### F-07 — approval غير مربوط بمسار terminal

**الشدة:** P2 (يرتفع إلى P1 إذا كان المطلوب explicit approval لكل terminal).  
**الأدلة:** `TerminalExecutionPolicy.kt:34` يعيد approval للأوامر destructive؛ `TerminalExecutionGateway.kt:55-65` لا يستدعي `requestApproval` بل يعيد Disabled؛ `PermissionGovernanceLayer.kt:294-329` يدعم task/run/step اختيارياً، لا إلزامياً؛ `AgentLoop.kt:1000-1041` لا يملك typed terminal approval continuation.

المسار fail-closed ولا ينفذ الأمر destructive، لكن لا توجد موافقة قابلة للاستئناف ولا durable evidence يبين approval/denial. كما أن `TerminalExecutionGateway` لا يتحقق من task ownership عند governance.

**الإصلاح:** إمّا (أ) إعلان terminal read-only فقط، مع رفض destructive كـ `UNSUPPORTED` موثق؛ أو (ب) إنشاء approval durable إلزامي للأمر، حفظ canonical argv/hash والـ correlation، إيقاف step، ثم resume one-shot بعد تطابق approval/task/run/step وانتهاء صلاحية قصيرة. لا تعتمد على موافقة عامة أو text description.

### F-08 — AuditRepository fail-open وغير متزامن

**الشدة:** P2 للتشغيل، P1 للأدلة التنظيمية.  
**الأدلة:** `AuditRepository.kt:28-30,44-45,69-85` يصف fire-and-forget وfail-open؛ فشل DB يذهب إلى logcat فقط.

هذا يجعل stop أو terminal يبدوان مكتملين قبل ضمان كتابة الدليل، وقد يضيع الدليل عند process death.

**الإصلاح:** أضف durable outbox/transactional event، sequence number وidempotency key، وواجهة `appendRequired` للتنفيذات الأمنية. لا يلزم منع كل action عند عطل DB، لكن يجب إظهار `audit_degraded` ومنع claim بأن terminal evidence مكتمل.

## خطة إصلاح مرتبة

1. **P0:** إخفاء `SandboxExecutor` خلف Gateway، توحيد parser/argv validator، ومنع direct shell runner في production.
2. **P1:** إضافة `TerminalExecutionRequest` كامل correlation والتحقق من durable ownership داخل Gateway.
3. **P1:** إدخال generation/cancellation fence إلى Gateway وTerminalRuntime وAgentLoop callbacks، مع stop quiescence.
4. **P1:** بناء terminal evidence durable (start + exactly-one terminal) وربطه بالـ audit outbox والـ trace.
5. **P1:** redaction output/error مركزي قبل UI/model/activity/audit، مع منع raw output من `ToolResult`.
6. **P2:** حسم عقد approval للأوامر destructive: reject موثق أو durable approval/resume.
7. **P2:** تحسين audit failure semantics والـ degraded-mode telemetry.

## مصفوفة الاختبارات المقترحة (لا تُشغّل ضمن هذا التدقيق)

### argv وshell

- `echo safe; touch x`, `echo safe && touch x`, `echo $(id)`, backticks، newline، quote/glob: يجب الرفض، وألا ينشأ `x`.
- `cat ../secret`, absolute path، symlink إلى خارج workspace: يجب الرفض عبر Gateway وexecutor.
- `find -fprint`, `sed -i`, `sed ... e`, `awk -f`, `sort -o`, `git -c`, `git branch -D`, `git diff --output`: يجب الرفض حتى عند استدعاء runner الداخلي.
- `git status/log/diff/show` داخل workspace: يسمح فقط مع evidence كامل.
- env يحتوي `LD_PRELOAD`, `LD_LIBRARY_PATH`, `HOME`, secret key: لا يصل إلى child process.

### correlation والحوكمة

- كل request ناقص `project/task/run/step/execution/action/sourceSession`: deny قبل process start.
- task من project آخر، run قديم، step مكتمل، task cancelled، أو agentId غير `agent_loop`: deny.
- governance exception/rate limit/firewall deny: لا يبدأ process، ويسجل denial بنفس correlation.
- parallel duplicate fingerprint: process واحد فقط وterminal evidence واحد.

### secrets/output

- stdout/stderr يتضمن fake API key وPEM/password: safe output لا يحتويها؛ model/UI/activity/audit لا تكشفها.
- output يتجاوز 256 KiB: truncation معلنة، process ينتهي، evidence يسجل limit دون raw output.
- command يفشل برسالة تتضمن path/secret: الرسالة الخارجية redacted.

### cancellation/late callbacks

- block child process، ثم `ActiveWorkStopController.stopActiveUserWork()`: assert `cancel_requested` ثم terminal `CANCELLED` exactly once، و`cancel_confirmed` بعد انتهاء process.
- أطلق completion متأخراً بعد stop: لا output ولا `onStepComplete` ولا `ToolCompleted` ولا `Success` للـ old generation.
- ابدأ generation جديداً قبل completion القديم: callback القديم مرفوض.
- cancel قبل process start وبعد governance وقبل dispatch: لا process يبدأ.

### evidence/audit

- process death بعد start وقبل completion: يبقى `STARTED/ORPHANED` قابلاً للاسترداد، لا يختفي مع session.
- فشل Room أثناء audit: الحالة `AUDIT_DEGRADED`/outbox pending، وليس نجاحاً صامتاً.
- كل تنفيذ له exactly one of `COMPLETED/FAILED/TIMED_OUT/CANCELLED/REJECTED`، ولا terminal event بلا start أو بلا correlation.

## حكم الإصدار

**غير موصى بإغلاق مسار Terminal كـ production-auditable حالياً.** الحماية من shell injection جيدة في المسار المركزي، لكن لا يكفي ذلك: direct executor API، فقدان correlation، فقدان evidence بعد إغلاق session، raw output، وغياب late-callback fence تمنع ادعاء العزل والتدقيق الكامل. بعد تنفيذ P0 وP1 وإضافة الاختبارات أعلاه يمكن إعادة التقييم؛ لم تُجرَ اختبارات تنفيذية في هذه المراجعة.