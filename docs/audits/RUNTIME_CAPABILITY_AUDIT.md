# AIRI Runtime Capability Audit

## نطاق الإصلاح

يعالج هذا التغيير فجوة كانت تجعل أوامر الجهاز العربية القصيرة تسقط في مسار المحادثة العادية بدل `AgentLoop`.

- يكتشف `CapabilityIntentDetector` أوامر تشغيل/إيقاف وظائف الجهاز بالعربية والإنجليزية.
- يصنف أوامر مثل `قم بتشغيل فلاش الجهاز` و`شغّل الواي فاي` و`أطفئ البلوتوث` كـ `QueryType.ACTION`.
- لا يمرر `terminal_execute` إلى النموذج إلا في طلبات `ACTION`.
- يربط الطرفية بصلاحية `EXECUTE_TERMINAL` في `ScopedPermissionRegistry` و`ExecutionFirewall`.
- ينشئ `task/run/step` مملوكًا للجلسة قبل تنفيذ الطرفية عبر `AgentLoopTaskRuntime`.
- يمرر التنفيذ عبر `AgentSandbox` و`PermissionGovernanceLayer` و`SandboxExecutor` وallowlist الموجودة.
- تبقى طرفية واجهة المستخدم على principal منفصل (`terminal`) ولا تعيد استخدام سياق AgentLoop.
- يمنع prompt الوكيل الادعاء بعدم توفر قدرة قبل فحص الأداة واستلام خطأ فعلي.

## التحقق

- `python3 tools/verify_core_changes.py`: **96/96 ناجحة**.
- `python3 tools/final_runtime_integration_audit.py`: **PASS**.
- `git diff --check`: **PASS**.
- اختبارات الانحدار المضافة:
  - `CapabilityIntentDetectorTest`
  - `QueryClassifierDeviceActionTest`
  - ربط `terminal_execute` في `ExecutionFirewallTest`

## حدود التنفيذ

هذا الإصلاح يصلح توجيه الطلب إلى مسار الوكيل ويمنع الادعاء الخاطئ بأنه chatbot بلا أدوات. لا يضيف تلقائيًا صلاحيات Android الحساسة، ولا ينشئ تنفيذًا صامتًا للفلاش أو الواي فاي؛ تنفيذ هذه الوظائف يحتاج أداة Android أصلية وصلاحيات/واجهة نظام مناسبة، وقد يبقى محكومًا بحدود `Accessibility` وقيود إصدار Android.

تشغيل Gradle الكامل يبقى معتمدًا على Android SDK المتاح في بيئة CI أو جهاز Android؛ بيئة Sandbox الحالية لا تحتوي على SDK مكتمل.
