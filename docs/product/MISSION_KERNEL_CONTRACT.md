# عقد AIRI Mission Kernel

## الغرض

`MissionKernel` هو حد الملكية لرحلة التنفيذ الدائم في AIRI. تستخدم AIRI `DurableTask` كسجل التنفيذ المستمر، ويمنحها `missionId` هوية aggregate مستقرة تربط الخطة والتشغيل والخطوات والموافقات وسجل الأدلة في نطاق مشروع واحد.

> لا ينشئ هذا العقد جدولاً موازياً أو مهمة تنفيذ ثانية. إنه يطبّع السجل الدائم الحي قبل الحفظ والاستعادة، ويمنع record غير متسق من الدخول إلى runtime.

## الملكية المنفذة

| المورد | الحقول المفروضة | نقطة التطبيق | السلوك عند سجل قديم |
|---|---|---|---|
| Durable task | `missionId`, `projectId`, `ownerId` | `DurableTaskManager.putTask` و`loadFromDisk` | يصبح `missionId = task.id`. |
| Run | `taskId`, `missionId`, `projectId` | `DurableTask.beginRun` ثم `MissionKernel.normalize` | تملأ الحقول الناقصة من المهمة المالكة. |
| Plan step | `runId` بعد بدء التنفيذ | `DurableTask.beginRun` ثم normalisation | تربط بالـrun الحالي فقط عندما يوجد run. |
| Approval | `taskId`, `missionId`, `projectId`, `runId`, `stepId` | `DurableTask.requestApproval` ثم normalisation | تملأ من المهمة والتنفيذ/الخطوة الحاليين. |

## invariants

1. لا يكون `missionId` فارغاً بعد أن تمر المهمة عبر مدير التخزين؛ للسجلات القديمة يساوي `task.id`.
2. يجب أن يطابق كل `TaskRun` مهمة وMission وProject المهمة المالكة.
3. لا يجوز لخطوة معلنة بــ`runId` أن تشير إلى run غير موجود.
4. يجب أن تطابق الموافقة المهمة وMission وProject، وأن تشير فقط إلى run معروف إن عيّنت run.
5. يمنع `MissionKernel.canAccessProject` الوصول إلى أي resource لا يملك `projectId` المطابق للمهمة.
6. يرفض مدير المهام السجل غير الصالح قبل الحفظ، ويستبعد السجل غير الصالح أثناء استعادة JSON.

## الدليل المحلي

| الدليل | ما يثبته |
|---|---|
| `MissionKernelTest` | تطبيع `missionId` وRun/Step/Approval، ورفض run من مشروع آخر، ورفض access لمشروع آخر. |
| `DurableTaskProductKernelTest` | دورة task/run/step/approval الدائمة تبقى قائمة فوق الحقول الموسعة. |
| `tools/verify_core_changes.py` | وجود normalisation والتحقق في حدود الحفظ والاستعادة. |

## حدود الدفعة

Artifact provenance ليس جزءاً من هذا التغيير بعد: `ArtifactManager` ما زال session-owned ولا يملك `taskId/runId/stepId` دائماً. تستكمل دفعة provenance التالية migration Room ومسار إنشاء artifact حي قبل رفع الحالة من `PARTIAL`. واستئناف الخطوة exact-step بعد قرار approval يحتاج continuation token وworker/orchestrator hook؛ القرار والمفاتيح المالكة محفوظة الآن، لكن لا تُدّعى وصلة resume حتى تُنفذ.
