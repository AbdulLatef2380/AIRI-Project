# عقد الأتمتة المجدولة

**الحالة:** `IMPLEMENTATION_COMPLETE` لمسار WorkManager المحلي، مدخلات مقيدة، التنفيذ الدائم لوكلاء الخلفية، السجل، الإلغاء، والنتائج. **`RUNTIME_VERIFICATION_PENDING`** لتشغيل طويل الأمد على جهاز Android حقيقي، Doze/OEM restrictions، وإشعارات المستخدم.

## الغرض

يربط هذا العقد `ScheduledJobOrchestrator` و`ScheduledAgentWorker` بالمنسق الإنتاجي الحي. الوظيفة المجدولة ليست نصاً يرسل مباشرة إلى sub-agent؛ كل agent job ينفذ عبر `ProductionAgentOrchestrator.executePlan` حتى ينشأ التسلسل التالي:

> `ScheduledJob → WorkManager run → DurableTask → Run → Step → timeline/approval/diagnostics`

وبذلك يستطيع مركز المهام إظهار replay للعمليات الخلفية بدلاً من outcome منفصل غير قابل للتدقيق.

| المكوّن | المسؤولية المنفذة |
|---|---|
| `ScheduledJobInputPolicy` | يرفض agent/payload/label غير الصالحة أو الكبيرة قبل إدخال WorkManager |
| `ScheduledJobOrchestrator` | يحفظ job وWorkRequest ونتيجة آخر تشغيل و`lastDurableTaskId` في JSON محلي |
| `ScheduledAgentWorker` | يطبق network constraints ويصنع `SubAgentContext` بالنطاق المحفوظ ثم يستدعي المنسق الحي |
| `ProductionAgentOrchestrator` | ينشئ DurableTask وRun وStep، يطبق Agent Team Policy، ويسجل diagnostics/approvals |

## نطاق البيانات والخصوصية

كل job جديد يحمل `projectId` و`ownerId` و`privacyLevel` اختيارياً. تمرر هذه الحقول ضمن input Data إلى WorkManager وتبقى في JSON المحلي، ثم تستخدم في `SubAgentContext` و`OrchestratorPlan`. الوظائف القديمة التي لا تحتويها تقرأ بقيم متوافقة: owner=`scheduled` وprivacy=balanced ومشروع null.

| حقل | الأثر |
|---|---|
| `projectId` | يربط DurableTask المجدول بمساحة المشروع إن كانت معروفة |
| `ownerId` | يثبت الملكية بدلاً من نسبة كل run إلى مستخدم عام |
| `privacyLevel` | يفرض local-only أو balanced أو standard في اختيار الوكيل/backend |
| `lastDurableTaskId` | يشير إلى آخر task أنشأه job لواجهة history/replay |

## التنفيذ والنتائج

لا يمر agent job عبر `SubAgentRegistry.execute` مباشرة. يستدعي العامل خطة من خطوة واحدة بمُعرّف agent المقصود؛ فإن اكتملت يسجل `COMPLETED` مع `planId` بوصفه `lastDurableTaskId`. وإذا أعاد المنسق `PartialFailure`، يحفظ العامل task id نفسه مع `FAILED`، كي يبقى الفشل قابلاً للمراجعة في timeline. أخطاء الشبكة العابرة وحدها تدخل retry المحدود ثلاث مرات؛ أما فشل الخطة أو policy أو agent فيبقى فشلاً نهائياً ولا يعاد تشغيله تلقائياً كأنه transient.

وظائف `system` المعروفة (`sandbox_reaper` و`audit_log_pruner` و`context_cache_pruner`) تبقى maintenance محلية مباشرة. لا تتظاهر بأنها agent work، ولا تنشئ DurableTask لأن لا توجد مهمة مستخدم أو محتوى/موافقة قابلة لإعادة التشغيل. وظيفتها تسجل outcome فقط.

## ضمانات السلامة

يحد WorkManager التنفيذ الدوري إلى 15 دقيقة على الأقل. كل job يستخدم unique work name، والإلغاء يستدعي WorkManager ثم يحذف descriptor المحلي. لا يضيف هذا العقد حدثاً خارجياً أو webhook أو service دائم؛ إنه أتمتة محلية مؤجلة ضمن ضمانات Android فقط. لا يمنح العامل أدوات إضافية: `allowedTools` يبدأ فارغاً، وأي طلب عالي الخطورة يبقى خاضعاً لمركز الموافقات عبر المسار الإنتاجي.

## أدلة التحقق

| الدليل | ما يثبته |
|---|---|
| `ScheduledJobInputPolicyTest` | حدود agent/payload/label قبل enqueue |
| `ScheduledJobDurableLinkTest` | بقاء project/owner/privacy ومرجع DurableTask في نموذج job |
| واجهة `AgentTasksScreen` + الحارس البنيوي | تعرض كل jobs المحفوظة، ومنها `COMPLETED` و`FAILED`، وتدعم refresh صريحاً بلا polling؛ عندما يملك job `lastDurableTaskId` يركز إجراء المستخدم على task المطابق في Execution Center، مع خيار آمن للعودة إلى كل التنفيذات أو توضيح أن evidence لم تعد موجودة. |
| `:app:compileDebugKotlin` | تكامل WorkManager وServiceLocator والمنسق الحي |
| Android CI | سيعاد تشغيله بعد دفع الدفعة؛ اختبار Doze الحقيقي وOEM background policy ليسا جزءاً من هذا الدليل |

## فجوات الإغلاق الصريحة

واجهة المهام تركز الآن على task الدقيق عند توفر `lastDurableTaskId` وتسمح بالعودة إلى كل التنفيذات؛ لا تدّعي navigation عابرة لإعادة إنشاء العملية أو deep-link مستقل خارج الشاشة. لا توجد سياسة جدولة event-triggered أو webhook أو delivery عبر قناة خارجية. لا يدعي هذا العقد التوقيت الدقيق؛ WorkManager قد يؤخر التنفيذ بحسب Doze والقيود الشبكية والنظام. تُعالج event/webhook automation، مصادر البحث، عمليات Git، وقواعد البيانات بعقود منفصلة ضمن Phase 10.
