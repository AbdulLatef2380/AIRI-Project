# AIRI Core White-Label Path

## الهدف

يمكن إعادة استخدام محرك AIRI في منتج ذي هوية مختلفة من دون إعادة بناء طبقات الوكيل والذاكرة والنماذج. الهدف الحالي هو **قابلية تهيئة منظمة**، وليس ادعاء وجود لوحة OEM أو محرر branding مكتمل.

## حدود الفصل الحالية

| الطبقة | وضعها الحالي | الإجراء المطلوب عند شريك white-label |
|---|---|---|
| اسم التطبيق وpackage/version | Gradle وAndroid resources/manifest. | إنشاء flavor أو product configuration مملوك للشريك. |
| النصوص والترجمات | `app/src/main/res/values*`. | موردات شريك لكل لغة مع الحفاظ على تكافؤ المفاتيح. |
| الألوان والأشكال والطباعة | `ui/theme/`. | theme overlay أو flavor resources، مع اختبار تباين وRTL/LTR. |
| الأيقونة وsplash/onboarding | موارد Android وواجهات onboarding. | أصول مرخّصة للشريك، لا استبدال منطق AIRI. |
| مزودو النموذج والإعدادات | registry/configuration وتخزين آمن. | تكوين شريك مفصول عن أسرار المصدر ولا يحمل defaults حساسة. |
| الميزات الاختيارية | الصوت، cloud providers، skills/connectors. | policy/feature configuration صريح، لا paywall مزيف. |
| محرك الوكيل والذاكرة | `agent/` و`execution/` و`memory/`. | يبقى نواة مشتركة؛ لا يعدّل لأجل الاسم أو اللون. |

## إجراء إعادة العلامة التجارية المقترح

1. أنشئ product flavor باسم الشريك وحدد `applicationId` واسم العرض ورقم الإصدار.
2. أضف resource overlays للاسم والألوان والأيقونة والترجمات بدلاً من تعديل Core.
3. وفّر إعدادات مزودين وOAuth ومفاتيح signing للشريك عبر CI secrets الخاص به.
4. قرر مع الشريك الميزات المفعلّة: local-only، cloud-optional، voice، skills، connectors.
5. شغّل فحوص الترجمات وmanifest والأمان والإصدار على الـflavor الجديد.
6. راجع policy الخصوصية وإفصاحات المتجر وحقوق الأصول لكل سوق.

## قيود معروفة

- لا يوجد بعد flavor generator أو لوحة إعداد white-label للمستخدم النهائي.
- بعض النصوص والقرارات المنتجية قد تحتاج مراجعة مركزة لتصبح جميعها resource-driven قبل تسليم OEM واسع.
- مزودو الخدمات والحسابات وOAuth وrelease signing لا تنتقل بأمان عبر مجرد fork للمستودع.

## معيار الجاهزية للشريك

لا يوصف brand جديد بأنه **BUILD_VERIFIED** إلا بعد بناء Release/AAB له، ولا **RUNTIME_VERIFIED** إلا بعد instrumentation واختبار جهاز ومزودي خدماته الفعليين.
