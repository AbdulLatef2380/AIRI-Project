# حد الكتابة في GitHub Connector

**الحالة:** `IMPLEMENTATION_COMPLETE` لحظر الكتابة غير المحكومة في `GitHubConnector`. **`RUNTIME_VERIFICATION_PENDING`** لتكامل GitHub حقيقي بعد ربط المسار بموافقة task-owned قابلة للاستئناف. لا يدعي هذا العقد دعم إنشاء issue أو commit أو PR من agent في هذه المرحلة.

## المشكلة المغلقة

كان `GitHubConnector.execute` يقبل action=`create_issue` ويجري HTTP POST مباشرة من envelope عام لا يحمل task/run/step أو approval يمكن التحقق منه. هذا يجعل إنشاء مورد خارجي ممكناً من مسار موصل عام، من دون مركز الموافقات الدائم.

## السلوك الحالي

`GitHubMutationPolicy` يسمح بأفعال GitHub للقراءة مثل `list_repos` و`get_file` و`search_code`. أما `create_issue` فيعيد `ConnectorOutput.Failure(code="approval_required")` **قبل** تحميل credential أو إنشاء اتصال HTTP. الرسالة تشرح أن العملية تتطلب task-owned approval flow.

| نوع العملية | النتيجة |
|---|---|
| قراءة repository/code/issues/PRs | تتابع عبر health-gated `ConnectorRuntimeManager` |
| `create_issue` عبر connector | محجوبة بوضوح حتى يوجد approval/resume contract |
| `GithubService.createCommit/createPullRequest` | غير مكشوفين من UI/ToolRegistry حالياً؛ لا يمثلان مسار agent قابل للاستخدام |

## ما لم ينفذ بعد

لا يحل الحارس محل الموافقة. يلزم قبل إعادة تمكين write أن يحمل `ConnectorInput` أو task context معرّفات task/run/step، وأن يخلق `PermissionGovernanceLayer` موافقة دائمة، ثم يستطيع مسار resume التحقق من approval غير المنتهية واستهلاك نطاق ONCE/TASK/PROJECT وتسجيل timeline قبل POST. لا يجوز تمرير approval id نصي غير متحقق منه أو إعادة الإرسال تلقائياً.

## الأدلة

| الدليل | التغطية |
|---|---|
| `GitHubMutationPolicyTest` | أفعال القراءة مسموحة و`create_issue` يتطلب task approval |
| `:app:compileDebugKotlin` | policy موصولة بالـconnector قبل credential وnetwork write |
