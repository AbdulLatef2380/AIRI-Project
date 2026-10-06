# Microsoft SharePoint Read-Only Vertical Slice

**Catalog ID:** `microsoft_sharepoint`  
**Runtime:** `microsoft_graph`  
**Scope:** قراءة فقط، دون إنشاء أو تعديل أو حذف

## ما تم تنفيذه

- تحويل الكتالوج من `COMING_SOON` إلى `PARTIAL` بحدود معلنة.
- ربط السطح صراحةً بالـcanonical runtime `microsoft_graph`.
- إضافة scope `Sites.Read.All` إلى مسار Microsoft OAuth المسموح.
- إضافة أفعال agent محددة بالسطح:
  - `sharepoint_site_read`: قراءة بيانات موقع SharePoint الجذر.
  - `sharepoint_files_read`: قراءة قائمة الملفات من موقع يحدده المستخدم عبر `site_id`.
- تقييد عدد النتائج إلى `1..50` في قراءة الملفات.
- منع cross-surface access عبر `surfaceId = microsoft_sharepoint`.
- إبقاء العمليات خلف access profile وhealth check وMicrosoft secure token lifecycle.
- إبقاء `microsoft_sharepoint` ضمن `LIVE` في rollout registry بعد توفر adapter فعلي.

## المسارات

```text
GET /v1.0/sites/root?$select=id,displayName,webUrl,siteCollection
GET /v1.0/sites/{site_id}/drive/root/children?$top={1..50}&$select=id,name,size,folder,file,lastModifiedDateTime,webUrl
```

## الحماية

- لا توجد أفعال `POST`, `PUT`, `PATCH`, أو `DELETE` في هذا vertical slice.
- `Sites.Read.All` صلاحية قراءة واسعة على مستوى المؤسسة؛ يجب أن تعرضها واجهة permission preview بوضوح قبل الموافقة.
- `site_id` إلزامي لقراءة الملفات، و`top` bounded.
- أخطاء `401`, `403`, `429`, `5xx` تمر عبر تصنيف Microsoft Graph الموجود في runtime.
- انتهاء أو غياب token يمنع التنفيذ ويعيد نتيجة typed بدلاً من إعلان نجاح.

## بوابة التحقق

- catalog/runtime mapping: موجود.
- OAuth scope admission: موجود.
- agent actions وsurface grants: موجودة.
- access-profile enforcement: مربوط بالـsurface.
- registry readiness: `LIVE`.
- unit/static tests: مضافة لعقد SharePoint وauth strategy وruntime mapping وعزل الأدوات.
- Android Gradle build: يجب تشغيله على CI أو جهاز يحتوي Android SDK؛ بيئة Sandbox الحالية لا تحتوي SDK.

## الحدود المقصودة

هذا ليس دعماً كاملاً لكل SharePoint:

- لا يوجد بحث شامل في كل المواقع.
- لا توجد قراءة محتوى الملفات الثنائية أو تنزيلها في هذه الدفعة.
- لا توجد كتابة أو مشاركة أو إدارة أذونات.
- لا يتم توسيع scope إلى `Sites.ReadWrite.All`.
