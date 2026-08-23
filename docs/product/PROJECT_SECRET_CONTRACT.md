# عقد AIRI Project-Scoped Secrets

## الغرض

يضيف `SecretVault` نطاق مشروع حقيقياً إلى broker القائم من دون كشف قيمة سرية إلى الوكيل أو prompt أو diagnostics. يبقى secret العالمي متوافقاً مع namespace التخزين السابق؛ secret المشروع يستخدم namespace منفصلاً ولا يمكنه fallback إلى السر العالمي الذي يحمل الاسم نفسه.

## النموذج

| العنصر | الحقول | الحد الأمني |
|---|---|---|
| `ProjectSecret` | `projectId`, `secretId`, `scope`, `connectorId` | يصف ملكية السر؛ لا يحمل القيمة. |
| `SecretCapability` | agent, operation, task, project, connector, TTL, remaining uses | token metadata فقط؛ لا يعيد raw secret. |
| تخزين المشروع | `PROJECT::<projectId>::[CONNECTOR::<connectorId>::]<secretId>` | namespace منفصل داخل SecureStorage/Keystore. |

## قواعد الاستهلاك

1. تنشئ `storeProjectSecret` namespace مشروع مستقل؛ لا تكتب فوق secret عالمي.
2. تنشئ `issueCapability(... projectId, connectorId)` capability لا تصدر إن لم يكن secret مملوكاً لذلك النطاق.
3. تستهلك `useProjectCapability` القدرة فقط عند تطابق agent وoperation وproject وconnector، وبعد فحص الانتهاء والاستخدامات.
4. يستدعي `useCapability` القديم المسار العالمي فقط؛ capability مرتبطة بمشروع تُرفض فيه لأن project context مفقود.
5. تبطل `revokeProjectSecret` كل capabilities المرتبطة بنفس namespace فوراً.
6. لا تسجل القيمة ولا تدخل capability أو رسالة الرفض أي قيمة سرية.

## الدليل

| الدليل | ما يثبته |
|---|---|
| `SecretVaultTest.projectSecretCannotBeUsedAcrossProjectOrConnector` | Project B وconnector مختلف يُرفضان قبل الاستهلاك؛ Project A/connector الصحيح يستهلك مرة واحدة. |
| `SecretVaultTest.revokingProjectSecretInvalidatesOutstandingCapability` | إبطال السر يزيل capability المعلقة. |
| اختبارات broker الأساسية | binding للـagent/operation، الاستخدام الواحد، الإبطال، وعدم إرجاع قيمة raw عبر API. |

## الحدود المتبقية

هذه الدفعة تغلق broker والنطاق الداخلي. لا تضيف شاشة إدارة أسرار مشروع ولا تخمّن `projectId` في provider adapters؛ يجب على كل connector/task runtime أن يمرر project/connector context الحقيقي قبل أن يبدأ باستهلاك project capability. إلى أن يربط adapter هذا السياق، لا يملك مساراً للوصول إلى secret مشروع، وهو fail-closed مقصود.
