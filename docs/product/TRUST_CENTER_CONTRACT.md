# عقد AIRI Trust Center

## الغرض

يوفر **AIRI Trust Center** سطح قرار حي داخل شاشة المهام والتنفيذ، وليس شاشة إعدادات Android. يقرأ من سجلات الموافقة القائمة، ويعرض لكل طلب نطاق التنفيذ والمخاطرة، ثم يمرر قرار المستخدم إلى `PermissionGovernanceLayer` الذي يحدّث الموافقة الدائمة عند امتلاك الطلب `taskId`.

| المصدر | ما يظهر في Trust Center | القرار الحي | حدّ الصدق |
|---|---|---|---|
| `DurableTask.approvals` ذات الحالة `PENDING` | عنوان المهمة، الإجراء، الوصف، مستوى الخطر، run/step عند توفرهما | `approveAction` أو `denyAction`؛ القرار يكتب في الموافقة الدائمة للمهمة. | لا يعرض تاريخ الموافقات المنتهية أو المكتملة كطلب نشط. |
| `calendar_create` typed approval | بعد نقر Allow، يقرأ dialog منفصل عنوان الحدث ووقته ومدته من proposal خاص محلياً فقط قبل منح القرار. | Allow يؤكد المراجعة ثم يمنح مرة واحدة ويستأنف فقط `CalendarCreateRuntime`؛ Deny/expiry يمسح proposal الخاص. | العنوان والوقت والمدة لا تدخل بطاقة الموافقة أو `TaskApproval` أو timeline أو artifact provenance أو diagnostics. Dismiss لا يمنح الموافقة. |
| `PermissionGovernanceLayer.pendingApprovals` غير الموجودة مسبقاً في المهمة | طلب runtime حي، الإجراء، الوصف، مستوى الخطر، run/step عند توفرهما | نفس حارس الحوكمة؛ لا يفترض أن الطلب يملك مهمة إذا لم يملكها. | لا يصنع موافقات جديدة ولا يستنتج صلاحيات غير مسجلة. |
| `ActiveWorkStopController` | يبقى إجراء الإيقاف في شريط الشاشة. | يوقف فقط الخطط والمهام الدائمة ووظائف المستخدم والطرفية التي تملك API إلغاء. | لا يعلن إيقاف موصل أو Browser action قيد التنفيذ. |

## قرارات المستخدم

| القرار | النطاق | المسار |
|---|---|---|
| Allow once | الإجراء الحالي فقط | `ApprovalGrantScope.ONCE` |
| Allow for task | المهمة المالكة فقط عندما تكون الموافقة مرتبطة بمهمة | `ApprovalGrantScope.TASK` |
| Deny | يرفض الطلب ويكتب نتيجة الرفض في سجل الموافقة عند توفره | `PermissionGovernanceLayer.denyAction` |

> لا يوفر Trust Center حالياً `Allow project` في الواجهة؛ وجود enum لا يثبت إنفاذاً كاملاً على مستوى المشروع. لا تعرض البطاقة العامة أسراراً أو payload خاماً. الاستثناء المرئي المحدود هو مراجعة `calendar_create` الخاصة قبل القرار، وتقرأ فقط من التخزين المحلي الخاص بالمقترح ولا تُحفظ في سجل الحوكمة.

## علاقة Trust بالرحلة الأساسية

```text
DurableTask / Run / Step
        ↓
PermissionGovernanceLayer.requestApproval
        ↓
Trust Center (قرار المستخدم)
        ↓
Durable task approval + typed runtime / audit path
```

لا تزال **Mission aggregate** وFile/Code Editor وBrowser runtime وArtifact-to-task links صالات منفصلة في P0، ولا يعد هذا العقد أنها أغلقت. البند المنفذ هنا هو الوصلة الحية `Approval → Task/Run/Step → user decision`.

## التحقق

| البوابة | الحالة | الحدود |
|---|---|---|
| `:app:compileDebugKotlin` | `LOCAL_VERIFIED` | يبني Compose والحاوية والموارد. |
| `scripts/airi_localization_health.py --strict` | `LOCAL_VERIFIED` عند تشغيل الدفعة | يفحص parity والقيم غير المترجمة المرشحة فقط. |
| `tools/verify_core_changes.py` | `LOCAL_VERIFIED` عند تشغيل الدفعة | ضمان مصدر لعلاقة طلبات الحوكمة/المهمة بالواجهة ومسار التقويم typed. |
| Calendar private-review JVM/build | `BUILD_VERIFIED` / `TESTED` | يثبت المصدر وربط الواجهة، لا يثبت Android Calendar provider أو ظهور dialog على جهاز. |
| TalkBack، التمرير، وقرار موافقة على جهاز | `RUNTIME_VERIFICATION_PENDING` | يتطلب Android فعلياً، ويشمل calendar review والرفض/الانتهاء. |
