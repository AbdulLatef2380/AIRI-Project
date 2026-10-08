# ADR-001: حدود مسار التنفيذ وقدرات AIRI

- **الحالة:** Accepted as baseline for migration
- **التاريخ:** 2026-10-08
- **الالتزام محل القرار:** `bf8750e3` (`main` و`origin/main`)
- **المرجع التاريخي في خطة التدقيق:** `39c9c1b056402be06f6662f66b5da1913645bb7b`
- **النطاق:** Chat, AgentLoop, tools, skills, connectors, permissions, sandbox, terminal, background execution, device proof

## السياق

توجد في المستودع عدة محركات تنفيذ وطبقات تسجيل وأوصاف capability. وجود class أو تسجيل في `ServiceLocator` لا يثبت أن المسار يبدأ من Chat أو يمر بالتفويض أو يعمل على جهاز. لذلك يعتمد هذا القرار على جرد call sites في HEAD الحالي، مع فصل حالات `defined`, `registered`, `advertised`, `authorized`, `invoked` و`device-proven`.

## القرارات

1. **الدردشة النصية تستخدم مسارًا واحدًا.** نقطة الدخول هي `ChatViewModel.sendMessage`/`sendMessageInternal`، والطلب يذهب إلى fast response أو `AgentLoop.run`. لا يُوصل `UnifiedCognitiveLoop` أو `ProductionAgentOrchestrator` أو `AgentController` بالدردشة لمجرد وجوده أو تسجيله.
2. **التصنيف ليس تفويضًا.** `QueryType` يستخدم للتوجيه، بينما `CapabilityIntentDetector`/النية canonical يحددان احتياج capability. لا يكفي تصنيف `ACTION` وحده للسماح بأثر جانبي؛ الصلاحية والسياسة والجاهزية والسياق المملوك مطلوبة.
3. **ToolDispatcher هو منفذ أدوات Chat الحالي.** builtin وskill وconnector actions التي يعلنها AgentLoop تمر عبر `RuntimeToolCatalog` ثم `AgentLoop` ثم `ToolDispatcher`/bridge. المسارات المباشرة خارج ذلك تبقى مسارات مستقلة حتى تثبت بوابة موحدة لها.
4. **الأثر الجانبي fail-closed.** `AgentLoopSideEffectPolicy`, `AgentSandbox`, `ExecutionFirewall` و`PermissionGovernanceLayer` لا تُزال ولا تُستبدل بتأكيد نصي. `terminal_execute` في HEAD الحالي موصول فقط بطلبات `ACTION` وبسياق task/run/step مملوك للجلسة؛ طرفية UI لها principal منفصل.
5. **الإثبات على الجهاز منفصل عن إثبات الكود.** لا تعتبر compilation أو static audit أو وجود Android permission دليلاً على أن Accessibility فعالة أو أن Torch/Wi-Fi/Bluetooth نفذت بنجاح.
6. **المزامنة السحابية لا تُعتبر مفعلة بلا consent/rules/outbox/evidence.** التسجيل أو وجود Firebase class لا يرفع حالة capability إلى connected أو healthy.

## الحالة المعتمدة لكل طبقة

| الطبقة | القرار في HEAD الحالي |
|---|---|
| Chat → AgentLoop | مسار كود مثبت ساكنًا؛ runtime/device proof غير متحقق |
| Intent → tool surface | مسار catalog موجود؛ التصنيف لا يمنح التفويض |
| Builtin/skill/connector dispatch | موحد عبر ToolDispatcher/bridges لمسار Chat؛ بعض المسارات legacy مستقلة |
| Terminal من AgentLoop | موصول في `bf8750e3` عبر سياق durable وصلاحية firewall؛ التنفيذ الفعلي على Android غير متحقق |
| Accessibility | مسار AndroidAgent وAccessibilityExecutionEngine مستقل جزئيًا عن canonical ToolDispatcher |
| Flash/Torch/Wi-Fi/Bluetooth | غير موجودة كأدوات Android مثبتة؛ لا يجوز إعلانها متاحة |
| Background AgentLoop بعد process death | غير مثبت؛ WorkManager paths موجودة لكنها ليست إثبات استمرار عام |
| Build/device | غير متحقق في Sandbox لغياب Android SDK |

## العواقب

- يمنع هذا القرار حذف محركات أو classes قبل جرد reflection والاختبارات والتكاملات.
- كل PR لاحق يجب أن يذكر طبقة الحالة التي رفعها، وألا يرفع `device-proven` إلا بدليل emulator/جهاز فعلي.
- تصحيح التقرير التاريخي الخاص بالطرفية مطلوب؛ لا تُنقل حالة `DISCONNECTED` القديمة إلى HEAD الحالي.
- يبقى Flash/Torch فجوة تنفيذية حقيقية، ولا يُعالج بإضافة patterns فقط.

## بوابة الانتقال

لا يبدأ PR-1 أو PR-2 على أساس ادعاء build أخضر. بوابة PR-0 هي وجود هذا القرار وجرد call sites المرفق، ثم يحق للمرحلة التالية معالجة dependency verification دون تعطيلها.
