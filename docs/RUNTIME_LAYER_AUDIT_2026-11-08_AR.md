# تدقيق طبقة Runtime في AIRI

## النتيجة

طبقة Runtime موجودة فعلياً وليست اسماً تجميلياً. `ExecutionGraphRuntime` يدير خطة التنفيذ، يبني موجات DAG، يشغل العقد المستقلة بالتوازي، يمرر نتائج الاعتماديات، يحفظ snapshots، ويدعم الإلغاء والاستئناف.

تم العثور على مشكلة مثبتة: كان هناك `Mutex` واحد حول كامل Runtime، لذلك كانت خطة طويلة تمنع خطة مستقلة من البدء. هذا يخالف هدف runtime متعدد التنفيذات، رغم أن `ProductionAgentOrchestrator` نفسه مصمم لتنفيذ الوكلاء المستقلين بالتوازي.

## التغيير المنفذ

استُبدل القفل العام بسجل `planLocks` keyed by deterministic `planId`:

- تنفيذان لخطة مختلفة يمكن أن يتقدما بالتوازي.
- استئنافان لنفس الخطة لا يتداخلان.
- snapshot loading وactive job registration وmarkCancelled ما زالت ضمن قفل الخطة.
- لم تتغير عقود `resume`, `execute`, `cancel`, `currentSnapshot` أو `ExecutionGraphEvent`.

## حدود الطبقات

- **AgentLoop:** يقرر استدعاء الأدوات ويتولى دورة LLM/tool-call. Runtime لا يعيد تنفيذ قرار الوكيل.
- **Runtime:** ينفذ ActionPlan كـDAG ويضبط الاعتماديات، timeouts، snapshots، retries التي يوفرها orchestrator، والإلغاء.
- **RAG/Memory:** يبقى مصدر السياق والاسترجاع داخل AgentLoop/Memory layer. لا توجد إضافة RAG مكررة داخل Runtime؛ هذا يحافظ على single ownership ويمنع استرجاع السياق مرتين.
- **Sandbox:** AgentLoop يمرر الأدوات الحساسة عبر `AgentSandbox`/`SandboxExecutor`. Runtime يملك orchestration ولا يتجاوز sandbox policy.
- **Terminal:** `TerminalRuntime` يمر عبر `TerminalExecutionGateway` ثم `SandboxManager`; لا يوجد مسار Runtime مباشر إلى ProcessBuilder.

## مراجعة Sandbox وTerminal

`SandboxExecutor` يستخدم argv execution دون shell، allowlist، منع metacharacters، scrubbed environment، حدود output/file، timeout، وتنظيف process في `finally`. هذه ضوابط مثبتة باختبارات `SandboxExecutorSecurityTest`.

`TerminalRuntime` يمنع تداخل أمرين عبر `AtomicBoolean`, يربط الإلغاء بـJob، ولا يعرض الأمر غير المنقح في السجل أو الواجهة. وتبقى صلاحية الأمر عند `PermissionGovernanceLayer` و`TerminalExecutionGateway` قبل sandbox.

ملاحظة lifecycle لاحقة: `SandboxManager` و`TerminalRuntime` يملكان scopes طويلة العمر ولا يقدمان API موحداً لـclose؛ لم أغيرهما في هذه المرحلة لأن ذلك يحتاج ربطاً واضحاً بعمر ViewModel/service واختبارات lifecycle منفصلة.

## التحقق

- `git diff --check`: ناجح.
- `python3 tools/verify_core_changes.py`: `100/100`.
- اختبارات Runtime الحالية تغطي stable plan IDs وcancelled snapshot.
- يلزم تشغيل Android CI للتحقق من compile/unit/instrumentation بعد تغيير القفل لكل خطة؛ لم يتم ادعاء نجاح Gradle محلياً قبل تشغيله.
