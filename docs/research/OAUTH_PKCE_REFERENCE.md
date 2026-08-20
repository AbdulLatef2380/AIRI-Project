# مرجع OAuth وPKCE

- المصدر الرسمي: [Zapier OAuth v2 authentication configuration](https://docs.zapier.com/integrations/build/oauth)
- تاريخ الاطلاع: 13 أغسطس 2026.

يوضح المصدر أن Zapier يوفر دعماً مدمجاً لـ PKCE في تدفق Authorization Code عند تفعيله، وأن قيمة `state` تحمي من CSRF ويجب أن تعاد بلا تغيير ضمن redirect. كما يوضح أن تبادل الرمز يتم عبر طلب Access Token وفق Authorization Code grant.

استخدمت هذه المعلومة لتوجيه الإصلاحات المصدرية التالية في AIRI: إنشاء `code_verifier` عشوائي، اشتقاق `code_challenge` بأسلوب S256، ربط verifier بحالة OAuth قصيرة العمر وحيدة الاستهلاك، وتمرير challenge في رابط التفويض وverifier عند تبادل الرمز. لا يثبت ذلك جاهزية الاتصال التشغيلي: ما زال المشروع يحتاج بيانات عميل Zapier حقيقية واختبار تفويض/redirect/token exchange على جهاز.

## المصدر

1. Zapier Docs, [OAuth v2 authentication configuration](https://docs.zapier.com/integrations/build/oauth).
