# AIRI Runtime Call-Site Inventory

**تاريخ الجرد:** 2026-10-08  
**HEAD:** `bf8750e3`  
**الفرع:** `main` مطابق لـ`origin/main`  
**الطريقة:** بحث call sites في المصدر ثم مطابقة التسجيل والإعلان والتفويض والاستدعاء؛ لا يوجد Android SDK في البيئة، لذلك لا توجد device evidence.

## تعريف الحالات

- **defined:** النوع أو التنفيذ موجود في المصدر.
- **registered:** مربوط في registry/service locator/runtime bootstrap.
- **advertised:** يظهر في catalog/schema المرسل إلى model أو UI.
- **authorized:** يمر عبر policy/permission/gate قبل التنفيذ.
- **invoked:** يوجد caller فعلي يصل إلى handler/bridge.
- **device-proven:** ثبت على emulator أو جهاز Android فعلي مع postcondition. هذه الحالة لم تثبت في هذا الجرد.

## الجرد العمودي

| المسار | defined | registered | advertised | authorized | invoked | device-proven | الدليل الحالي |
|---|---:|---:|---:|---:|---:|---:|---|
| Chat text → AgentLoop | نعم | نعم | نعم | نعم | نعم ساكنًا | لا | `ChatViewModel.sendMessageInternal` → `CapabilityIntentDetector`/`RuntimeToolCatalog` → `AgentLoop.run` |
| Fast response في Chat | نعم | نعم | لا ينطبق | نعم | نعم | لا | فرع fast response داخل `ChatViewModel`؛ يجب ألا يتوازى مع AgentLoop لنفس request |
| Builtin tools | نعم | نعم | نعم بحسب catalog/intent | نعم | نعم | لا | `BuiltinTools.ALL` → `RuntimeToolCatalog.assemble` → `ToolDispatcher` |
| Skills | نعم | نعم | نعم للجاهز | نعم | نعم | لا | `SkillRegistry` → `SkillToolBridge.asToolSchemas/invoke` → `ToolDispatcher` |
| Connectors | نعم | نعم | نعم للمتصل/الصحي/المصرح | نعم | نعم للموصلات الجاهزة | لا | `ConnectorBootstrap/Registry` → `ConnectorToolBridge` → `ConnectorRuntimeManager` |
| Terminal UI | نعم | نعم | UI path | نعم | نعم | لا | `TerminalRuntime` → governance → `SandboxExecutor` |
| `terminal_execute` من AgentLoop | نعم | نعم | نعم في ACTION فقط | نعم | نعم ساكنًا عبر `AgentSandbox`/dispatcher | لا | `AgentPermissionProfile`, `AgentLoopSideEffectPolicy`, `AgentLoopTaskRuntime`, `ToolPermissionPolicy` |
| Accessibility AndroidAgent | نعم | نعم | مسار Agent مستقل | نعم جزئيًا | نعم عند service enabled | لا | `AndroidAgent` → `AccessibilityExecutionEngine` → `AiriAccessibilityService` |
| Accessibility builtin actions | نعم | نعم | بحسب catalog | نعم في policy | نعم ساكنًا | لا | `ToolDispatcher` actions `read_screen/open_app/tap/type_text/scroll_down/go_back` |
| Flash/Torch | لا كأداة تنفيذ | لا | patterns intent فقط | لا | لا | لا | لا يوجد `CameraManager.setTorchMode` أو tool handler؛ لا يُرفع claim من تصنيف الطلب |
| Wi-Fi/Bluetooth/Hotspot | لا كأداة تنفيذ عامة | لا | patterns intent فقط | لا | لا | لا | لا يوجد Android API/tool path مثبت في المصدر المفحوص |
| Background `AgentWorker` | نعم | نعم WorkManager | لا ينطبق | جزئي | نعم لمسارات GitHub/Gmail | لا | `AgentWorker` ينفذ فحوصات مباشرة؛ ليس إثبات AgentLoop resume بعد process death |
| Durable task worker | نعم | نعم WorkManager | لا ينطبق | نعم جزئيًا | نعم لمسارات durable | لا | `DurableTaskManager`/worker؛ device restart evidence غير متاح |
| UnifiedCognitiveLoop | نعم | نعم عبر `ServiceLocator` | ليس Chat canonical | غير محسوم لكل caller | نعم لمسارات graph المستقلة | لا | لا يوجد في جرد Chat call site الحالي؛ لا يُوصل بالدردشة دون PR مستقل |
| ProductionAgentOrchestrator | نعم | نعم عبر `ServiceLocator` | voice/scheduled/graph paths | نعم لمساراته | نعم | لا | `VoiceAgentRouter`, `ScheduledAgentWorker`, `ExecutionGraphRuntime` |
| AgentController | نعم legacy | نعم/مستورد في domain pipeline | غير مثبت في Chat | غير موحد | caller موجود خارج Chat | لا | `domain/agent/AgentExecutionPipeline`; لا يُفترض أنه Chat route |
| Firebase Analytics/Crash | نعم | نعم Application init | لا ينطبق | consent gate بحسب الكود | نعم عند opt-in | لا | `TelemetryConsentStore` → `AnalyticsService`/`FirebaseCrashReporter` |
| Cloud sync | نعم | نعم worker/coordinator | لا ينطبق | readiness/auth غير مثبتة | نعم جزئيًا | لا | `CloudSyncWorker`/`CloudSyncCoordinator`; لا claim أن sync مفعّل للمسار العام |

## نتائج البحث الحاسمة

### Chat callers

النقطة العامة هي `ChatScreen`/`AiriApp` → `ChatViewModel.sendMessage` أو `sendMessageWithAttachments`. داخل ViewModel يوجد `sendMessageInternal` ثم اختيار fast response أو `agentLoop.run`. لم يظهر caller من هذا المسار إلى `UnifiedCognitiveLoop`, `ProductionAgentOrchestrator` أو `AgentController` في الجرد الحالي.

### Tool callers

`ChatViewModel` ينشئ `SkillToolBridge`, `ConnectorToolBridge` و`ToolDispatcher`، ثم يجمع `BuiltinTools.ALL` والمهارات والموصلات عبر `RuntimeToolCatalog.assemble`. التنفيذ يمر من `AgentLoop` إلى `ToolDispatcher`، ومنه إلى skill/connector bridge عند تطابق الاسم. توجد فئات legacy مثل `SkillExecutor` و`ai.tools.ToolExecutor`؛ لا تُحذف في PR-0 لأن الجرد وحده لا يثبت غياب reflection أو تكامل خارجي.

### Terminal correction

التقرير التاريخي المصاحب صنّف Terminal من AgentLoop على أنه `DISCONNECTED` لأن `terminal_execute` كان محجوبًا. هذا لم يعد صحيحًا على HEAD الحالي بعد `bf8750e3`: توجد صلاحية `EXECUTE_TERMINAL`، وسياق `task/run/step`، وقرار `ALLOW_TYPED_TERMINAL`، وتمرير principal Agent إلى sandbox. الحالة الصحيحة الآن هي **invoked: ساكنًا مثبت، device-proven: غير مثبت**؛ ولا يعني ذلك أن أوامر Android مثل Torch أصبحت موجودة.

### Device proof

البحث لم يجد `CameraManager.setTorchMode` أو handler لأداة Torch/Flashlight. وجود كلمات `flash/torch/wifi/bluetooth` في detector يثبت routing candidate فقط. لا يوجد في البيئة Android SDK أو emulator/device evidence، لذلك كل claims المتعلقة بتفعيل Accessibility أو postcondition الجهاز تبقى غير مثبتة.

## بوابة PR-0

- [x] جرد HEAD الحالي بدل الاعتماد على الالتزام التاريخي.
- [x] فصل defined/registered/advertised/authorized/invoked/device-proven.
- [x] توثيق callers المعروفة وعدم حذف legacy surface قبل فحص إضافي.
- [x] تصحيح حكم Terminal التاريخي المتعارض مع HEAD.
- [x] إبقاء Flash/Torch وdevice proof كفجوات غير مغلقة.
- [x] إنشاء ADR يثبت حدود المسار قبل PR-1.

## حدود الدليل

هذا تقرير static call-site inventory. لم يُشغّل Gradle لأن Android SDK غير متاح في Sandbox، ولم تُنفذ اختبارات JVM/instrumentation أو Firebase Emulator أو جهاز فعلي في هذه الجولة. لذلك لا يرفع هذا التقرير أي حالة إلى `device-proven` أو `build-proven`.
