

---

## تحديث لاحق: Microsoft OneDrive Vertical Slice

بعد commit التسليم السابق، استؤنف العمل من نقطة `Microsoft OneDrive` كما أوصت الخطة. تم تنفيذ أول Adapter فعلي من الدفعة المتبقية باستخدام Microsoft Graph المشترك.

### ما تغير

- `microsoft_onedrive` أصبح `PARTIAL` بدلاً من `COMING_SOON`.
- تمت إضافة canonical runtime mapping:

```text
microsoft_onedrive -> microsoft_graph
```

- أضيفت `Files.Read` إلى Microsoft OAuth consent.
- أضيفت أداة قراءة فقط:

```text
onedrive_files_read
```

- تدعم قراءة root أو folder path.
- تم تحديد حد `top` بين 1 و50.
- metadata المعادة محدودة إلى:

```text
id, name, size, folder, file, lastModifiedDateTime, webUrl
```

- Teams وSharePoint وMicrosoft To Do ما زالت مؤجلة.
- تم تحديث اختبارات المصادقة وrollout والعقود.
- تم تحديث بوابة التكامل لتفشل إذا اختفى OneDrive action أو Files.Read أو canonical mapping.

التفاصيل الكاملة:

`docs/MICROSOFT_ONEDRIVE_VERTICAL_SLICE_AR.md`

### الحالة بعد OneDrive

- Microsoft Outlook: PARTIAL، runtime مشترك.
- Microsoft Calendar: PARTIAL، runtime مشترك.
- Microsoft OneDrive: PARTIAL، runtime مشترك، read-only slice مضاف.
- Microsoft Teams: COMING_SOON.
- Microsoft SharePoint: COMING_SOON.
- Microsoft To Do: COMING_SOON.

هذا لا يعني أن OneDrive اجتاز OAuth على جهاز حقيقي؛ ما زال مطلوباً provider configuration وconsent وAndroid/device evidence.
