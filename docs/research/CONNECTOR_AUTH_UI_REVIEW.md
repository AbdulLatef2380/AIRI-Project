# مراجعة فصل واجهات الموصلات والمصادقة

## نتائج المصادر الرسمية

- Google يطلب اختيار OAuth client المناسب للمنصة، وطلب scopes عند الحاجة، ثم مقارنة scopes الممنوحة مع المطلوبة وتعطيل الميزات التي لا تملك scope صالحاً. توصي الوثائق بالتفويض التدريجي بدلاً من طلب كل الصلاحيات مسبقاً.
  المصدر: https://developers.google.com/identity/protocols/oauth2

- GitHub OAuth هو تدفق redirect خاص بالمستخدم، مع `state` للحماية من CSRF، وPKCE عبر `code_challenge`/`code_verifier`، والتحقق من هوية المستخدم بعد كل تسجيل. نطاقات GitHub ليست تبادلية مع موصلات أخرى.
  المصدر: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps

- Microsoft Entra يفرض redirect URI مسجلاً بدقة، ويطلب scopes محددة، ويوصي بـPKCE. تطبيقات native/mobile العامة لا تستخدم client secret عند استبدال authorization code.
  المصدر: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow

- Notion public connections لها صفحة تفويض واختيار صفحات/قواعد بيانات، redirect URI، code exchange، access/refresh token، وربط token بالموارد التي اختارها المستخدم. إلغاء التفويض أو فشل exchange يجب أن يظهر للمستخدم بوضوح.
  المصدر: https://developers.notion.com/docs/authorization

- RFC 7636 يشرح أن تطبيقات native معرضة لاعتراض authorization code عبر custom URI schemes، وأن PKCE يستخدم verifier عشوائياً لكل طلب ويجب التحقق منه في token endpoint.
  المصدر: https://www.rfc-editor.org/rfc/rfc7636

## استنتاجات هندسية

1. يجوز مشاركة مكوّن بصري منخفض المستوى، لكن لا يجوز أن تكون كل الموصلات في تدفق مصادقة واحد أو أن تعرض نفس الأزرار والافتراضات.
2. يجب أن يكون لكل connector surface تعريفه الخاص للتدفق: OAuth browser، provider token، local Android permission، webhook، MCP endpoint، أو no-auth.
3. Google surfaces تشترك في runtime لكنه لا يعني اشتراكها في scopes أو تجربة تفويض واحدة؛ يجب عرض scope الخاص بالسطح وتحديث حالة ذلك السطح فقط.
4. الموصلات المصنفة PARTIAL أو COMING_SOON يجب ألا تعرض زر Connect يوحي بأنها جاهزة؛ يلزم حالة مخصصة توضّح المتطلب أو عدم الجاهزية.
5. زر refresh/reconnect يجب أن يستدعي authorization manager المناسب للموصل، وليس `registry.connect` العام فقط عندما يتطلب الموصل browser/token/configuration.
6. التصميم المقترح يجب أن يفصل: تعريف كل connector، provider-specific auth executor، ومكوّنات UI قابلة لإعادة الاستخدام فقط للعرض.
