# جرد موصلات AIRI وخطة الإغلاق على ثلاث مراحل

**الفرع المرجعي:** `main`
**المراجعة المصدرية:** `d87da8dd`
**الغرض:** إنشاء نقطة بدء قابلة للتدقيق، وتوزيع الإغلاق على ثلاث مراحل دون اعتبار تعريف الكتالوج أو الفحص الساكن دليلاً على جاهزية الإنتاج.

## خلاصة تنفيذية

- يحتوي `OfficialConnectorCatalog` على **35 سطحاً**: **11 `PARTIAL`** و**24 `COMING_SOON`**.
- يثبت `ConnectorBootstrap` تسجيل **15 فئة تنفيذ**. ست فئات منها تخدم أسطحاً في الكتالوج (`GoogleConnector`, `MicrosoftGraphConnector`, `GitHubConnector`, `TelegramConnector`, `NotionMcpConnector`, `ZapierConnector`)؛ وتبقى **9 معرفات runtime خارج الكتالوج** أدناه.
- النطاق المعتمد هو **44 مدخلاً معروفاً في هذا الجرد التشغيلي (35 سطح كتالوج + 9 معرفات runtime خارج الكتالوج)**، دون أسطح إضافية غير معرفة.
- `PARTIAL` أو `LIVE` في rollout تعني وجود مسار مصدر/adapter، ولا تعني اختبار مزود حي أو جاهزية إطلاق. **عدد الموصلات المثبت إغلاقها إنتاجياً من الأدلة المتاحة هنا: صفر.**

## أ. أسطح الكتالوج (35)

| # | Catalog ID | المزود/الفئة | الحالة المصدرية | Runtime / التنفيذ | حدود الدليل الحالي |
|---:|---|---|---|---|---|
| 1 | `google_gmail` | Google | PARTIAL | `google` / `GoogleConnector` | إعداد وتفويض وموافقة واختبار مزود حي مطلوبة |
| 2 | `google_calendar` | Google | PARTIAL | `google` / `GoogleConnector` | إعداد وتفويض وموافقة واختبار مزود حي مطلوبة |
| 3 | `google_drive` | Google | PARTIAL | `google` / `GoogleConnector` | إعداد وتفويض وموافقة واختبار مزود حي مطلوبة |
| 4 | `google_docs` | Google | COMING_SOON | لا يوجد adapter | عقد وصفي فقط؛ scopes/API بحاجة تنفيذ وتحقق |
| 5 | `google_sheets` | Google | COMING_SOON | لا يوجد adapter | عقد وصفي فقط؛ scopes/API بحاجة تنفيذ وتحقق |
| 6 | `google_contacts` | Google | COMING_SOON | لا يوجد adapter | عقد وصفي فقط؛ scopes/API بحاجة تنفيذ وتحقق |
| 7 | `google_tasks` | Google | COMING_SOON | لا يوجد adapter | عقد وصفي فقط؛ scopes/API بحاجة تنفيذ وتحقق |
| 8 | `google_meet` | Google | COMING_SOON | لا يوجد adapter | تعريف النطاق والقدرات غير محسومين |
| 9 | `microsoft_outlook` | Microsoft Graph | PARTIAL | `microsoft_graph` / `MicrosoftGraphConnector` | Entra app وredirect وconsent واختبار حي مطلوبة |
| 10 | `microsoft_calendar` | Microsoft Graph | PARTIAL | `microsoft_graph` / `MicrosoftGraphConnector` | Entra app وredirect وconsent واختبار حي مطلوبة |
| 11 | `microsoft_onedrive` | Microsoft Graph | PARTIAL | `microsoft_graph` / `MicrosoftGraphConnector` | قراءة أولية فقط؛ لا دليل مزود/جهاز حي |
| 12 | `microsoft_teams` | Microsoft Graph | PARTIAL | `microsoft_graph` / `MicrosoftGraphConnector` | بيانات الفرق المنضم إليها فقط؛ لا رسائل أو كتابة |
| 13 | `microsoft_sharepoint` | Microsoft Graph | COMING_SOON | لا يوجد adapter | يحتاج تحديد حدود المواقع وأقل scopes واختباراً |
| 14 | `microsoft_todo` | Microsoft Graph | COMING_SOON | لا يوجد adapter | يحتاج تحديد القراءة/الكتابة والصلاحيات والاختبار |
| 15 | `github` | GitHub | PARTIAL | `github` / `GitHubConnector` | قدرات قراءة وكتابة محدودة؛ لا دليل حساب/device إنتاجي |
| 16 | `gitlab` | GitLab | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 17 | `bitbucket` | Atlassian | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 18 | `jira` | Atlassian | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 19 | `linear` | Linear | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 20 | `slack` | Slack | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 21 | `discord` | Discord | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 22 | `telegram` | Telegram | PARTIAL | `telegram` / `TelegramConnector` | اتصال/أفعال فعلية في المصدر؛ لا دليل حساب حي شامل |
| 23 | `notion` | Notion | PARTIAL | `notion_mcp` / `NotionMcpConnector` | مسار MCP موجود؛ إعداد واعتماد واختبار المزود مطلوبة |
| 24 | `trello` | Atlassian | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 25 | `asana` | Asana | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 26 | `clickup` | ClickUp | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 27 | `monday` | Monday.com | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 28 | `todoist` | Todoist | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 29 | `dropbox` | Dropbox | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 30 | `box` | Box | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 31 | `figma` | Figma | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 32 | `canva` | Canva | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 33 | `airtable` | Airtable | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |
| 34 | `zapier` | Zapier | PARTIAL | `zapier` / `ZapierConnector` | adapter موجود؛ OAuth/provider evidence لازمة |
| 35 | `zoom` | Zoom | COMING_SOON | لا يوجد adapter | عقد وصفي؛ لا تنفيذ أو اختبار مزود |

**ملاحظة scopes:** أُضيفت في المرحلة الثانية متطلبات typed لكل فعل حي، ومصدر قيم موحد لأقل نطاقات OAuth المعتمدة للمسارات المنفذة. يطلب Google وMicrosoft وZapier اتحاد النطاقات التي تخص أفعالاً تسمح بها ملفات الوصول المختارة فقط؛ حفظ callback يربط النطاقات بحالة PKCE أحادية الاستخدام. لا يمثل ذلك إثبات موافقة أو اختباراً حياً لدى المزود. GitHub PAT وNotion وTelegram وREST Hook لا تُسمى OAuth scopes: متطلباتها مميزة حسب نموذج كل مزود، وبعض تفاصيل GitHub تُترك للتحقق من endpoint response.

## ب. معرفات runtime خارج الكتالوج (9)

هذه مسجلة في `ConnectorBootstrap` أو قائمة runtimes في `ConnectorRolloutRegistry`، لكنها ليست 9 تطبيقات خدمة مستقلة مثبتة؛ بعضها قدرات Android محلية وبعضها واجهات تقنية. لا تدخل ضمن catalog readiness ولا ينبغي عرضها على أنها خدمات مزود مغلقة.

| Runtime ID | فئة التنفيذ | نوع/نطاق عام | حالة الجرد |
|---|---|---|---|
| `remote_llm` | `RemoteLlmConnector` | API / موفرو نماذج | runtime مسجل؛ كل provider وإعداداته يحتاجان تحققاً منفصلاً |
| `android_intent` | `AndroidIntentConnector` | Android محلي | runtime مسجل؛ الأفعال والأذونات تتطلب مراجعة حسب القدرة |
| `voice_mtmd` | `VoiceConnector` | صوت محلي | runtime مسجل؛ تعتمد الجاهزية على backend والأذونات والجهاز |
| `clipboard` | `ClipboardConnector` | Android محلي | runtime مسجل؛ يلزم تدقيق حد البيانات والصلاحية |
| `device_apps` | `DeviceAppsConnector` | Android محلي | runtime مسجل؛ يلزم تحقق الجهاز وسياسة البيانات |
| `contacts` | `ContactsConnector` | Android محلي | runtime مسجل؛ إذن وقرار مستخدم واختبار جهاز مطلوب |
| `system_info` | `SystemInfoConnector` | Android / نظام | runtime مسجل؛ حدود البيانات تحتاج اختباراً |
| `ifttt` | `IftttConnector` | أتمتة / webhook | runtime مسجل؛ غير مكافئ لسطح catalog مستقل ولا يعد جاهزاً إنتاجياً |
| `n8n` | `N8nConnector` | أتمتة / webhook | runtime مسجل؛ يحتاج إعداد endpoint/auth واختباراً مستقلاً |

## ج. نقاط لا يمكن حسمها من المستودع وحده

1. ملف الوصول الافتراضي الصريح هو `NOT_CONFIGURED`؛ لا يُمنح حتى `READ` قبل اختيار المستخدم. لا تمنح الشاشة `WRITE` أو `DESTRUCTIVE` أو `ADMIN` ضمنياً.
2. حسابات اختبار المزود، تسجيلات OAuth/redirect URIs/consent، وأجهزة Android الفعلية غير متاحة في هذا الفحص.
3. الإطلاق العام يتطلب أيضاً قرار مالك الإصدار، توقيعاً موثقاً للحزمة المقصودة، مراجعة الخصوصية والمتجر والقانون، واختبارات الجهاز/المزود؛ لا تنتج هذه الأدلة من تعديل كود محلي.

## خطة الإغلاق — ثلاث مراحل وبوابات خروج

### المرحلة 1 — نطاق موثوق وصلاحيات قابلة للإنفاذ

**ما نُفّذ محلياً على `main` حتى الآن:** أُضيفت ملفات `NOT_CONFIGURED` (رفض افتراضي)، `READ_ONLY`, `READ_WRITE`, `FULL_ACCESS`؛ تخزين خاص محلي دائم؛ واجهة اختيار/إلغاء المنحة لكل سطح؛ فصل catalog ID عن runtime ID للأسطح ذات adapter مشترك؛ ربط أفعال Google وMicrosoft وNotion بهويات أسطح مستقلة؛ وفحص المنحة في جسر الأدوات ثم إعادة الفحص داخل `ConnectorRuntimeManager`. أفعال Notion صارت فريدة لكل أداة، والكتابة/التدمير/الإدارة تبقى محجوبة حتى توفر مسار تأكيد typed؛ ملف الوصول وحده لا يعد موافقة.

**ما بقي قبل إغلاق المرحلة:** إكمال mapping وتصنيف typed لكل فعل في جميع أسطح الكتالوج وruntimes، إضافة سجل تدقيق durable لتغييرات المنح والرفض، وتغطية التخزين/الواجهة ومسارات التفويض بالمزيد من الاختبارات. تعذر تشغيل Gradle في البيئة الحالية لغياب Android SDK؛ لذلك لم تُثبت صلاحية build أو اختبارات Kotlin بعد. المرحلة الأولى **قيد التنفيذ وليست مغلقة**.

**بوابة الخروج:** 44 مدخلاً معروفاً موثقاً بلا معرفات مجهولة أو تكرار ملتبس؛ كل action له تصنيف؛ الاختبارات تثبت أن المنحة الأدنى ترفض الأفعال الأعلى وأن غياب المنحة fail-closed؛ لا تتغير حالة `READY` تلقائياً.

### المرحلة 2 — تنفيذ القدرات لكل adapter

**العمل:** إكمال الأسطح الـ24 المصنفة `COMING_SOON` والـ11 `PARTIAL` عند الحاجة، وتسوية تنفيذات runtime التسعة الإضافية على دفعات المزودين. لكل مدخل: وثائق رسمية، config typed، أقل scopes، auth/cancel/deny/state/PKCE/replay، تخزين مشفر وفصل disconnect/revoke، health check، actions typed، error/rate-limit/retry policy، enforcement من Permission Profile، اختبارات unit وintegration، وتوثيق setup. لا يُنفذ write/delete/admin قبل وجود منحة وموافقة مناسبة.

**تقدم هذه الدفعة على `main`:** أُرفقت scopes/permissions بالأفعال المدعومة فعلياً من Google, Microsoft Graph, GitHub, Notion, Telegram, Zapier. Google consent وMicrosoft/Zapier OAuth يطلبون scopes الأفعال المسموح بها، وليس كل scopes adapter الثابتة؛ scopes المطلوبة تحفظ مع state PKCE، ويُرفض callback إذا تغير ملف الوصول أثناء الموافقة. فُصلت قدرات GitHub PAT وNotion sharing وTelegram bot token عن OAuth. `trigger_zap` غير مكشوف للوكيل حتى يتوفر سجل آمن لعنوان REST Hook؛ واختبار `send_webhook` يدوي ومقيد بمضيف Zapier الرسمي. أُزيل ادعاء نجاح pause/resume في Zapier لأن adapter لا ينفذ عمليتيهما. هذا تقدم جزئي فقط، وليس استكمالاً لأسطح catalog أو إثباتاً لمزود حي.

مصادر المزود الرسمية: [Gmail OAuth scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)، [Calendar API authorization](https://developers.google.com/workspace/calendar/api/auth)، [Drive API authorization](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)، [Microsoft Graph messages](https://learn.microsoft.com/en-us/graph/api/user-list-messages?view=graph-rest-1.0)، [events](https://learn.microsoft.com/en-us/graph/api/user-list-events?view=graph-rest-1.0)، [OneDrive children](https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0)، [joined Teams](https://learn.microsoft.com/en-us/graph/api/user-list-joinedteams?view=graph-rest-1.0)، [GitHub fine-grained PAT permissions](https://docs.github.com/en/rest/authentication/permissions-required-for-fine-grained-personal-access-tokens)، [Notion capabilities](https://developers.notion.com/reference/capabilities)، [Telegram Bot API](https://core.telegram.org/bots/api)، [Zapier OAuth scopes](https://docs.zapier.com/powered-by-zapier/api-reference/oauth-scopes).

**المتبقي:** اختبار OAuth/token exchange وdeny/revoke/refresh بأجهزة وحسابات مزود حقيقية، مراجعة الدلالة النهائية للنطاقات مقابل إعدادات التطبيقات المسجلة، وإكمال التغطية لكل runtime وerror handling. المرحلة الثانية **قيد التنفيذ وليست مغلقة**.

**بوابة الخروج:** كل مدخل من الـ44 له حالة تنفيذ صريحة؛ لا يبقى سطح كتالوج معتمد catalog-only؛ كل adapter له coverage لمسارات النجاح والفشل (401/403/429/5xx/timeout/expiry/refresh/disconnect) ولا توجد أدوات وهمية؛ النتائج موثقة دون تسريب أسرار. تظل حالة كل موصل أدنى من `READY` إذا بقيت أدلة المزود/الجهاز غائبة.

### المرحلة 3 — تحقق الإصدار وإغلاق الأدلة

**العمل:** تنفيذ اختبارات المزود بحسابات وتسجيلات فعلية، callback/revoke/consent، اختبارات أجهزة Android ومصفوفة API/ABI، unit/instrumentation وCI debug/release/R8/native، فحص الحزمة والتوقيع والخصوصية والأذونات، استكمال وثائق إعداد كل موصل وسجل الأدلة، ثم تقييم بوابات المتجر والقانون والنشر. تُعاد مراجعة الجرد والصلاحيات مقابل actions المنفذة فعلياً.

**بوابة الخروج:** لا يُعلن `PRODUCTION_VERIFIED` أو `CLOSED` إلا بمرجع evidence لكل معيار من Definition of Done، وتطابق نسخة المصدر مع artifact موثق. قرار النشر يظل لمالك الإصدار وبوابات المتجر/القانون؛ لا يمكن ضمانه مسبقاً أو إنشاؤه من هذا المستودع وحده.

**نتيجة التنفيذ 2026-10-04:** بدأت فحوص المرحلة وأُصلحت ثلاثة عيوب تجميع كشفها آخر CI على `d87da8dd`، ونجحت الحواجز الساكنة؛ لكن لا يوجد بعد build/CI ناجح على نسخة مطابقة للإصلاحات، كما تغيب أدلة الجهاز والمزود والمتجر. لذلك المرحلة الثالثة `NOT_CLOSED / EVIDENCE_BLOCKED`، والعدد النهائي الموثق للإغلاقات ما زال **0/44**. التفصيل في [تقرير تنفيذ المرحلة الثالثة](CONNECTOR_PHASE3_RELEASE_CLOSURE_AR.md).

## معيار الإغلاق لكل surface

- Catalog/runtime IDs canonical وربط واحد مفهوم، أو توثيق adapter المشترك.
- adapter حقيقي مسجل، وليس contract وصفيّاً.
- OAuth/API/MCP صحيح، وإلغاء/رفض/فشل/replay/revoke مغطى.
- تخزين آمن وعدم ظهور الأسرار في log أو prompt أو telemetry.
- health check فعلي، مع حالة اتصال لا تُعلن قبل نجاحه.
- كل action/capability مصنف، والمنحة تُفرض وقت التنفيذ؛ side effects محمية بالموافقة.
- scopes لا تتجاوز المنحة/الحاجة المعلنة، مع بيان حدود provider grant.
- معالجة أخطاء الشبكة والمزود وانتهاء/تجديد الرمز واختبارات regression.
- Android/CI evidence حيث يلزم، وتعليمات إعداد وتشغيل وحدود موثقة.
- عدم ادعاء الجاهزية أو الإغلاق عند غياب أي دليل مطلوب.

## الأوامر المتكررة

```bash
git diff --check
python3 tools/final_runtime_integration_audit.py
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/connector_lifecycle_static_scan.py
```

هذه الفحوص ساكنة/مصدرية. لا تحل محل Gradle أو تشغيل Android أو اختبار provider فعلي.
