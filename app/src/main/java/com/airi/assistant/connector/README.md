# طبقة الموصلات

تحتوي هذه الحزمة adapters وإعدادات الخدمات الخارجية. وهي لا تجعل وجود شاشة أو preference أو dependency دليلاً على أن مزوداً حياً صالح للاستخدام. هذا المسار جزء من AIRI Android على `cp-foundation` ضمن **Feature Freeze**.

## العقد الحالي

| الحد | التنفيذ المقصود |
|---|---|
| availability | يجب أن يعرض الموصل حالته الفعلية أو فشله بوضوح؛ لا تتحول تهيئة UI إلى `Healthy` من دون تحقق مناسب. |
| الأسرار | تحفظ في storage مشفر ومربوط، عند وجود سياق مشروع، بـproject/connector capability. لا يظهر secret raw في UI أو log أو artifact أو prompt. |
| الملكية | مسار GitHub المملوك يتحقق من project/task/run/step ثم يستهلك capability محددة الاستعمال؛ لا توجد global fallback عند غياب أو عدم تطابق ملكية المشروع. |
| الأثر الخارجي | الاستدعاءات المتغيرة تحتاج مسار موافقة صريح وtyped continuation حيثما يملكه العقد. لا يمنح سياق chat أو calendar أو confirmation عام تفويضاً آخر. |
| الفشل | أخطاء الشبكة أو المزود تبقى فشلاً واضحاً أو retry محدوداً حسب العقد؛ لا تتحول إلى نجاح شكلي. |

## حدود الإصدار

تنجح CI في compile والبناء والاختبارات الداخلية، لكن هذا لا يثبت credentials أو OAuth redirect أو revoke أو provider API أو سياسة طرف ثالث. Firebase/OAuth/Calendar/GitHub لا تُعد release capabilities حية إلا إذا أعلنها مالك الإصدار وجمع evidence حقيقية لـconsent/cancel/revoke/failure/recovery.

الموصلات أو الأسطح التجارية غير المعلنة لهذا الإصدار لا تُفتح ضمن Feature Freeze. لا تُضاف credentials أو keys إلى Git أو README أو issue أو سجلات CI.

## التحقق

تغطي الاختبارات الحدود المحلية والملكية وحالات الرفض في المسارات ذات العقود. أما build والتحليل static وAndroid instrumentation المتاح فتوثقها CI؛ يبقى real-device وcredentialed provider verification وقرار النشر الخارجي حواجز مستقلة.
