# AIRI Core IP and Dependency Review

تحتوي AIRI على كود تطبيق وموارد خاصة بالمستودع، إضافة إلى تبعيات Android وnative runtimes ونماذج أو أصول صوت قد تكون مستقلة في شروطها عن كود التطبيق.

## أدلة المصدر

| الدليل | الغرض |
|---|---|
| [IP Inventory](IP_INVENTORY.md) | تصنيف مصدر الكود والموارد والنماذج والأسرار والبيانات. |
| [Third-Party Components](THIRD_PARTY_COMPONENTS.md) | فهرس المكونات الخارجية ومسار مراجعتها. |
| [Dependency Inventory](DEPENDENCY_INVENTORY.md) | جرد مباشر مولد من Gradle وكتالوج الإصدارات. |
| [License Matrix](LICENSE_MATRIX.md) | نطاقات المراجعة وقرار الإصدار التجاري. |

## وضع الاستخدام التجاري

**SOURCE_VERIFIED:** يجمع المستودع التبعيات المعلنة، ويمنع الإصدارات الديناميكية في فاحص supply-chain الجديد، ويحدد runtimes أو نماذج تحتاج مراجعة منفصلة.

**EXTERNAL:** نتيجة legal clearance، ownership chain، وحقوق النماذج والأصول والـtrademark. لا يجب أن يدعي أي عرض تجاري أن كل طرف ثالث مملوك لـ AIRI أو أن قابلية إعادة التوزيع مضمونة بلا مراجعة قانونية مؤهلة.
