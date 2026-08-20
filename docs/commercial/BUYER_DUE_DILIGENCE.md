# AIRI Core Buyer Due Diligence Index

## المجلدات والأدلة

| موضوع الفحص | المصدر داخل المستودع | حالة الدليل |
|---|---|---|
| تعريف المنتج وتموضعه | [Commercial Overview](OVERVIEW.md) و[README](../../README.md) | **SOURCE_VERIFIED** |
| البنية وملكية الحالة | [Architecture Overview](../architecture/OVERVIEW.md) | **SOURCE_VERIFIED** |
| تهديدات وحدود البيانات | [Threat Model](../security/THREAT_MODEL.md)، [Data Flow](../security/DATA_FLOW.md) | **SOURCE_VERIFIED** |
| تبعيات وملكية | [Dependency Inventory](DEPENDENCY_INVENTORY.md)، [License Matrix](LICENSE_MATRIX.md)، [IP Inventory](IP_INVENTORY.md) | **SOURCE_VERIFIED**؛ المراجعة القانونية **EXTERNAL** |
| مسار white-label | [White-label Path](WHITE_LABEL.md) | **SOURCE_VERIFIED** |
| إعادة البناء والإصدار | [Build and Release](../deployment/BUILD_AND_RELEASE.md) وCI | **BUILD_VERIFIED** |
| الاختبارات | `tools/verify_core_changes.py`، `tools/security_scan.py`، `scripts/airi_core_health.py`، CI | **BUILD_VERIFIED** / instrumentation **RUNTIME_VERIFIED** |
| بيانات الإنتاج وحساباته | secrets/CI/Play/Firebase/OAuth | **EXTERNAL** |

## قائمة الاستلام الفني

- [ ] Git history وملكية المساهمات والعقود.
- [ ] قائمة براءات/علامات/أصول مرئية وصوتية إن وجدت.
- [ ] رسم التبعيات المتعدي ومراجعة licenses/CVEs للإصدار المرشح.
- [ ] حسابات Firebase/Google Play/OAuth/Cloud provider مملوكة للجهة الجديدة.
- [ ] release signing وخطة تدوير مفاتيح موثقة وآمنة.
- [ ] نماذج GGUF وأصول Vosk/Porcupine مصدرها وترخيصها واضحان.
- [ ] اختبار جهاز فعلي لمزيج النماذج والصوت والمرفقات الذي سيباع.
- [ ] Data Safety وprivacy policy وخطة retention صالحة للسوق المستهدف.

## عرض توضيحي قصير موصى به

1. افتح محادثة جديدة واختر النموذج المحلي أو السحابي المتاح.
2. أرسل طلباً متعدد الخطوات ثم أظهر حالة التنفيذ الحقيقية والإلغاء.
3. أرفق ملف نصي أو صورة، وأظهر حدود المرفقات والتخزين الخاص.
4. استخدم `/` لاختيار مهارة و`@` لاستدعاء ذاكرة صريحة.
5. فعّل الاستماع المحلي وأظهر النص الجزئي ثم النتيجة النهائية.
6. أظهر حذف جلسة وشرح حدود البيانات، لا لقطات marketing أو progress مزيف.

## أسئلة يجب ألا يجيب عنها المستودع وحده

1. ما سعر الصفقة أو نموذج الترخيص أو الضمانات التجارية؟
2. ما الولاية القضائية وسياسات الخصوصية المتطلبة؟
3. ما الحسابات والمفاتيح والأصول التي يملكها البائع قانونياً؟
4. ما نماذج الذكاء التي سيسمح للشركة بتوزيعها أو استضافتها؟

هذه الأسئلة تحتاج مالكاً تجارياً وقانونياً، وليست فجوات مخفية في مصدر Android.
