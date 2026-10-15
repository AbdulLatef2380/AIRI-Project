# تدقيق مسار GitHub في AIRI

## الحكم الحالي

المشكلة ليست أن التوكن لا يملك صلاحية مطلقة بالضرورة. السبب الجذري الأكثر احتمالاً هو أن AIRI كان يثبت صحة الاعتماد عبر `GET /user` ثم يعامل الموصل على أنه جاهز، بينما صلاحية قراءة مستودع خاص وملف داخله تُحسم على مستوى المستودع والـendpoint. لذلك يمكن أن يظهر GitHub متصلاً، ثم يفشل `get_file` عند المستودع الفعلي. هذا السلوك متوافق مع قواعد GitHub الرسمية التي تذكر أن صلاحيات fine-grained PAT مرتبطة بالموارد والـendpoints، وأن `GET /repos/{owner}/{repo}` وقراءة المحتوى لهما متطلبات مستقلة [1].

لا يمكن إعلان أن كل GitHub end-to-end مثبت من دون تنفيذ اختبار حي باستخدام اعتماد المستخدم ومستودع يملكه. ما تم إثباته في الكود هو أن مسار المصادقة، الفحص، التخويل، التنفيذ، وترجمة الفشل أصبح قابلاً للتتبع، مع بقاء اختبار provider حي خارج بيئة البناء الحالية.

## مقارنة التدفق

### OpenHands

OpenHands لا يكتفي بتسجيل دخول GitHub. المستخدم يختار المؤسسات والمستودعات صراحة، ثم يمنح التطبيق صلاحيات مرتبطة بالمستودعات. بعد ذلك يختار المستودع والفرع قبل تشغيل الوكيل [2]. هذه نقطة مهمة مقابل AIRI: صحة الحساب ليست بديلاً عن اختيار repository scope.

### Composio

Composio يربط كل تشغيل بجلسة تحتوي على user ID، ومجموعة الأدوات، والمصادقة، والحساب المتصل، وحالة التنفيذ. الوكيل يكتشف الأداة ثم ينفذها داخل الجلسة نفسها. التوثيق يحذر من تنفيذ أدوات الجلسة عبر مسار مباشر منفصل لأنه يفقد سياق الجلسة والمصادقة [3].

AIRI يملك ما يقابل ذلك جزئياً عبر `ConnectorToolBridge` و`ConnectorRuntimeManager`، لكنه يحتفظ أيضاً بمسار قديم في `ToolRegistry` وبخدمات GitHub قديمة. هذا يرفع احتمال أن يرى المستخدم نتيجة اتصال من مسار، بينما ينفذ AgentLoop عبر مسار آخر.

### LangChain وMCP

نمط MCP القياسي يفصل بوضوح بين `tools/list` و`tools/call`. الأداة المعلنة تحمل input schema، والاستدعاء يعيد نتيجة أو `isError=true`. كما يوصي البروتوكول بعرض الأدوات للمستخدم، وإتاحة رفض الاستدعاء، والتحقق من النتائج قبل تمريرها للنموذج [4].

توثيق LangChain يوضح أن عميل MCP يكتشف الخوادم والأدوات عند بدء الجلسة، ويحافظ على اتصال الخادم، ويدعم OAuth أو headers للمصادقة [5]. هذا يؤكد أن AIRI يجب أن يعيد بناء قائمة الأدوات بعد تغيير الاتصال أو الصلاحيات، لا أن يعتمد على قائمة ثابتة من بداية المحادثة.

### n8n

n8n يفصل credential عن node operation. عقدة GitHub تعرض عمليات مستقلة للملفات والمستودعات وطلبات السحب، بينما وثيقة الاعتماد تشرح أن PAT غير ذي scopes لا يصل إلا للمعلومات العامة وأن عمليات معظم عقد GitHub تحتاج `repo` أو صلاحيات fine-grained مقابلة [6] [7]. هذا أقرب إلى ما يحتاجه AIRI من جعل `get_user` دليلاً وحيداً على الجاهزية.

### GitHub MCP Server

خادم GitHub الرسمي يسمح بتحديد toolsets أو أدوات منفردة، ويدعم وضع read-only. كما أن الأداة `get_file_contents` مستقلة عن أدوات المستودعات والطلبات، وتستخدم معاملات صريحة للمالك والمستودع والمسار [8]. الدليل الرسمي للخادم المستضاف يوضح كذلك أن OAuth، وtoolsets، ووضع القراءة فقط، واختبار الاتصال كلها أجزاء منفصلة [9].

## ما وجدته في AIRI

1. `ConnectorAuthorizationManager.submitCredential()` يتحقق من GitHub عبر `GithubService.validateAndConnect()`. هذا يختبر `/user` فقط، ثم يحفظ الاعتماد في `ConnectorAuthManager` ويستدعي `registry.connect()`.
2. `ConnectorToolBridge` يبني أسماء مثل `connector_github_get_file` ويعيد الاستدعاء إلى `ConnectorRuntimeManager` مع `authorizationActionId`.
3. `ToolDispatcher` يميز مسار الموصل عن مسار المهارة، وهذه نقطة صحيحة.
4. `GithubGuardianSkill` يمر عبر `SkillToolBridge`، ولذلك توجد في المشروع قناتان مشروعيتان: استدعاء connector tool مباشر، أو skill tool يستعمل connector.
5. توجد أدوات GitHub قديمة مثل `github_get_user` و`github_get_repos` و`github_get_file` في `ToolRegistry`. وجودها ليس خطأ وحده، لكنه يخلق ازدواجية إذا لم تُحقن بنفس الكتالوج وبنفس حالة الموصل.
6. `TaskPlanner` كان يعرف `github_get_user` و`github_get_repos` لكنه لا يعرف `github_get_file`. أُضيفت قراءة الملف إلى مخطط المهام مع فحص الجاهزية.
7. `ToolPermissionPolicy` كان يعلن صلاحية GitHub للأداتين القديمتين فقط. أُضيفت `github_get_file` حتى لا يُحجب المسار القديم بعد اكتشافه.
8. `GitHubConnector` كان يتبع رابط pagination القادم من response header دون تقييد host. أُضيف الآن قيد HTTPS و`api.github.com` حتى لا يُرسل Authorization إلى مضيف خارجي إذا وصل header غير موثوق.
9. مدخلات `repo` و`path` غير الصالحة كانت تُحوّل إلى `provider_error` قابل لإعادة المحاولة بسبب `require` ثم catch عام. أُضيف تصنيف `invalid_params` غير قابل لإعادة المحاولة.
10. عمليات الكتابة لا تتجاوز عقد approval الحالي: `GitHubMutationPolicy` و`ApprovalContinuation` يمنعان التنفيذ غير المرتبط بسياق task/project/run/step صالح.

## اختبارات الإثبات المطلوبة

- **اختبار اتصال:** توكن صالح مع `GET /user` يمر، لكن لا يُسجل نجاح repository access.
- **اختبار مستودع خاص:** `connector_github_get_file` على مستودع خاص يملكه المستخدم يعيد المحتوى عند منح Contents read وMetadata read.
- **اختبار fine-grained ناقص:** نفس التوكن مع إزالة repository أو Contents read يعيد `authorization_required` أو `not_found`، ولا يعيد نجاحاً شكلياً.
- **اختبار المسار العربي:** طلب عربي يتضمن رابط GitHub واسم ملف يمر إلى `get_file` أو يطلب repository/path بوضوح بدلاً من استعمال README افتراضي غير معلن.
- **اختبار ازدواجية المسار:** تنفيذ `github_get_file` القديم و`connector_github_get_file` الجديد يجب أن يعيدا نفس نوع الفشل ونفس سياسة الصلاحيات.
- **اختبار disconnect:** بعد فصل GitHub، يفشل كلا المسارين بـ`not_connected` ولا يستطيع أي منهما قراءة credential قديم.
- **اختبار pagination:** رابط `rel="next"` إلى `https://api.github.com` يُقبل، وأي host أو scheme آخر يُرفض ولا يُرسل إليه header Authorization.
- **اختبار write approval:** `create_issue` أو أي mutation دون continuation صالح يبقى `approval_context_required`، وبعد continuation مستهلك لا يعاد تشغيله.

## نتيجة الفحوص المحلية

- `verify_core_changes.py`: **96/96**.
- `security_scan.py`: **PASS**، ولا توجد أسرار مكتشفة.
- `git diff --check`: **PASS**.
- Android CI في آخر متابعة لم يصل إلى نتيجة نهائية ضمن نافذة الانتظار: بقي `main` في `in_progress` و`cp-foundation` في `queued`، وانتهت عملية المتابعة بمهلة. هذا ليس دليلاً على نجاح البناء أو فشله.
- لا يوجد اعتماد GitHub حقيقي متاح في بيئة Sandbox لاختبار مستودع المستخدم ضد API. لذلك لا أدعي إثبات provider حي أو نجاحاً بنسبة 100%.

## References

[1]: https://docs.github.com/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens "GitHub fine-grained PAT permissions"
[2]: https://docs.openhands.dev/openhands/usage/cloud/github-installation "OpenHands GitHub Integration"
[3]: https://docs.composio.dev/docs/how-composio-works "Composio sessions, authentication, and tool execution"
[4]: https://modelcontextprotocol.io/specification/2025-06-18/server/tools "Model Context Protocol tools specification"
[5]: https://docs.langchain.com/oss/deepagents/code/mcp-tools "LangChain MCP tools"
[6]: https://docs.n8n.io/integrations/builtin/credentials/github "n8n GitHub credentials"
[7]: https://docs.n8n.io/integrations/builtin/app-nodes/n8n-nodes-base.github "n8n GitHub node operations"
[8]: https://github.com/github/github-mcp-server "Official GitHub MCP Server"
[9]: https://github.blog/ai-and-ml/generative-ai/a-practical-guide-on-how-to-use-the-github-mcp-server/ "GitHub guide to the GitHub MCP server"
