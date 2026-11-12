# AIRI Documentation Archive

هذا المجلد هو سجل archive للوثائق التاريخية ومصادر المرفقات، وليس تصريحًا بحذف أو نقل الملفات. المسارات الأصلية تبقى مستقرة حتى يوافق مالك المشروع على canonicalization أو النقل.

- [سجل الاحتفاظ والتكرارات للمرحلة 9](PHASE9_RETENTION_REGISTRY.md)
- [Manifest lineage الكامل](../REPOSITORY_DOCUMENT_LINEAGE.md)
- [فهرس التقارير](../REPORTS_INDEX.md)

## قاعدة archive

1. لا تُحذف نسخة تاريخية بسبب تطابق hash وحده.
2. يحدد سجل الاحتفاظ النسخة canonical والنسخ retained/archived وسبب الإبقاء.
3. أي نقل لاحق يتطلب تحديث الروابط وتشغيل حارس المراجع في commit مستقل.
4. ملفات runtime و`SKILL.md` وJAR/ZIP/BIN ليست ضمن archive cleanup التلقائي.
