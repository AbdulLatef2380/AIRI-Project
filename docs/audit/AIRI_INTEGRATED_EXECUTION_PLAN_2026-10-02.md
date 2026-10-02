# AIRI Integrated Execution Plan

**Date:** 2026-10-02  
**Branch:** `cp-foundation`  
**Scope:** `main` and `cp-foundation` only; implementation is performed on `cp-foundation`.

## 1. الوضع الحقيقي

التغييرات السابقة أضافت trace وcapability discovery وparser محسنًا، لكنها لم تغلق فجوة التنفيذ الرئيسية:

```text
ChatViewModel → AgentLoop → ToolDispatcher
                                      └─ built-in tools / SkillToolBridge

ConnectorRegistry → ConnectorRuntimeManager
```

المساران لا يلتقيان عند عقدة tool exposure/execution. لذلك ظهور موصل في Connections لا يعني أن AgentLoop يستطيع رؤيته أو استدعاءه.

## 2. القرار التنفيذي

لن نضيف Native Tool Calling الآن، ولن نعيد بناء Connectors أو Skills. سنبني جسرًا عامًا صغيرًا يحقق العقدة الناقصة:

```text
ConnectorRegistry
      ↓ dynamic executable read-only actions
Agent ConnectorToolBridge
      ↓ ToolSchema + execution
AgentLoop → ToolDispatcher → ConnectorRuntimeManager → Connector
```

الجسر لا يعرض catalog entries غير المسجلة، ولا الموصلات غير المتصلة/غير السليمة، ولا capabilities الكتابية. الموصلات المستقبلية تنضم عبر عقد action metadata نفسها دون تعديل AgentLoop.

## 3. خطة التنفيذ

### Workstream A — contract

1. تعريف action metadata عام للموصل (`id`, description, permission, parameters).
2. إبقاء `ToolSchema` هو العقد الوحيد الذي يراه AgentLoop.
3. اشتقاق الأدوات من live registry + connector state، لا من قائمة ثابتة.

### Workstream B — runtime bridge

1. إضافة `ConnectorToolBridge` pure schema projection + execution adapter.
2. جعل `ToolDispatcher` يمرر أسماء connector tools إلى الجسر.
3. تنفيذ connector calls عبر `ConnectorRuntimeManager` فقط.
4. عدم استدعاء `connect()` ضمن exposure؛ health/connection state شرط عرض، وليس side effect.

### Workstream C — vertical slices

نبدأ بالموصلات الموجودة فعليًا ذات read paths:

- GitHub: `list_repos`, `list_issues`, `search_code`, `get_file`, `list_prs`, `status`.
- Telegram: `get_updates`, `get_chat_info`, `status`.
- Google: `gmail_list`, `gmail_read`, `calendar_list`, `drive_search`.

لا يتم عرض `send_message`, `create_issue`, أو أي write action في هذه slice.

### Workstream D — integration proof

اختبارات JVM باستخدام fake connectors تثبت:

- غير المسجل/غير المتصل لا يظهر كأداة.
- read action يظهر كـToolSchema.
- write action لا يظهر.
- execution يصل إلى `ConnectorRuntimeManager` لا إلى HTTP/legacy service.
- فشل connector يرجع إلى AgentLoop كـToolResult.Error.
- مستقبل connector جديد ينضم عبر metadata دون تغيير bridge.

### Workstream E — regression

1. تشغيل اختبارات bridge والموصلات وAgentLoop.
2. تشغيل regression للـtrace/parser/policy.
3. `git diff --check` وworking tree نظيف.
4. commit واحد متكامل على `cp-foundation`، و`main` بلا تعديل.

## 4. معيار النجاح

لا نعتبر العمل مكتملًا حتى يثبت الاختبار هذا المسار:

```text
ToolSchema generated from live connector action
  → AgentLoop selects tool
  → ToolDispatcher delegates connector tool
  → ConnectorToolBridge builds ConnectorInput
  → ConnectorRuntimeManager executes
  → ConnectorOutput mapped to ToolResult
  → trace records canonical connector path
```

## 5. ما لن نفعله الآن

- لا Native Provider Tool Calling.
- لا إرسال Telegram أو إنشاء GitHub issue أو أي write operation.
- لا إضافة قائمة ثابتة لكل الموصلات.
- لا تعديل `main`.
- لا حذف legacy implementations قبل إثبات أن canonical path يغطيها.
