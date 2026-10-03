# دفعة العقود الموحدة لبقية الموصلات

## الحالة الحالية

بعد تحويل Outlook وCalendar إلى Microsoft vertical slice، بقي في الكتالوج **26 تعريفاً `COMING_SOON`**، وليس 28. هذه الدفعة لا ترفعها إلى Connected؛ بل تنشئ لكل تعريف عقد provider واضحاً يمنع التنفيذ الوهمي ويحدد ما يجب أن يطبقه الـAdapter الحقيقي.

## ما يحتويه العقد

لكل موصل متبقٍ يوجد الآن في `ProviderAdapterContract`:

- `catalogId` ثابت.
- اسم المزود.
- Auth mode موحد.
- أقل مجموعة scopes مبدئية.
- read-only health endpoint.
- اسم Adapter canonical.
- رابط توثيق رسمي.
- `readOnlyFirst` كسياسة للـvertical slice.
- `isExecutable = false` حتى يثبت adapter الحقيقي والاختبارات.

العقد وصفي فقط؛ لا ينفذ OAuth أو HTTP أو يسجل credentials، ولذلك لا يتحول إلى Mega Registry.

## تغطية الدفعة

| المجموعة | الموصلات |
|---|---|
| Google Workspace | Docs، Sheets، Contacts، Tasks، Meet |
| Microsoft Graph | OneDrive، Teams، SharePoint، To Do |
| Development | GitLab، Bitbucket، Jira، Linear |
| Communication | Slack، Discord |
| Productivity | Trello، Asana، ClickUp، Monday، Todoist |
| Files | Dropbox، Box |
| Design | Figma، Canva |
| Automation | Airtable |
| Meetings | Zoom |

## سلوك AuthorizationManager

إذا طلب المستخدم موصلاً من هذه الدفعة، يعيد المدير typed failure قبل فتح المتصفح أو استدعاء `ConnectorRegistry.connect()`، ويعرض:

- الدفعة.
- الـAdapter المطلوب.
- Auth mode.
- scopes المخططة.
- health endpoint.

بهذا يعرف المستخدم سبب عدم الجاهزية بدلاً من رسالة عامة أو Connected وهمية.

## الترتيب الحقيقي بعد هذه الدفعة

العقود جاهزة لكل الـ26، لكن التفعيل الحقيقي يجب أن يتم عبر vertical slices صغيرة داخل كل مزود، لا عبر تغيير `isExecutable` جماعياً:

1. Microsoft Graph: OneDrive ثم Teams/SharePoint/To Do بعد نجاح Outlook/Calendar.
2. Google Workspace: Docs/Sheets/Contacts/Tasks، مع مراجعة scope لكل API.
3. Slack/Discord.
4. Dropbox/Box.
5. Trello/Asana/ClickUp/Monday/Todoist.
6. GitLab/Bitbucket/Jira/Linear.
7. Figma/Canva/Airtable/Zoom.

كل Adapter يحتاج OAuth/client configuration، redirect URI، secure token lifecycle، health check read-only، tool exposure، واختبارات cancel/deny/state replay/PKCE/token expiry/network failure/logout/missing scope.

## التحقق

يجب أن ينجح `ProviderAdapterContractTest` في إثبات أن كل `COMING_SOON` الحالي له عقد واحد فقط، وأن كل عقد يملك scopes وhealth endpoint ومرجعاً رسمياً، وأن أياً منها غير قابل للتنفيذ قبل اكتمال الـAdapter.
