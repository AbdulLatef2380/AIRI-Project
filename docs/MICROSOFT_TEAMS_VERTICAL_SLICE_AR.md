# Microsoft Teams Vertical Slice

## الحالة

تم تنفيذ Microsoft Teams كـvertical slice قراءة أولى فوق Adapter Microsoft Graph:

```text
microsoft_teams -> microsoft_graph
```

## التنفيذ

- تحويل Catalog entry من `COMING_SOON` إلى `PARTIAL`.
- إضافة runtime descriptor canonical.
- إعادة استخدام Microsoft OAuth/PKCE وtoken lifecycle.
- إضافة delegated scope:

```text
Team.ReadBasic.All
```

- إضافة action:

```text
teams_list_joined
```

- Graph request:

```text
GET /v1.0/me/joinedTeams?$select=id,displayName,description,visibility,webUrl
```

- النطاق قراءة metadata للفرق التي انضم إليها المستخدم فقط.
- لا توجد عمليات إرسال رسائل أو إنشاء فرق أو إدارة أعضاء في هذه المرحلة.

## لماذا بدأنا بهذا النطاق

يوفر `List joinedTeams` اختباراً حقيقياً لمسار Teams بأقل سطح صلاحيات معقول، من دون إدخال scopes أعلى لقراءة الرسائل أو القنوات أو الكتابة. كما يثبت أن نفس OAuth token يستطيع تمرير أكثر من Microsoft capability عبر Adapter واحد.

## حدود التفعيل

- يحتاج Entra public client وredirect URI مضبوطاً.
- يحتاج consent فعلياً لـ`Team.ReadBasic.All`.
- يحتاج اختبار Android/device callback.
- يجب عدم إضافة `ChannelMessage.Send` أو صلاحيات كتابة قبل مراجعة مستقلة وموافقة صريحة.
- لا يعتبر Teams Ready إنتاجياً قبل اختبار 401/403/429/5xx وانتهاء token وعدم تسريب الأسرار.

المراجع الرسمية:

- https://learn.microsoft.com/en-us/graph/api/user-list-joinedteams?view=graph-rest-1.0
- https://learn.microsoft.com/en-us/graph/permissions-reference
- https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow
