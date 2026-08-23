# عقد AIRI Trust Center

## الغرض

يوفر **AIRI Trust Center** سطح قرار حي داخل شاشة المهام والتنفيذ، وليس شاشة إعدادات Android. يقرأ من سجلات الموافقة القائمة، ويعرض لكل طلب نطاق التنفيذ والمخاطرة، ثم يمرر قرار المستخدم إلى `PermissionGovernanceLayer` الذي يحدّث الموافقة الدائمة عند امتلاك الطلب `taskId`.

| المصدر | ما يظهر في Trust Center | القرار الحي | حدّ الصدق |
|---|---|---|---|
| `DurableTask.approvals` ذات الحالة `PENDING` | عنوان المهمة، الإجراء، الوصف، مستوى الخطر، run/step عند توفرهما | `approveAction` أو `denyAction`؛ القرار يكتب في الموافقة الدائمة للمهمة. | لا يعرض تاريخ الموافقات المنتهية أو المكتملة كطلب نشط. |
| `PermissionGovernanceLayer.pendingApprovals` غير الموجودة مسبقاً في المهمة | طلب runtime حي، الإجراء، الوصف، مستوى الخطر، run/step عند توفرهما | نفس حارس الحوكمة؛ لا يفترض أن الطلب يملك مهمة إذا لم يملكها. | لا يصنع موافقات جديدة ولا يستنتج صلاحيات غير مسجلة. |
| `ActiveWorkStopController` | يبقى إجراء الإيقاف في شريط الشاشة. | يوقف فقط الخطط والمهام الدائمة ووظائف المستخدم والطرفية التي تملك API إلغاء. | لا يعلن إيقاف موصل أو Browser action قيد التنفيذ. |

## قرارات المستخدم

| القرار | النطاق | المسار |
|---|---|---|
| Allow once | الإجراء الحالي فقط | `ApprovalGrantScope.ONCE` |
| Allow for task | المهمة المالكة فقط عندما تكون الموافقة مرتبطة بمهمة | `ApprovalGrantScope.TASK` |
| Deny | يرفض الطلب ويكتب نتيجة الرفض في سجل الموافقة عند توفره | `PermissionGovernanceLayer.denyAction` |

> لا يوفر Trust Center حالياً `Allow project` في الواجهة؛ وجود enum لا يثبت إنفاذاً كاملاً على مستوى المشروع. لا تعرض الواجهة أسراراً أو payload خاماً، وتكتفي بوصف الحوكمة المسجل أصلاً.

## علاقة Trust بالرحلة الأساسية

```text
DurableTask / Run / Step
        ↓
PermissionGovernanceLayer.requestApproval
        ↓
Trust Center (قرار المستخدم)
        ↓
Durable task approval + AgentActivity / audit path
```

لا تزال **Mission aggregate** وFile/Code Editor وBrowser runtime وArtifact-to-task links صالات منفصلة في P0، ولا يعد هذا العقد أنها أغلقت. البند المنفذ هنا هو الوصلة الحية `Approval → Task/Run/Step → user decision`.

## التحقق

| البوابة | الحالة | الحدود |
|---|---|---|
| `:app:compileDebugKotlin` | `LOCAL_VERIFIED` | يبني Compose والحاوية والموارد. |
| `scripts/airi_localization_health.py --strict` | `LOCAL_VERIFIED` عند تشغيل الدفعة | يفحص parity والقيم غير المترجمة المرشحة فقط. |
| `tools/verify_core_changes.py` | `LOCAL_VERIFIED` عند تشغيل الدفعة | ضمان مصدر لعلاقة طلبات الحوكمة/المهمة بالواجهة. |
| TalkBack، التمرير، وقرار موافقة على جهاز | `RUNTIME_VERIFICATION_PENDING` | يتطلب Android فعلياً. |
