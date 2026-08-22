# AIRI Product Gap Matrix

## الغرض

هذه المصفوفة هي خط الأساس التجاري الجديد لـAIRI بعد مراجعة المرفق ولقطات الشاشة التي تُظهر بنية مساحة عمل Manus: البحث والملفات، الوكيل، المهارات، التخزين، Canvas، Console، Database، Developer، Domains، Git، Growth، Integrations، Invite، Monitoring، Preview، Secrets، Security Center، Shell، User Settings، Users & Auth، وWorkflows.

المصفوفة لا تعتبر وجود واجهة أو صنف أو اختبار وحدة دليلاً على اكتمال القدرة. لا تُغلق القدرة إلا عبر العقد، التكامل الحقيقي، اختبار الفشل، اختبار الواجهة، واختبار الأداء ودليل قابل لإعادة التشغيل.

## تعريف الحالات

| الحالة | المعنى |
|---|---|
| `VERIFIED` | رحلة مستخدم مكتملة بأدلة كود وتكامل وCI أو اختبار واجهة مناسب. |
| `FOUNDATION` | عقد أو runtime موجود، لكن الرحلة أو التكامل أو واجهة المنتج غير مكتملة. |
| `PARTIAL` | جزء من القدرة موجود، مع فجوة تشغيلية أو ملكية بيانات أو صلاحيات. |
| `MISSING` | لا توجد قدرة إنتاجية قابلة للاستخدام. |
| `EXTERNAL` | يتطلب أسراراً أو أجهزة أو خدمة إنتاجية خارجية قبل الإغلاق. |

## Tier 0: رحلة AIRI اليومية

| ID | القدرة | قيمة المستخدم | الحالة الحالية | فجوة الإغلاق | المنصات | معيار القبول |
|---|---|---|---|---|---|---|
| T0-01 | Workspace 2.0 | مشروع واحد يجمع الوكيل والملفات والمهام والمعرفة والأدوات | `PARTIAL` | Product Kernel يربط Workspace/Task/Project Files/Artifacts؛ المتبقي حقن الذاكرة والمعرفة المصرح بهما واختبار الاستعادة على جهاز | Android/Desktop | إنشاء مشروع، فتحه، واستعادة سياقه بعد إعادة التشغيل |
| T0-02 | Project Context | عدم إعادة شرح المشروع في كل محادثة | `PARTIAL` | حقن السياق المصرح به في AgentLoop واختبار عزله عن المشاريع الأخرى | Android/Desktop | الرسالة الجديدة ترى موارد المشروع المصرح بها فقط |
| T0-03 | File Intelligence | تحويل المرفق إلى مورد قابل للبحث والمعرفة | `PARTIAL` | Project File حقيقي: URI→validate→SHA-256→managed storage→preview، وفهرسة نصية محلية صريحة؛ المتبقي semantic index وversion/restore وdocument parsers واختبار Android picker | Android/Desktop | استيراد، بحث exact/semantic، حذف واستعادة |
| T0-04 | Memory Fabric | ذاكرة قابلة للتفسير والحذف وليست مخزناً عشوائياً | `PARTIAL` | Room v8 يطبق provenance/scope/privacy/expiry وMemoryAgent لا يعلن تخزيناً كاذباً؛ المتبقي طبقات Working/Episodic/Semantic الكاملة والتصدير واختبار migration على جهاز | Core/Android/Desktop | عرض المصدر، التصحيح، الحذف، والتصدير |
| T0-05 | Knowledge/RAG | إجابات مرتبطة بالأدلة | `PARTIAL` | فهرسة Project File محلية Lexical مع citations/provenance/scope/privacy داخل RAG؛ المتبقي semantic embeddings، hybrid retrieval/reranking، parsers وواجهة evidence لكل claim | Core/Android/Desktop | كل claim يعرض المصدر والثقة عند توفر المعرفة |
| T0-06 | Execution Center | فهم ما فعله الوكيل ولماذا | `PARTIAL` | Durable Task timeline يسجل run/step/tool/recovery/error مع واجهة replay مختصرة؛ المتبقي event detail/retry وartifact links واختبار process death على جهاز | Android/Desktop | replay قابل للقراءة وإعادة الفتح |
| T0-07 | Approval Center | موافقة واضحة قبل الأثر الجانبي | `PARTIAL` | موافقة Task/Run/Step دائمة مع Allow once/task ورفض/انتهاء واستعادة القرار؛ المتبقي Allow project enforcement وتعديل الأمر وcontinuation لاستئناف الأداة بعد القرار | Android/Desktop | لا ينفذ command أو secret أو connector قبل القرار |
| T0-08 | Real Terminal | تنفيذ مراقب لا زر shell فقط | `PARTIAL` | Android sandbox argv-only مع session/history/output cap/timeout/cancel مرئي وحوكمة؛ المتبقي Desktop PTY وقياس موارد العملية وaudit مُفصل على جهاز | Desktop/Android control | تشغيل، مراقبة، إيقاف، وفشل آمن |
| T0-09 | Browser Agent | تنفيذ مهمة ويب لا web search فقط | `MISSING` | tabs، DOM، upload/download، login approval، replay | Desktop/Cloud | يتوقف عند login أو destructive action وينتظر الموافقة |
| T0-10 | Artifact System | نتيجة قابلة للمعاينة والتنزيل وإعادة التوليد | `PARTIAL` | أنواع artifact، preview، versions، compare، export | Core/Android/Desktop | إنتاج ملف، معاينته، حفظه، وتنزيله |
| T0-11 | Model Router | اختيار نموذج حسب capability/privacy/cost | `PARTIAL` | capability profile، local-only policy، fallback قابل للتفسير | Core/Android/Desktop | routing يرفض نموذجاً غير مناسب ويشرح القرار |
| T0-12 | Automation/Event Engine | تشغيل مهام مفيدة عند حدث حقيقي | `PARTIAL` | trigger/condition/input/approval/retry/result/notification | Android/Desktop/External | run now، pause، history، failure recovery |
| T0-13 | Secret Broker | استخدام credential دون كشف القيمة الخام | `PARTIAL` | Keystore-backed SecretVault يصدر capability مقيدة بالوكيل/العملية/المدة/الاستخدام ويستهلكها داخل provider callback؛ المتبقي audit دائم وrotation وربط كل provider/connector بقبول capability | Android/Desktop | tool receives ephemeral capability, not raw secret |
| T0-14 | AIRI Mesh | اختيار node مناسب لتنفيذ المهمة | `PARTIAL` | device identity، capabilities، presence، trust، routing | Android/Desktop/External | تنفيذ آمن على node مصرح به فقط |
| T0-15 | Continuity | متابعة نفس المهمة من جهاز آخر | `PARTIAL` | مزامنة task state وplan وartifacts وlogs وcurrent step | Android/Desktop/External | فتح المهمة على جهاز ثانٍ واستكمالها دون فقدان الحالة |
| T0-16 | Diagnostics | معرفة سبب الفشل بدون تسريب الأسرار | `PARTIAL` | إكمال trace Task→Run→Step→Tool→Provider→Recovery مع export منقح | Core/Android/Desktop | diagnostic bundle لا يحتوي secrets أو raw prompts الحساسة |

## Tier 1: بيئة التطوير والإنتاج

| ID | القدرة | الحالة الحالية | معيار الإغلاق |
|---|---|---|---|
| T1-01 | Git workspace | `PARTIAL` | clone/branch/diff/commit/pull/push مع موافقة destructive |
| T1-02 | Developer Center | `FOUNDATION` | code/build/test/logs/preview في رحلة مشروع واحدة |
| T1-03 | Database Lab | `MISSING` | query planner وسياسة read/write وaudit وbackup |
| T1-04 | Canvas | `MISSING` | prompt→canvas→user edit→AIRI refinement |
| T1-05 | Research Mode | `PARTIAL` | source graph، cross-check، citations، snapshots، contradiction detection |
| T1-06 | Agent Teams | `MISSING` | roles/models/tools/memory/permissions/budget لكل agent |
| T1-07 | Connector framework | `PARTIAL` | OAuth scope→Secret Broker→Tool Registry→Audit |
| T1-08 | Voice state machine | `PARTIAL` | interruption، barge-in، offline/online، Arabic detection، permissions |
| T1-09 | Vision/OCR/video | `PARTIAL` | image/document/video ingestion مع evidence وlimits |
| T1-10 | Update Center | `MISSING` | signed updates، channels، rollback، migration safety |

## Tier 2: التجاري والفرق

| ID | القدرة | الحالة الحالية | معيار الإغلاق |
|---|---|---|---|
| T2-01 | Package trust / marketplace | `PARTIAL` | manifest، permissions، dependencies، signature، hash، risk |
| T2-02 | Team workspace | `MISSING` | members، roles، projects، policies، audit |
| T2-03 | Sharing/collaboration | `MISSING` | resource-scoped sharing مع revoke |
| T2-04 | Public API/webhooks | `EXTERNAL` | documented auth، rate limits، replay protection، deployment evidence |
| T2-05 | External connectors | `EXTERNAL` | Gmail/Calendar/Drive/GitHub/Slack وغيرها عبر OAuth production |
| T2-06 | Billing/usage | `MISSING` | metering، limits، plans، invoices، privacy controls |
| T2-07 | Admin/analytics | `MISSING` | privacy-preserving metrics وtenant administration |
| T2-08 | Publishing/deployment | `EXTERNAL` | signed artifacts، staged rollout، rollback، store verification |

## رحلة القبول الرئيسية

لا تُعتبر AIRI بيئة عمل مكتملة حتى تمر الرحلة التالية على Android وDesktop حيثما ينطبق:

> ينشئ مستخدم جديد مشروعاً، يضيف ملفاً، يراه في File Intelligence، يوافق على إدخاله إلى Knowledge، يطلب مهمة، يكوّن AIRI خطة، يطلب صلاحية Terminal أو Browser عند الحاجة، ينفذ داخل حدود المشروع، يسجل كل خطوة في Execution Center، يتعافى من فشل قابل للإصلاح، ينتج Artifact، يحفظ evidence وdiagnostics، ثم يفتح المهمة من جهاز آخر ويستكمل من الموضع نفسه.

كل مرحلة من الرحلة تحتاج `PRODUCT_CONTRACT` و`USER_JOURNEY` و`DOMAIN_CONTRACT` و`SECURITY_MODEL` و`REAL_INTEGRATION` و`UNIT_TEST` و`INTEGRATION_TEST` و`UI_TEST` و`FAILURE_TEST` و`PERFORMANCE_TEST` و`CI_ARTIFACT`. آخر أدلة التنفيذ تشمل `24fb3a98` لحدود RAG، و`00d0c7a7` لمركز التنفيذ، و`a1e35c7b` لخريطة صلاحيات AgentLoop، و`492871d8` لحماية Sandbox، و`c569153f` للاسترداد الذاتي داخل الحلقة.

## قرار الأولوية

الأولوية التنفيذية ليست إضافة عشرات الأصناف، بل وصل الموجود فعلياً. يبدأ العمل بربط `WorkspaceRuntime` و`AgentWorkspace` في نموذج Project Context واحد، ثم إغلاق File Intelligence وMemory Governance، وبعدها Execution/Approval/Terminal. Browser وMarketplace وTeam/Billing تبقى مسارات لاحقة أو خارجية حتى تتوفر بيئة تشغيل واعتمادات وأدلة حقيقية.

## الهوية المقترحة

> **AIRI = Personal AI Operating Environment**
>
> ميزته الفارقة ليست عدد التكاملات، بل **Memory Fabric + Local Execution + Trust Fabric + Device Continuity**.

## الأدلة المرئية المستخلصة من الصور

تُظهر الصور المرجعية مساحة عمل ذات بحث موحد للملفات والأدوات، ثم فئات Agent وPublishing وAgent Skills وApp Storage وCanvas وConsole وDatabase وDeveloper وDomains وGit، ثم Growth وIntegrations وInvite وMonitoring وPreview وSecrets وSecurity Center وShell وUser Settings وUsers & Auth وWorkflows. لذلك يجب أن تكون هذه العناصر في AIRI ضمن Information Architecture متدرجة: Chat/Projects/Tasks/Devices/More على الهاتف، وSidebar كاملة على Desktop، لا قائمة مزدحمة داخل Bottom Navigation.

## المراجع

[1]: https://manus.im/blog/manus-my-computer-desktop "Manus — My Computer"
[2]: https://docs.openwebui.com/features/ "Open WebUI Features"
[3]: https://docs.anythingllm.com/ "AnythingLLM Documentation"
