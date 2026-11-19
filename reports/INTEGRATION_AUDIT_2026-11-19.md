# تقرير التدقيق النهائي لتكامل AIRI

**التاريخ:** 2026-11-19  
**النطاق:** تكامل واجهة الدردشة، التخطيط، حلقة الوكيل، استدعاء الأدوات، الذاكرة وRAG، التنفيذ الطرفي والعزل، GenUI، المهارات، والثيمات.  
**منهج الحكم:** تم الفصل بين **دليل المصدر** و**دليل التشغيل الفعلي**. وجود wiring في المصدر أو اختبارات تستخدم fakes/scripted fixtures لا يساوي نجاح runtime إنتاجي.  
**الحكم العام:** **PARTIAL / متصل مصدرّياً، غير مثبت تشغيلياً بصورة كاملة**.

## 1. الملخص التنفيذي

المسار الأساسي للدردشة ليس واجهة شكلية فقط. المصدر يثبت أن `ChatScreen` يجمع الحالة من `ChatViewModel` ويرسل الرسالة إلى `ChatViewModel.sendMessage`/`sendMessageWithAttachments`، وأن الـViewModel يبني كتالوج الأدوات وسياق RAG عند المسار المناسب، ثم يستدعي `AgentLoop.run` مع `HybridOrchestrator` و`ToolDispatcher` و`AgentSandbox` وسياق تنفيذ وهوية جلسة. وتثبت `AgentLoop` بدورها parsing لاستدعاءات الأدوات، والتحقق من الوسائط، والتفويض إلى `ToolDispatcher`، وإعادة `ToolResult` إلى history ثم إلى طلب النموذج التالي.

كما أن تكامل terminal، الذاكرة، المهارات، وGenUI موجود في المصدر عبر جسور تنفيذ فعلية، وليس مجرد interfaces غير مستخدمة. غير أن هذا الحكم هو **source-verified** بالدرجة الأولى. لم يثبت في هذه البيئة تشغيل UI حقيقي أو نموذج حي أو SSE حقيقي أو جهاز Android أو emulator؛ محاولة Gradle توقفت أثناء تهيئة المشروع بسبب غياب Android SDK وغياب `local.properties` و`ANDROID_HOME`/`ANDROID_SDK_ROOT`. لذلك لا يجوز إعلان نجاح runtime أو APK أو instrumentation.

أهم نتيجة معمارية هي أن `ToolExecutionPipeline` ليس في سلسلة الدردشة الطبيعية. فهو موصول بمسار coding عبر `CodingAgentRuntime`، بينما مسار الدردشة يمر مباشرة من `AgentLoop` إلى `ToolDispatcher` وhandlers/bridges. وعليه لا يوجد دليل أن validation/authorization/approval/rate-limit/sandbox/ledger الخاص بـ`ToolExecutionPipeline` يحمي كل أدوات الدردشة.

الذاكرة متصلة فعلياً بمسارين: حقن RAG قبل بناء system prompt، وأداة `memory_recall` داخل `ToolDispatcher`. لكن embedding اختياري وغير مضمون وقت التشغيل، ويعتمد على نموذج GGUF وJNI؛ كما يوجد fast path في `ChatViewModel` يمكنه إنهاء الطلب قبل بناء RAG، ومسار `selectedKnowledgeContext` يتجاوز framing الموحد لـRAG. هذه فجوات P0/P1 وليست دليلاً على انقطاع كامل.

GenUI متصل مصدرّياً من `render_ui` إلى compiler وCompose renderer ثم staging/claim والتنفيذ عبر رسالة AIRI_UI_ACTION، لكنه ليس استدعاء connector أو terminal مباشراً من renderer، ولا يوجد اختبار E2E يثبت الضغط ثم dispatch ثم الأثر الخارجي.

الألوان ليست موحدة بالكامل. المصدر النهائي في `Theme.kt` يعرّف مخططات Material، لكن `DesignSystem.kt` وبعض الشاشات ما زالت تستخدم literals وbrand/status colors مباشرة. لذلك تُعتمد نتيجة المصدر النهائي لا التقارير التاريخية التي ادعت PASS أو توحيداً كاملاً.

## 2. جدول حالة المسارات

| المسار | الحالة من المصدر | ما يثبت فعلياً | ما لم يثبت | الحكم |
|---|---|---|---|---|
| ChatScreen → ChatViewModel | متصل | `ChatScreen.kt` يجمع StateFlows ويستدعي `sendMessage`/`sendMessageWithAttachments`؛ الـViewModel يربط callbacks والإلغاء والحالة | تشغيل Compose فعلي بعد rotation/process death | متصل مصدرّياً |
| ChatViewModel → Planner | متصل | `ChatViewModel.kt:2991` يستدعي `requestPlanningService.plan`، و`AgentRequestPlanningService` يستدعي `AgentRequestPlanner` | اختبار من request boundary إلى persistence على جهاز | متصل مصدرّياً |
| Planner → AgentLoop | متصل | `requestPlan` و`requiredToolNames` يمران إلى `agentLoop.run` | قرار planner حي من نموذج فعلي | متصل مصدرّياً |
| AgentLoop → Orchestrator/LLM | متصل | `AgentLoop` يمرر الطلب إلى `HybridOrchestrator` ويستقبل tokens/results | provider حي وSSE وشبكة | متصل مصدرّياً |
| AgentLoop → ToolDispatcher | متصل | parsing، validation، sandbox/approval branches، ثم `ToolDispatcher.execute` | UI-to-tool runtime كامل | متصل مصدرّياً |
| ToolDispatcher → skill/connector/memory | متصل | `skill_*` و`connector_*` و`memory_recall` تمر إلى bridges/manager حقيقية | نجاح skill أو connector حي | متصل مصدرّياً |
| ToolDispatcher → terminal | متصل ومقيّد | `TerminalExecutionGateway` ينشئ sandbox مؤقتاً ويمرر إلى `SandboxExecutor` مع policy/governance | instrumentation وdevice isolation | متصل مصدرّياً |
| AgentLoop → ToolExecutionPipeline | غير متصل في مسار الدردشة | pipeline موصول بـ`CodingAgentRuntime` | حماية أدوات Chat عبر pipeline | غير مثبت/منفصل |
| Tool calls المتتابعة | مدعومة مصدرّياً | history وtool_result يعادان إلى الطلب التالي؛ اختبارات scripted تغطي العقد | نموذج حي يختار call ثانياً بعد النتيجة | جزئي |
| Memory/RAG → system prompt | متصل بشروط | `RagRetriever.buildContextBlock` قبل system prompt مع filters/envelope | embedding حقيقي، payload نهائي، fast path | متصل مصدرّياً |
| GenUI | متصل مصدرّياً | compiler، schema، renderer، gate، staging/claim، status bus | ضغط UI وأثر خارجي E2E | جزئي |
| Skills | متصل ومحمّي مصدرّياً | registry، manifest/policy، bridge suspend، timeout، untrusted envelope | runtime trace فعلي وانتحال custom على جهاز | جزئي |
| الألوان | جزئي | `AiriTheme` وMaterial schemes موجودة | توحيد literals وcontrast/screenshot لكل modes | غير مكتمل |
| Profile photo | **local-only متصل (P1-11 محسوم)** | picker، MIME/UID/5MiB، UID-keyed `UserProfileRepository.activateAccount`، `cachePhotoForAccount` و`localPhotoPath` | cloud upload أو `avatarUrl` sync | مقصود: لا يوجد cloud avatar backend في هذا الإصدار |
| Secret Manager | UI/control-flow متصل | BiometricPrompt، lock/unlock، ON_STOP، re-auth للعمليات | حماية كل consumers وhardware-backed runtime | جزئي |

## 3. خريطة الـwiring الفعلية

### 3.1 مسار الدردشة

```text
ChatScreen.kt
  └─ StateFlows من ChatViewModel
  └─ sendMessage / sendMessageWithAttachments
       └─ ChatViewModel.kt
            ├─ ServiceLocator.ragRetriever.buildContextBlock (المسار الكامل فقط)
            ├─ requestPlanningService.plan
            │    └─ AgentRequestPlanningService.kt
            │         └─ AgentRequestPlanner.plan
            ├─ RuntimeToolCatalog / liveSkillSchemas
            ├─ AgentLoop(...)
            │    ├─ HybridOrchestrator
            │    ├─ ToolDispatcher
            │    ├─ ServiceLocator.agentSandbox
            │    ├─ calendarCreateRuntime
            │    └─ UniversalRuntimeTraceRecorder
            └─ agentLoop.run(...)
                 ├─ onToken / stream callbacks
                 ├─ StepEvent.ToolExecuted
                 ├─ confirmation decision
                 └─ final result/state publication
```

المواضع المحورية في المصدر: `ChatViewModel.kt:621-634` لإنشاء `AgentLoop`، `:2820-2838` لـRAG، `:2991` للتخطيط، `:3378-3547` للتنفيذ، و`:3427-3537` لمعالجة `ToolExecuted` والـconfirmation. في الواجهة، `ChatScreen.kt:238-248` يجمع الحالة، و`:651-652` و`:972-1023` يرسلان الرسائل.

### 3.2 دورة الوكيل والأداة

```text
AgentLoop.run
  ├─ AgentOrchestrator / HybridOrchestrator
  ├─ NativeToolCallWire أو TextToolCallProtocol
  ├─ StrictArgumentValidator
  ├─ ToolCallLedger.tryClaim / fingerprint
  ├─ AgentSandbox عند الحاجة
  ├─ ToolDispatcher.execute
  │    ├─ memory_recall → MemoryManager
  │    ├─ skill_* → SkillToolBridge
  │    ├─ connector_* → ConnectorToolBridge
  │    ├─ terminal_execute → TerminalExecutionGateway
  │    ├─ render_ui → GenerativeUiCompiler
  │    └─ باقي handlers والـapproval gates
  ├─ ToolResultVerifier
  ├─ assistant envelope + ConversationTurn.ToolResult إلى history
  └─ role=tool/tool_result إلى طلب النموذج التالي
```

`AgentLoop.kt:767-795` يفسر tool calls، و`:1185-1202` يتحقق من الوسائط، و`:1416-1428`, `:1484-1495`, `:1518-1523`, `:1600-1605` يستدعي dispatcher. يمنع `ToolResultVerifier` إعلان `TOOL_COMPLETED` عند فشل التحقق (`:1650-1721`). الإلغاء متصل من UI (`ChatViewModel.kt:946-961`) إلى loop، مع منع نشر نجاح بعد الإلغاء (`:3568-3576`).

### 3.3 الخدمات المركزية

`ServiceLocator.kt` ينشئ `AgentSandbox` (`:197-199`)، يربط `SandboxManager` بـ`WorkspaceRuntime` (`:335-356`)، وينشئ `TerminalExecutionGateway` و`TerminalRuntime` (`:383-398`)، وsingleton `MemoryManager` (`:489-491`)، ثم `RagRetriever` فوق `MemoryManager` و`ProjectContextResolver` و`MemoryStoreRagFactSource` (`:739-745`).

## 4. تحليل دورة Planner / AgentLoop / ToolDispatcher / ExecutionPipeline

### Planner

التخطيط ليس اسماً في الوثائق فقط: `ChatViewModel` يستدعي `requestPlanningService.plan`، والخدمة تفوض إلى `AgentRequestPlanner.plan`، ثم يمرر الناتج إلى `AgentLoop.run` بوصفه `requestPlan` و`requiredToolNames`. هذا يثبت دخول planner في مسار الدردشة.

### AgentLoop

الحلقة تضع حدوداً واضحة: `MAX_STEPS=16`، حد زمني صلب يقارب 15 دقيقة، وstall timeout افتراضي 120 ثانية. عند استنفاد الحد، يوجد طلب تلخيص نهائي بلا أدوات. هذه الحدود تمنع loop لا نهائياً، لكنها تعني أن correctness بعد الحد تعتمد على التلخيص لا على استمرار التنفيذ.

الـparser يدعم envelope النصي `{tool_call}` و`{tool_calls}` عبر `TextToolCallProtocol`، والمسارات native عبر `NativeToolCallWire`. توجد ملاحظة سلامة/عقد: parser يبحث عن marker ويحل أول JSON مطابق وقد يقبل prose محيطاً بالـenvelope، رغم أن prompt يطلب JSON فقط؛ ينبغي حسم ذلك بعقد صريح أو رفض الردود المختلطة.

### ToolDispatcher

`ToolDispatcher` ليس stub. فهو محقون بـ`MemoryManager` و`SkillToolBridge` و`ConnectorToolBridge` و`ArtifactManager`. `memory_recall` يستخدم semantic search أو recent fallback، و`skill_*` و`connector_*` يصلان إلى جسور تنفيذ فعلية. terminal يمر عبر `TerminalExecutionGateway` لا عبر `SandboxExecutor` مباشرة من AgentLoop، مع correlations وسياق تنفيذ.

### ExecutionPipeline

`ToolExecutionPipeline.kt:108-225` يطبق parse/validate/authorization/approval/rate-limit/sandbox/handler/ledger، لكنه مسار مستقل. `ProductionCodingRuntimeFactory` و`CodingAgentRuntime` و`CodingAgentOrchestrator` تربطه بمسار coding. لا يوجد استدعاء pipeline من `AgentLoop` أو `ToolDispatcher` أو `ChatViewModel`. كذلك `ExecutionGraphRuntime`/`AdaptiveGraphEngine` مساران مستقلان. لذلك لا يجوز وصف السلسلة التالية على أنها مثبتة: `Planner → AgentLoop → ToolDispatcher → ToolExecutionPipeline`.

**الاستنتاج:** يلزم إما إدخال `ToolDispatcher` خلف pipeline، أو إنشاء adapter رسمي يطبق نفس عقود التحقق والتفويض والموافقة والعزل والـledger على مسار Chat، مع اختبار رأسي يثبت ذلك.

## 5. دليل tool calls المتتابعة

الدليل المصدرّي على الاستمرارية قوي:

1. `AgentLoop` يضيف رد المساعد و`ConversationTurn.ToolResult` إلى history (`:1755-1777`).
2. يحولها إلى `role=tool` و`kind=tool_result` (`:1990-2005`).
3. يرسل `conversationHistory` في الطلب التالي (`:2058-2100`).
4. `AnthropicPayloadContract` و`GeminiAdapter` يترجمان النتائج إلى `tool_result`/`functionResponse` المناسبين للمزود.
5. adapters تجمع tool-call deltas من SSE، و`NativeToolCallWire` يتعامل مع الوسائط المقطوعة دون تحويل صامت إلى `{}`.
6. `ToolCallBatchExecutor` يسمح بالتوازي فقط لقراءات مستقلة ذات IDs فريدة؛ side effects أو IDs المكررة تمنع التوازي. `ToolCallLedger` يمنع duplicate execution.
7. `AgentLoopVerticalIntegrationTest` يثبت عقداً scripted لاستدعاءات متتابعة وعودة النتيجة إلى الطلب التالي، واختبارات batch تغطي التوازي والترتيب والتكرار.

لكن هذا ليس دليلاً على نموذج حي. `ScriptedOrchestrator` يعيد نصوصاً ثابتة، ولم تُشغل الاختبارات أصلاً في هذه البيئة. كما أن اعتماديات batch مثل `$call-1` أو `{{call-1}}` لا تملك مرحلة materialization مثبتة؛ قد يُمنع التوازي لكن تمرر القيمة النصية الخام. يلزم resolver صريح يخزن نتائج calls السابقة ويستبدل placeholders أو يعيد `dependency_unresolved` منظماً.

كذلك `take(4000)` قد يقص نتيجة أداة قبل الدور التالي دون chunking أو marker معلن. يجب تحويل ذلك إلى سياسة موثقة وقابلة للاختبار.

## 6. دليل memory-to-context

المسار الفعلي:

```text
Chat/explicit admission
  └─ MemoryManager
       ├─ Room MemoryDao / MemoryStore mirror
       ├─ optional embedding عبر EmbeddingService + GGUF/JNI
       └─ facts وفق MemoryAdmissionPolicy
            └─ RagRetriever
                 ├─ long-term memory
                 ├─ scratchpad
                 ├─ semantic أو recent fallback
                 ├─ project knowledge
                 ├─ scope/privacy/expiry/sensitivity filters
                 └─ UntrustedContentEnvelope + budget
                      └─ PromptService / system prompt
                           └─ AgentLoop/model request
```

`ChatViewModel.kt:2822-2837` يستدعي `ServiceLocator.ragRetriever.buildContextBlock` قبل بناء system prompt، ثم يمرر `ragContext` إلى `buildGenerationSystemPrompt` (`:2861-2869`) و`PromptService.buildSystemPromptWithContext` (`:4312-4325`). `RagRetriever.kt:113-228` يجمع المصادر، و`:302-345` يطبق visibility filters. `PromptService.kt:241-245` يضع boundary بأن memory/knowledge/tool outputs بيانات غير موثوقة وليست تعليمات.

نقاط القصور:

- fast path في `ChatViewModel` يمكن أن ينهي الطلب قبل RAG؛ لذلك RAG ليس مضموناً لكل رسالة.
- embedding يتطلب ملف GGUF وJNI؛ عدم الجاهزية يعطي `EMBEDDING_SKIPPED`/`VECTOR_SEARCH_SKIPPED` وfallback زمنياً، ولم يثبت runtime وجود vectors.
- `MemoryManager.recordChatMessage` يكتب row ثم يطلق embedding عبر `SupervisorJob` fire-and-forget؛ لا يظهر outbox/retry durable بعد فشل native أو توقف العملية.
- `selectedKnowledgeContext` يضاف مباشرة إلى base system prompt، خارج `UntrustedContentEnvelope` الموحد؛ يلزم تمريره عبر framing والـownership/privacy/budget نفسه.
- يوجد عدم اتساق محتمل بين السماح بـUSER scope في `getScopedLongTermMemories` ومنعه في `isScopedLongTermMemoryVisible`، خصوصاً في mirrored scratchpad facts.
- semantic candidate query مقيدة بـsession/dimension ثم تعتمد على post-filter Kotlin؛ الأفضل إضافة constraints في DAO أو hydrate محكوم مع post-filter كدفاع ثانٍ.

اختبار `MemoryStoreRagIntegrationTest` يثبت wiring وframing، لكنه لا يثبت دورة `recordChatMessage → Room → embedding حقيقي → retrieval → ChatViewModel → model payload`، ولم يُشغّل في هذه البيئة.

## 7. حالة GenUI

GenUI ليس UI-only على مستوى المصدر:

```text
render_ui tool
  └─ GenerativeUiCompiler / GenerativeUiSchema
       ├─ fenced airi-ui
       ├─ version/size/type/key allow-list
       ├─ unique IDs وroute/diagram/table/form checks
       └─ canonical rebuilt action
            └─ ContentAwareMessageRenderer / ChatScreen
                 └─ GenerativeUiRenderer (Compose)
                      └─ preview/confirmation/local state
                           └─ PendingGenerativeUiActionGate
                                └─ AIRI_UI_ACTION إلى ChatViewModel
                                     └─ claim → AgentLoop/tool path
```

المصدر يثبت compiler (`GenerativeUiSchema.kt:219-560`)، مرور `render_ui` عبر compiler (`RenderUiTool.kt` و`ToolDispatcher.kt:735-748`)، العرض (`ContentAwareMessageRenderer.kt` و`GenerativeUiRenderer.kt`)، والتحقق من `sourceMessageId` و`sourceExecutionId` ثم STAGED/RUNNING/SUCCEEDED/FAILED (`ChatViewModel.kt:2360-2394`, `:3191-3197`, `:3944-3976`). لا ينفذ renderer connector أو terminal مباشرة؛ الأثر مشروط برسالة action ثم اختيار الأدوات والموافقة.

القصور: `GenerativeUiStructuredOutput.kt` يعلن `strict=false` ومخططاً سطحياً وmax pattern مختلفاً عن compiler (`64` مقابل `48`). كما لا يوجد اختبار يثبت control click إلى connector محدد أو حالات cancellation/timeout/approval denial/provider failure حتى terminal outcome. يجب توحيد schema والحدود وإضافة اختبار E2E.

## 8. حسم تناقض الألوان من المصدر النهائي

المصدر النهائي هو المرجع، لا التقارير التاريخية. `Theme.kt:20-67` يعرّف `DarkColorScheme` و`LightColorScheme`، و`:85-105` يعرّف AMOLED، و`:110-128` يختار حسب `ThemeMode`. هذا يثبت وجود بنية ثيم Material صحيحة.

لكن `DesignSystem.kt:102-165` ما زال يستخدم gradients/surfaces ثابتة، و`ProfileScreen.kt:365-370,401-405` و`SecretManagerScreen.kt:197,541-543,578,601` تستخدم ألواناً ثابتة أو `CosmicAccent`/`SemanticError` خارج المخطط. لذلك:

- لا يصح اعتماد عبارة «تم توحيد كل الألوان» أو «لا توجد literals».
- بعض literals قد تكون brand/status/decorative مقصودة وليست كلها خطأ، لكن يلزم inventory وتصنيف آلي.
- لا يوجد تدقيق بصري أو contrast matrix موثق لـLight/Dark/System/AMOLED.
- الحكم النهائي: **الثيم الأساسي متصل، والتوحيد الكامل غير مكتمل (PARTIAL)**.

## 9. runBlocking والعزل الأمني للمهارات

### runBlocking

البحث في `app/src/main/java` و`core-domain/src` و`app-desktop/src` لم يجد استدعاء `runBlocking` تنفيذي في المسار المدقق. ظهوره في `CloudBackend.kt` وKDoc في `BehaviorEngine.kt` توثيقي فقط. `SkillToolBridge.invoke` دالة `suspend`، وتستدعي `skill.execute` داخل `withTimeout`، و`ToolDispatcher` يستدعيها من المسار المعلق. لذلك لا يوجد دليل على حجب coroutine عبر `runBlocking` في wiring الحالي. لكن لا يمكن تحويل ذلك إلى ضمان runtime عام دون build/trace، كما ينبغي إبقاء فحص static في CI.

### العزل والصلاحيات

`SkillRegistry` يبني descriptors من `OfficialSkillLibrary.ALL` ويفصل المصانع الرسمية عن `CustomSkillAiriSkillAdapter`. `SkillInvocationAccessPolicy` يرفض الرسمي بلا manifest ويجمع صلاحيات manifest. `SkillToolBridge` لا يثق بالمهارة الرسمية اعتماداً على `isOfficial` وحده؛ membership في الكتالوج/manifest canonical مهم، ويمسح `configValues` و`secretAccess` للمهارات غير الرسمية، ويغلف نتائجها في `UntrustedContentEnvelope`. توجد typed argument contracts وtimeout وauthorization.

هذا تصميم fail-closed جيد مصدرّياً، لكنه يحتاج اختباراً مباشراً لمحاولة custom skill انتحال ID/official، واختبار timeout/cancellation وtrace فعلي من AgentLoop إلى `skill.execute`. كما أن `SkillManifest.isOfficial` حقل قابل للتصريح؛ الثقة يجب أن تبقى مرتبطة بالعضوية canonical لا بالعلم وحده.

## 10. ما هو UI-only وما هو متصل فعلاً

**متصل فعلاً مصدرّياً:** ChatScreen state/send، planner، AgentLoop، ToolDispatcher، memory_recall، skill/connector bridges، terminal gateway، RAG prompt assembly، GenUI compiler/gate/status، local profile photo caching، وbiometric control-flow داخل SecretManagerScreen.

**UI-only أو غير مكتمل الأثر الخارجي:** GenUI renderer قبل action/claim لا ينفذ side effect؛ profile photo المثبتة local cache وليست cloud upload؛ SecretManagerScreen يثبت gate للواجهة وليس حماية كل قراءات SecretVault؛ الألوان مجرد tokens/values ما لم تستخدمها كل الشاشات؛ تقارير النجاح التاريخية ليست runtime evidence.

**غير مثبت تشغيلياً:** رد نموذج حي، provider tool-choice، SSE round-trip، device terminal isolation، skill execution على جهاز، upload avatar سحابي، biometric lifecycle على جهاز، وE2E من ChatScreen حتى persistence.

## 11. قائمة الواجهات غير المكتملة أو ذات العقد غير المحسوم

1. لا توجد طبقة باسم `sendbox`؛ الموجود `sandbox`. إذا كانت التسمية مقصودة لمكوّن مختلف فهو غير موجود وقابل للتدقيق غير متاح.
2. `ToolExecutionPipeline` لا يحمي مسار Chat AgentLoop/ToolDispatcher.
3. لا يوجد resolver مكتمل لاعتماديات batch placeholders.
4. fast path قد يتجاوز RAG.
5. selected knowledge bypass يتجاوز envelope والـprivacy/budget الموحد.
6. embedding fire-and-forget بلا outbox/retry/reconciliation مثبت.
7. USER visibility بين long-term وmirror/scratchpad غير موحد بوضوح.
8. schema GenUI strictness/حدود IDs غير متطابقة بين provider pre-schema وcompiler.
9. profile photo لا تثبت cloud upload أو `avatarUrl` synchronization؛ المثبت local-only.
10. SecretVault نفسه لا يظهر في الأدلة كحارس capability/reauth لكل consumer بدلاً من حراسة الشاشة فقط.
11. readiness/connector permission changes بعد إنشاء ViewModel تحتاج lifecycle assertion.
12. لا يوجد contract موحد لـterminal outcome/trace/cancellation بين AgentLoop وToolExecutionPipeline وExecutionGraphRuntime.
13. parser لا يحسم رفض prose خارج tool envelope.
14. truncation لـtool result إلى 4000 قد يكون صامتاً.
15. لا يوجد اختبار يثبت أن كل side-effect handler يملك semantic verification لا مجرد نجاح نصي.

## 12. عوائق البناء الحالية

- `local.properties` غير موجود في `/home/ubuntu/AIRI-Project`.
- `ANDROID_HOME` و`ANDROID_SDK_ROOT` فارغان.
- Gradle يفشل أثناء configuration برسالة SDK location not found قبل compilation وقبل تشغيل الاختبارات.
- لا يوجد emulator أو connected Android device متاح.
- لم يثبت توفر `javac`/JAVA_COMPILER في البيئة السابقة؛ يجب التحقق منه في CI بعد إصلاح SDK.
- لا تتوفر credentials/provider harness لإثبات OpenAI/Gemini/Anthropic أو نموذج محلي حي.
- embedding runtime يحتاج GGUF وLlamaNative JNI، ولم يثبت توفرهما.
- instrumentation الخاص بـGenUI وterminal وmemory وbiometric لم يُنفذ.
- محاولة Gradle سببت تغييرات في `.gradle/buildOutputCleanup` فقط؛ لا توجد في هذه المهمة تعديلات على كود التطبيق.

## 13. خطة الإصلاح المرتبة

### P0 — قبل إعلان CONNECTED أو PASS

1. **توفير بيئة بناء قابلة للتكرار:** تثبيت Android SDK/JDK، تعريف `sdk.dir` أو `ANDROID_HOME` في CI، وتشغيل `:app:testDebugUnitTest` وconnected/instrumentation tests. معيار القبول: compilation ناجح، نتائج الاختبارات محفوظة، وعدم الاكتفاء بمخرجات source scan.
2. **اختبار رأسي لمسار الدردشة:** تركيب production-like graph يبدأ من request/`ChatViewModel` ويمر عبر planner ثم `AgentLoop` ثم `ToolDispatcher` ثم handler وverification وhistory/persistence. معيار القبول: trace مترابط يثبت requestId وtoolCallId وtool result والرد النهائي.
3. **حسم boundary الأدوات:** إدخال `ToolDispatcher` خلف `ToolExecutionPipeline` أو إنشاء adapter رسمي يضمن parse/validation/authorization/approval/rate-limit/sandbox/ledger لجميع أدوات Chat. معيار القبول: اختبار deny وapproval وrate-limit وduplicate وsandbox على مسار Chat نفسه.
4. **اختبار provider round-trip:** fake HTTP SSE server على حدود adapter، native tool call ثم `tool_result` ثم turn ثانٍ/ثالث. معيار القبول: payloads وSSE وcontinuation موثقة، دون `ScriptedOrchestrator` وحده.
5. **إصلاح تسرب سياق RAG:** إزالة/تقييد fast-path الذي يتجاوز RAG، وتمرير `selectedKnowledgeContext` عبر envelope وownership/privacy/expiry/budget الموحدة. معيار القبول: اختبار fast hit وknowledge context وcross-session/project لا يسمح بتجاوز السياسة.
6. **إكمال وعاء embedding:** outbox durable keyed by messageId مع retry/reconciliation وmetrics. معيار القبول: فشل JNI أو توقف العملية لا يفقد embedding إلى الأبد، والـretrieval trace يميز pending/created/failed.
7. **إثبات العزل الطرفي والمهارات:** connected/instrumentation tests لمسار terminal و`SkillToolBridge`, مع allow/deny/timeout/cancellation/custom impersonation. معيار القبول: trace يثبت policy decision والعزل والنتيجة، لا مجرد وجود الكود.

### P1 — توحيد العقود وزيادة التغطية

1. إضافة resolver صريح لاعتماديات `$call-id` و`{{call-id}}` أو رفضها برسالة `dependency_unresolved`.
2. جعل parser يرفض prose المختلط أو توثيق envelope extraction رسمياً مع اختبارات before/after/partial/malformed.
3. استبدال `take(4000)` الصامت بـchunking أو structured truncation marker.
4. توحيد terminal outcome وtrace/cancellation بين AgentLoop وToolExecutionPipeline وExecutionGraphRuntime.
5. إضافة semantic verification لكل side-effect handler وربط قرار completion بالدليل الناتج.
6. توحيد USER visibility بين `getScopedLongTermMemories` و`isScopedLongTermMemoryVisible` مع اختبارات حذف/expiry/mirror.
7. جعل vector candidate query تحمل قيود scope/privacy/project في DAO أو join محكوم، مع post-filter دفاعاً ثانياً.
8. توحيد GenUI schema/compiler، خصوصاً `strict` وMAX_ID_CHARS، وإضافة E2E control click → claim → AgentLoop → fake connector → final status.
9. إنشاء اختبار مباشر لـ`SkillToolBridge.invoke` يغطي manifest canonical، custom impersonation، typed arguments، timeout، cancellation، والـuntrusted output.
10. تنفيذ inventory آلي لكل `Color(...)` و`Color.White/Black` و`Cosmic*`/`Semantic*` خارج theme، ثم screenshot/contrast tests لكل ThemeMode.
11. **P1-11 محسوم: avatar local-only.** لا يوجد authenticated cloud storage ولا `avatarUrl` sync في هذا الإصدار؛ المنتج يصرّح بذلك، والملف المحلي مفاتيحه UID، مع اختبار تبديل الحساب لمنع تسريب الصورة بين الحسابات.
12. نقل biometric capability إلى خدمة/SecretVault قابل للاختبار أو فرض reauth على كل consumer، ثم اختبار background/foreground/rotation/process recreation/unavailable/cancel/failure.

### P2 — التحسين والتوثيق

1. إضافة telemetry redacted يربط model turn وtool dispatch وresult-returned وnext model request.
2. توحيد وثائق المسارات وفصل Chat AgentLoop عن CodingAgentRuntime وExecutionGraphRuntime وVoice/Scheduled/Sub-agent.
3. تحديث وثائق MemoryExtractor لتفصل candidate extraction عن allow-listed durable facts.
4. توثيق أن semantic RAG غير مضمون دون GGUF/JNI وأن fallback زمني مقصود.
5. أرشفة التقارير التاريخية ووسمها Historical، وإصدار تقرير واحد يميز source-verified عن runtime-verified.
6. توثيق `sandbox` بالاسم الصحيح أو إنشاء contract واضح إن كانت `sendbox` تسمية مطلوبة.
7. إضافة assertions lifecycle لـStateFlow/listeners بعد rotation وprocess restart، مع منع تكرار أو فقد execution events.

## 14. الخلاصة والحكم النهائي

المشروع يملك بنية تكامل حقيقية ومتماسكة في المصدر لمسار الدردشة الأساسي: `ChatScreen → ChatViewModel → Planner/RAG/tool catalog → AgentLoop → Orchestrator/LLM → ToolDispatcher/bridges → verification/history → UI`. terminal وmemory وskills وGenUI ليست مجرد واجهات معلقة، لكن اكتمالها التشغيلي لم يثبت.

في المقابل، لا يمكن اعتماد التقرير كنجاح runtime: Gradle لم يصل إلى compilation بسبب Android SDK، ولا يوجد جهاز أو emulator، ولا provider/model harness حي. كما أن `ToolExecutionPipeline` منفصل عن Chat، وRAG/embedding وGenUI والألوان والمهارات تحتوي حدوداً موثقة تحتاج إصلاحاً أو اختباراً إضافياً.

**الحكم النهائي:** **PARTIAL — Source-connected, runtime-unverified, with P0 integration/security gaps.**
