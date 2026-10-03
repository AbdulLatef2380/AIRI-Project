# Microsoft OneDrive Vertical Slice

## الحالة

تم تفعيل `microsoft_onedrive` كأول Adapter حقيقي من دفعة الموصلات المتبقية. لا يملك OneDrive runtime منفصلاً؛ يستخدم Adapter Microsoft Graph الموجود:

```text
microsoft_onedrive -> microsoft_graph
```

## ما تم تنفيذه

- تحويل Catalog entry من `COMING_SOON` إلى `PARTIAL`.
- إضافة `ConnectorRuntimeDescriptor` canonical.
- إبقاء Teams وSharePoint وTo Do مؤجلة.
- إضافة `Files.Read` إلى Microsoft OAuth consent.
- إعادة استخدام `MicrosoftGraphTokenService` للتخزين المشفر وrefresh rotation.
- إضافة health/authorization عبر Microsoft Graph الحالي.
- إضافة action للوكيل:

```text
connector_microsoft_graph_onedrive_files_read
```

ويقبل parameters اختيارية:

```text
folder_path: مسار مجلد نسبي من جذر OneDrive
top: عدد العناصر من 1 إلى 50
```

الافتراضي يقرأ:

```text
GET /v1.0/me/drive/root/children
```

ومسار مجلد يقرأ:

```text
GET /v1.0/me/drive/root:/<folder_path>:/children
```

ويعيد فقط metadata محدودة:

```text
id, name, size, folder, file, lastModifiedDateTime, webUrl
```

## حدود الأمان

- قراءة فقط؛ لا upload أو delete أو move.
- لا يتم فتح مسار مستقل لـOneDrive؛ يستخدم مدير التفويض العام.
- لا يظهر tool قبل `connected && healthy`.
- `isExecutable` في عقد الدفعة يبقى false لأنه وصف عقدي عام، بينما التنفيذ الفعلي يتم عبر Microsoft adapter مثبت.
- يجب عدم إضافة `Files.ReadWrite` أو `Files.Read.All` لهذا الـslice.
- يجب أن تكون Graph client configuration وredirect URI مضبوطة في build/provider قبل الاختبار الحقيقي.

## التحقق المطلوب قبل إعلان Ready

1. تسجيل Entra public client مع redirect URI الدقيق.
2. consent حقيقي لـ`Files.Read`.
3. Android unit tests.
4. جهاز أو emulator مع callback فعلي.
5. اختبار root listing وfolder listing.
6. اختبار 401/403/429/5xx وانتهاء token.
7. تأكيد عدم ظهور access/refresh token في logs أو prompt.

المراجع الرسمية:

- https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0
- https://learn.microsoft.com/en-us/graph/permissions-reference
- https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow

## الشريحة التالية المنفذة: Microsoft Teams

تم تنفيذ Teams فوق نفس `microsoft_graph` runtime بقراءة الفرق التي انضم إليها المستخدم عبر `GET /v1.0/me/joinedTeams` وscope `Team.ReadBasic.All`. لا تدخل الرسائل أو القنوات أو عمليات الكتابة قبل إكمال مراجعة scopes إضافية واختبارات الموافقة.
