# مراجعة إغلاق سبعة موصلات — 2026-10-06

## النطاق

تمت مراجعة الموصلات التي خرجت من `Coming Soon` في الدفعة السابقة، مع إبقاء النطاق **قراءة فقط**:

- GitLab — Personal Access Token
- Linear — API Key وGraphQL `viewer`
- Slack — OAuth access token
- Discord — Bot token
- Asana — Personal Access Token
- Todoist — API token
- Figma — Personal Access Token

لم تُعدّل موصلات أخرى، ولم تُضف عمليات كتابة أو حذف أو إدارة.

## ما تم إصلاحه في هذه المراجعة

1. أصبح `ConnectorRolloutRegistry` يشير إلى adapter الفعلي `ProviderTokenConnector` بدلاً من أسماء مشتقة غير موجودة مثل `LinearLinearConnector`.
2. أصبحت معاملات الحدّ للقراءة provider-specific:
   - GitLab: `per_page`
   - Slack وDiscord وTodoist: `limit`
   - Asana وFigma: لا يضاف query parameter إلى endpoint الهوية.
3. أضيف اختبار يثبت عدم إلحاق `per_page` عشوائياً بكل endpoint، ويدقق المسارات الحالية.

## مسار المصادقة والتنفيذ الذي تمت مراجعته

1. الكتالوج يعرّف كل سطح كـ`PARTIAL` قابل للتنفيذ وليس `COMING_SOON`.
2. `ConnectorRolloutRegistry` يضع المعرفات السبعة ضمن `LIVE`.
3. `ConnectorBootstrap` يسجل instance مستقلاً لكل credential namespace.
4. `ConnectorAuthorizationManager.begin()` يطلب credential مناسباً للمزود.
5. `submitCredential()` يحفظ الاعتماد عبر `ConnectorAuthManager` ثم ينفذ health-check قبل إعلان `connected/healthy`.
6. الأفعال المعلنة محصورة في `status` و`read`؛ الكتابة والحذف غير موجودين في adapter.
7. `401/403` يعيدان `authorization_required` ويخفضان health، و`429` يعيد `rate_limited` قابلاً لإعادة المحاولة.
8. `disconnect()` يمسح الاعتماد ويفعل حاجز الفصل الصريح.

## نتائج التحقق

- `python3 scripts/connector_lifecycle_static_scan.py`: **13/13 PASS**.
- `git diff --check`: **PASS**.
- فحص اتساق المصدر: **7/7 configs و7/7 live IDs**.
- فحص نقاط API العامة دون أسرار:
  - GitLab/Discord/Asana/Todoist/Figma: `401` متوقع.
  - Linear GraphQL POST: `401` متوقع مع اعتماد غير صالح.
  - Slack: `200` مع `{"ok":false,"error":"not_authed"}`، وهو سلوك تتم معالجته صراحةً في المحول.
- اختبار Gradle لم يبدأ لأن بيئة الـSandbox لا تحتوي Android SDK (`SDK location not found`). لذلك لم يتم ادعاء نجاح build أو unit tests.

## قرار الجاهزية

الموصلات السبعة **مغلقة من حالة Coming Soon وجاهزة للنشر من ناحية الكود والعقد ومسار المصادقة والتنفيذ القراءة فقط**. يظل نجاح الحساب الفعلي مشروطاً بإدخال credential صالح للمزود، ويجب تشغيل `:app:testDebugUnitTest` وAndroid build على CI أو جهاز يحتوي Android SDK قبل إصدار APK نهائي.
