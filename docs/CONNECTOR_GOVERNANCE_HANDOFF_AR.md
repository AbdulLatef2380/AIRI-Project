# تسليم عمل حوكمة الموصلات — AIRI Project

**تاريخ التسليم:** 2026-10-05  
**المستودع:** `AbdulLatef2380/AIRI-Project`  
**الفرع المسموح:** `main` فقط  
**حالة التسليم:** نقطة تحقق غير نهائية؛ لا يُعلن إغلاق المرحلة أو جاهزية إنتاجية.
**SHA نقطة التحقق المدفوعة:** `1581b74890d38de2585c3e277d37ee2ae0bce33f`  
**حالة الشجرة عند التسليم:** نظيفة ومتزامنة مع `origin/main`.

## 1. الطلب الأصلي والحدود

المطلوب إغلاق طبقة الموصلات على **44 سطحاً**، بتطبيق حوكمة متعددة اللغات (`ar`, `en`, `es`, `zh`) على الأفعال القابلة للتنفيذ: إذن صريح حسب surface/action، أقل صلاحيات مزود ممكنة، تدقيق، ورفض افتراضي. تعليمات المستخدم الملزمة:

- العمل على `main` فقط.
- النطاق ثابت: `35` سطحاً في `OfficialConnectorCatalog` + `9` معرّفات runtime خارج الكتالوج = **44**؛ لا تعاد إضافة هدف 46.
- المطلوب تدقيق ساكن شامل؛ **نجاح CI وحده لا يثبت الإغلاق**.
- أي action غير معلن أو غير مخول يجب أن يرفضه التنفيذ.
- حذف بيانات الحساب يجب أن يمحو access profiles (GDPR).
- لا يُعلن `0–100%` أو `CLOSED` من دون إثبات التغطية، ولا تُساوى فحوص المصدر بأدلة مزود/جهاز/إصدار.

## 2. مرجع التدقيق الشامل السابق: 253 finding

التقرير الساكن الكامل القائم في المستودع هو [`CONNECTOR_STATIC_AUDIT_AR.md`](CONNECTOR_STATIC_AUDIT_AR.md). يحوي جدولاً منفصلاً لكل واحد من الأسطح الـ44، ومواقع الأدلة والأثر والإصلاح، كما يحصي نتائج التدقيق العابرة للمسارات. التقرير مربوط بمراجعة المصدر على commit `2f6fb6d978bca8f970e2b6d44a299f953b5e18db`؛ **الأرقام التالية هي نتائج تلك المراجعة وليست إعادة تدقيق للشجرة المعدّلة أدناه**.

| النطاق | H | M | L | I | الإجمالي |
|---|---:|---:|---:|---:|---:|
| 44 بنداً | 59 | 96 | 61 | 8 | 224 |
| النتائج العابرة للمسارات | 10 | 15 | 3 | 1 | 29 |
| **المجموع** | **69** | **111** | **64** | **9** | **253** |

لا توجد نتيجة CRITICAL في ذلك العد. من النتائج العابرة عالية الأثر: حواجز الوصول/العقود، تجاوزات دورة حياة وتوجيه، ومشكلات ربط OAuth/المزود. راجع قسم `cross_*` والتفاصيل في التقرير؛ لا تستنتج أن النتائج أغلقت لمجرد وجود تغييرات لاحقة.

الجرد الموثق: **35 تعريف كتالوج**، منها **11 PARTIAL** و**24 COMING_SOON**، إضافةً إلى runtime IDs: `remote_llm`, `android_intent`, `voice_mtmd`, `clipboard`, `device_apps`, `contacts`, `system_info`, `ifttt`, `n8n`. «44» هو نطاق أسطح التدقيق وليس دليلاً على أن كل تكامل جاهز أو أن لكل سطح adapter مزوداً حقيقياً.

## 3. نقطة التوقف الحالية

نقطة البداية للمراجعة كانت `2f6fb6d9`; التغييرات غير النهائية التالية جُمعت في checkpoint `1581b74890d38de2585c3e277d37ee2ae0bce33f` ودُفعت إلى `origin/main`. الشجرة نظيفة عند التسليم، لكن commit checkpoint لا يعني أن المنطق جرى تجميعه أو أن التدقيق اكتمل:

- fail-closed في `ConnectorRuntimeManager`: لا ينفذ action بلا `ConnectorAgentAction` معلن؛ يطابق `authorizationActionId` و`runtimeAction` وprofile، ويتطلب مسار الاستمرار المصرح به للأفعال التي تحتاج موافقة.
- إضافة تحقق runtime للمعاملات: رفض مفاتيح params غير المعلنة، القيم الثابتة المخالفة، القيم المطلوبة الناقصة/الفارغة، الأنواع/الحدود المعلنة، وأي binary ما لم يعلن action سقف bytes صريحاً؛ أضيف كذلك حارس للنص الحر.
- إضافة/توسيع declarations لبعض adapters: Remote LLM، Android Intent، Clipboard، Device Apps، Contacts، Voice، N8n، IFTTT، Google، GitHub، Microsoft Graph، Telegram، System Info وMCP/Notion. هذه التعديلات **لم تخضع بعد لإعادة تدقيق 44 بنداً على كامل الشجرة أو لتجميع Kotlin**.
- مواءمة مفاتيح GitHub/Google legacy tool مع مخططات actions؛ إزالة Zapier webhook sender غير المعلن وواجهة UI التي كانت تستدعيه مباشرة؛ توجيه IFTTT UI عبر runtime.
- صارت معالجة `CancellationException` في VoiceConnector صريحة، وأضيف فحص required/type/bounds لـNotion/MCP.
- بقيت تغييرات العمل السابقة الخاصة بـAgentRouter/ToolRegistry/Approval continuation، ServiceLocator، DataDeletionCoordinator، Connectors UI، Microsoft import، Telegram/Zapier، وغيرها ضمن الشجرة نفسها.

### فحوص أُعيدت في هذه الجلسة

- `git diff --check`: **PASS** وقت الفحص.
- `python3 tools/final_runtime_integration_audit.py`: **PASS، 13/13**.
- `python3 scripts/connector_lifecycle_static_scan.py`: **PASS، 13/13**.
- `bash scripts/verify_local.sh`: **فشل 95/96**. الفشل الوحيد الذي ظهر في المخرجات هو `External automation integration release boundary` في `tools/verify_core_changes.py`؛ يشترط الفحص أن تظل Zapier/IFTTT مقفلتين حتى دليل مستقل للمزود والاعتمادات وcallback/release، ويجب تحديد هل هو كشف regression أم أن checker يحتاج تحديثاً متوافقاً مع التنفيذ الجديد. لا تعدّل الفحص لمجرد جعله أخضر؛ أثبت الحدود المطلوبة أولاً. الفحص المحلي أعلن أيضاً أن Android build غير متاح لغياب SDK (التقرير السابق وثّق `SDK_not_configured`).
- GitHub Actions على commit الأساس `2f6fb6d9`: `AIRI Android CI` فشل (run `37177605684`)، بينما `AIRI Deep Audit` نجح (`37177605673`)، و`AIRI Architecture Audit` نجح (`37177605717`) و`AIRI Oracle` نجح (`37177605701`). هذه التشغيلات تخص SHA الأساس فقط، وليست الشجرة المعدلة.
- لا يوجد في هذه الأدلة اختبار مزود OAuth حي، جهاز Android فعلي، متجر، APK/AAB منشور، أو إثبات إنتاجي. آخر حالة إغلاق موثقة في [`CONNECTOR_PHASE3_RELEASE_CLOSURE_AR.md`](CONNECTOR_PHASE3_RELEASE_CLOSURE_AR.md) هي `NOT_CLOSED / EVIDENCE_BLOCKED`, `0/44` مغلقاً إنتاجياً؛ لا ترفع هذه الحالة حتى جمع دليل جديد.

## 4. ما يجب أن يفعله Manus التالي — بالترتيب

1. **التحقق من استلام checkpoint:** افحص `git status --short --branch`, `git branch --show-current`, `git log -1`, و`git rev-parse HEAD`; يجب أن يكون الفرع `main` فقط. لا تفترض أن sandbox الحالي أو المسارات المحلية مشتركة مع الحساب الآخر. إن كان workspace مختلفاً، استخدم تكامل GitHub المصرح به لاستنساخ `AbdulLatef2380/AIRI-Project` وتحقق من أحدث `main`.
2. **إعادة قراءة المهارات المطلوبة:** اقرأ `/home/ubuntu/skills/workflow-composer/SKILL.md` قبل أي fan-out. هذه مهمة تدقيق واسعة لـ44 سطحاً؛ استخدم `workflow/run` عبر MCP `workflow` إذا كان التقييم لكل سطح عملاً مستقلاً يحتاج reasoning، وإلا نفّذ ماسحاً حتمياً واحداً بمصدر واضح. لا تستخدم وكلاء متعددين لتنفيذ نفس grep/script بشكل مكرر.
3. **اقرأ قبل التعديل:** `docs/CONNECTOR_STATIC_AUDIT_AR.md`, `docs/CONNECTOR_INVENTORY_AND_3_PHASE_PLAN_AR.md`, `docs/CONNECTOR_PHASE3_RELEASE_CLOSURE_AR.md`, ثم `Connector.kt`, `ConnectorAccessProfile.kt`, `ConnectorRuntimeManager.kt`, `ConnectorToolBridge.kt`, `AgentRouter.kt`, `ConnectorDefinition.kt`, `ConnectorRuntimeDescriptor.kt`, `ConnectorBootstrap.kt`, وكل adapter/action declaration المستعمل.
4. **استكمل التدقيق عالي الأثر أولاً:** تتبع كل نداء تنفيذي من `AgentLoop`/`ToolRegistry`/`ToolDispatcher`/`AgentRouter`/الـUI/Skills/task continuations إلى `ConnectorRuntimeManager` أو إثبات أنه إعداد محلي غير تنفيذي. ابحث عن `.execute(ConnectorInput(...))`, نداء connector المباشر، aliases غير المعلنة، أو `input.text`/`params`/`binary` غير المقيدة. افحص تفاعل fixedParams مع MCP وGoogle/GitHub legacy bridge، و`CancellationException`, retry/idempotency، وfalse-success projection.
5. **أثبت تغطية 44 بطريقة قابلة لإعادة التشغيل:** لكل تعريف Catalog سجّل `catalogId -> runtimeId -> executable adapter? -> declared authorization action(s) -> authorizationActionId/permission -> provider grant/scope -> profile enforcement -> direct bypass?`. الأسطح `COMING_SOON` يجب أن تبقى غير قابلة للتنفيذ، مع توثيق عدم وجود adapter بدلاً من اختراع action. كل فعل في adapter executable يجب أن يكون له mapping واحد غير ملتبس، ومخطط typed، وprovider grant minimal. العدد 44 لا يعني 44 OAuth integrations.
6. **راجع التحقق الجديد قبل البناء:** اختبر unknown/missing/extra params، types/bounds، fixed argument forgery، النص الحر، binary absent/oversize/undeclared، أفعال READ مقابل WRITE/DESTRUCTIVE، cancellation وapproval continuation، وGDPR wipe. افحص أن أي side effect لا يصل للمزود قبل profile + confirmation المخصصة، وأن لا يتحول `ApprovalRequired` أو `Streaming` إلى نجاح اصطناعي.
7. **عالج فشل 95/96 بصدق**: افهم أولاً سبب `External automation integration release boundary`. افحص `ReleaseScopePolicy`, IFTTT/Zapier UI/adapter، واختبارها. لا تغيّر checker أو release guard لمجرد النجاح؛ اربط فحصاً ثابتاً يثبت `enabled=false` وحظر credentials/network قبل التصريح بأي action.
8. **تحقق كامل بعد الإصلاح:** `git diff --check`; `python3 tools/final_runtime_integration_audit.py`; `python3 scripts/connector_lifecycle_static_scan.py`; `python3 tools/verify_core_changes.py`; `bash scripts/verify_local.sh`; ثم Android Gradle/unit tests عندما يتاح SDK أو CI على SHA مطابق. افحص source diff، وليس النتيجة العددية وحدها.
9. **حدّث تقرير الإغلاق** فقط بعد تنفيذ كل فحص، مع فصل `STATIC_GOVERNANCE_COVERAGE` عن `PROVIDER/DEVICE/RELEASE_VERIFICATION`. إن بقيت أدلة خارجية غير متاحة فليكن القرار `BLOCKED_EXTERNAL_EVIDENCE` أو ما يعادله، ولا تدّع `CLOSED` أو `PRODUCTION_VERIFIED`.
10. **التزم بفرع main:** لا تنشئ branch جديداً. أجرِ التعديلات المتفق عليها، سجّل checkpoint commit، ادفع إلى `main` ضمن الصلاحية التي منحها المستخدم، وراقب CI الخاص بالـSHA الجديد. سجّل روابط التشغيل وSHA؛ لا تعتبر نجاح CI دليلاً مستقلاً على اكتمال تغطية 44.

## 5. شروط التوقف وإعلان النتيجة

لا تتوقف على نجاح CI فقط. لا تعلن اكتمال المهمة حتى:

- يمكن مطابقة 44/44 من inventory إلى catalog/runtime identity؛ وكل action قابل للتنفيذ مغطى بعقد authorization واحد واضح أو مثبت أنه غير منفذ.
- لا يوجد مسار حي يتجاوز runtime/store/confirmation، ولا alias/parameter/binary/text غير معلن.
- كل provider grant minimal ومحدد النوع؛ لا تعامل PAT/API key/webhook capability كـOAuth scope.
- مسح الحذف يشمل profile store والرموز/المراجع ذات الصلة ضمن نطاق حذف بيانات الحساب.
- فحوص المصدر والاختبارات قابلة لإعادة التشغيل ونتائجها مرتبطة بـSHA محدد.
- أي دليل مزود أو جهاز أو نشر يُذكر فقط إذا نُفذ فعلاً؛ وإلا يبقى عدد الإغلاق الإنتاجي `0/44` حتى تأتي الأدلة.

## 6. رسالة متابعة جاهزة للحساب الآخر

> استأنف إغلاق Connector Layer في المستودع `AbdulLatef2380/AIRI-Project` على `main` فقط، لا تنشئ فرعاً. اقرأ أولاً `docs/CONNECTOR_GOVERNANCE_HANDOFF_AR.md` وكل الملفات المشار إليها فيه، ثم افحص SHA وحالة الشجرة؛ لا تفترض أن sandbox من الجلسة السابقة متاح. الأرقام `253` (69 high) هي نتائج تدقيق ساكن سابق على commit `2f6fb6d9` وليست نتائج إعادة فحص التغييرات الحالية. توجد تغييرات عمل/checkpoint غير نهائية، بينها fail-closed runtime وفحص params/text/binary وتوسيع declarations، ولا يوجد إثبات compile عليها حتى الآن. `verify_local.sh` أعطى 95/96، والفشل هو external automation release boundary؛ افهمه ولا تتجاوزه بتليين الفحص. راجع أولاً تجاوزات المسارات العابرة عالية الأثر، ثم أعد مسحاً قابلاً للتكرار لتغطية كل 44 surface/action/provider grant، وGDPR deletion wipe. لا تعتبر CI إغلاقاً، ولا ترفع `0/44` إلى إغلاق إنتاجي دون أدلة المزود والجهاز والإصدار. اقرأ skill `workflow-composer` قبل fan-out لتدقيق 44 سطحاً؛ استخدم workflow عند وجود 5+ أعمال تقييم مستقلة، وإلا فماسح حتمي واحد. حدّث تقرير الإغلاق فقط بدليل مرتبط بـSHA، وتوقف بحالة صادقة إن كانت الأدلة الخارجية غير متاحة.
