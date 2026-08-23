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

## أول مسار حي: GitHub

عندما يصل `GitHubConnector` إلى `ConnectorInput.execution` يحمل `projectId` غير فارغاً، لا يقرأ connector من `ConnectorAuthManager` ولا يستخدم PAT عالمياً كبديل. يتحقق أولاً من `DurableTaskManager.ownsConnectorExecution` عبر مهمة محفوظة تطابق `taskId` و`missionId` و`projectId` و`runId` و`stepId`. بعد ذلك فقط يصدر capability أحادية الاستخدام للسر المنطقي `GITHUB_PAT` وبالنطاق `projectId + connectorId=github`، ويستهلكها داخل adapter أثناء HTTP request. لا يُحفظ raw PAT أو capability token في continuation أو `ConnectorInput.params` أو activity/timeline.

يبقى `ConnectorAuthManager` لمسارات GitHub غير المرتبطة بمشروع، ومنها اختبار `connect()` والعمليات القديمة التي لا تحمل execution context. هذا ليس fallback لمسار مشروع: كل تنفيذ يحمل project context يفشل مغلقاً عند غياب السر المقيّد أو عدم تطابق ملكية المهمة.

## الدليل

| الدليل | ما يثبته |
|---|---|
| `SecretVaultTest.projectSecretCannotBeUsedAcrossProjectOrConnector` | Project B وconnector مختلف يُرفضان قبل الاستهلاك؛ Project A/connector الصحيح يستهلك مرة واحدة. |
| `SecretVaultTest.revokingProjectSecretInvalidatesOutstandingCapability` | إبطال السر يزيل capability المعلقة. |
| اختبارات broker الأساسية | binding للـagent/operation، الاستخدام الواحد، الإبطال، وعدم إرجاع قيمة raw عبر API. |
| `DurableTaskProductKernelTest.connectorExecutionOwnershipRequiresExactMissionProjectRunAndStep` | لا يصدر مسار adapter مقيّد إلا بعد تطابق mission/project/run/step؛ ترفض الإحداثيات المخالفة. |
| Kotlin compilation | `ServiceLocator` يمرر SecretVault إلى `ConnectorBootstrap` ثم `GitHubConnector`، ويستهلك adapter capability المشروع داخلياً. |

## الحدود المتبقية

GitHub هو adapter الحي الأول فقط. لا تضيف هذه الدفعة شاشة إدارة أسرار مشروع، ولا ترحّل Remote LLM أو Telegram أو Notion أو Google أو الصوت الحي؛ تظل هذه consumers على مساراتها الموروثة إلى أن تمرر project/connector context حقيقياً وتتبنى capability داخل adapter. لا يجوز لأي consumer جديد أن يخمّن `projectId` أو fallback من project scope إلى secret عالمي.
