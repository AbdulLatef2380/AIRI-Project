# بوابة الموصلات الرئيسية السبعة

## النطاق

المقصود بالسبعة الرئيسية هو تعريفات الكتالوج التالية: Gmail وGoogle Calendar وGoogle Drive وGitHub وTelegram وNotion وZapier. أما Microsoft Outlook/Calendar فهو vertical slice إضافي مستقل جرى بناؤه بعد ذلك، ولا يغيّر عدّاد السبعة الأصلي.

## ما تم تثبيته

كل تعريف من السبعة يمر عبر `PrimaryConnectorContracts` ثم `ConnectorRuntimeDescriptor` و`ConnectorAuthStrategy` و`ConnectorAuthorizationManager`. لا يكفي وجود تعريف في الكتالوج؛ يجب أن يكون له runtime adapter مسجل، مسار مصادقة صريح، health check فعلي، وأدوات قابلة للحل عبر `ConnectorToolBridge`.

| سطح الكتالوج | runtime canonical | المصادقة | الفحص الصحي | الأدوات |
|---|---|---|---|---|
| Gmail | `google` | Google Identity ثم Data Authorization | Google API access | Gmail list/read |
| Google Calendar | `google` | نفس دورة Google | Calendar API access | calendar list |
| Google Drive | `google` | نفس دورة Google | Drive API access | Drive search |
| GitHub | `github` | PAT آمن | `/user` | repositories/issues/code/files |
| Telegram | `telegram` | Bot token آمن | `getMe` | updates/chat info |
| Notion | `notion_mcp` | MCP token/configuration | `/v1/users/me` handshake | MCP tools عبر `invoke_tool` |
| Zapier | `zapier` | OAuth2 + PKCE typed gate | `/v1/user` | list zaps/triggers/status |

## إصلاح مهم في هذه المرحلة

كان MCP يملك transport وhandshake حقيقيين، لكنه لم يكن يكشف أدواته إلى الجسر canonical. أضيف الآن `agentActions()` في `McpConnector`. يحتفظ كل binding باسم tool فريد في `ConnectorToolBridge`، بينما يستخدم dispatch الداخلي `invoke_tool` مع اسم MCP في parameters. وبذلك تصل أدوات Notion إلى AgentLoop بعد `connected && healthy` فقط.

كما أضيف كشف أدوات Zapier، وتمت مواءمة action IDs في Zapier وMicrosoft مع أسماء dispatch الداخلية حتى لا يعلن adapter أداة باسم ثم يستقبل اسماً مختلفاً عند التنفيذ.

## بوابة الانتقال

لا تنتقل بقية الموصلات إلى دفعة جماعية قبل تحقق هذه الشروط للسبعة:

1. كل تعريف يطابق runtime canonical واحداً.
2. المصادقة تعيد نتيجة typed ولا تفتح provider browser عند نقص الإعداد.
3. callback state وPKCE لا يقبلان replay أو mismatch.
4. token/credential storage مشفر ولا يدخل logs أو prompt.
5. health check ناجح قبل Connected + Healthy.
6. كل read action يظهر في bridge، وكل write action يبقى خلف permission/approval.
7. فشل الشبكة أو provider أو انتهاء token يعود بكود قابل للتشخيص.
8. Android CI/device evidence يثبت البناء والاختبار الحقيقي.

بعد اكتمال evidence للسبعة، يمكن إنشاء دفعة adapters لبقية الـ28 باستخدام نفس العقد، لكن لا يجوز اعتبارها جاهزة لمجرد إضافتها إلى registry.
