# AIRI Core Security for Commercial Review

AIRI يبني الأمان حول حدود بيانات وصلاحيات قابلة للفحص: تخزين خاص للمرفقات، منع حفظ URI المصدر، سياق RAG غير موثوق، إلغاء مقيد بجيل التنفيذ، PKCE، مفاتيح مخزنة بأمان، وFileProvider غير مصدّر مع grants محددة.

| المحور | الدليل داخل المستودع | الحالة |
|---|---|---|
| مرفقات وملفات | `AttachmentPolicy`، النسخ الخاص، والتنظيف عند حذف الجلسة. | **BUILD_VERIFIED** |
| أسرار وManifest | `tools/security_scan.py` وCI. | **BUILD_VERIFIED** |
| ذاكرة وRAG | admission وحدود بيانات غير موثوقة. | **SOURCE_VERIFIED** |
| OAuth | PKCE/state registry واختبارات JVM. | **BUILD_VERIFIED** |
| إلغاء الوكيل | `ExecutionGenerationGate` واختبارات JVM. | **BUILD_VERIFIED** |
| موصلات حقيقية وتهديد مزود | يحتاج credentials وإعداداً ومراجعة سيناريوهات تشغيلية. | **EXTERNAL** |

المراجع التفصيلية: [Threat Model](../security/THREAT_MODEL.md)، [Data Flow](../security/DATA_FLOW.md)، و[Security Boundaries](../security/SECURITY_BOUNDARIES.md).

لا تمثل هذه الوثائق اختبار اختراق أو شهادة امتثال. ينبغي إدراج اختبار أمني مستقل، ومراجعة OAuth/deep links، وفحص تبعيات متعدٍ قبل أي نشر تجاري واسع.
