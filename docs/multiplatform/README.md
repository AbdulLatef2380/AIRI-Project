# تخطيط AIRI متعدد المنصات

هذه الشجرة وثائق **استراتيجية post-release** وليست جزءاً من نطاق Android Release Closure الحالي. فرع العمل التنفيذي هو `cp-foundation` وحالته `FEATURE_FREEZE / INTERNAL_CANDIDATE_EVIDENCED / SIGNING_SECRETS_BLOCKED`. لا تنشئ هذه الوثائق وعداً بدعم Desktop أو Web ولا تبرر تعديل أو دمج `architecture-refactor`.

> Android هو المنتج الوحيد قيد الإغلاق. Windows وLinux وWeb وVNC وdesktop runtime وbrowser automation تظل خارج هذا الإصدار حتى تتوفر حزم واختبارات قبول وأدلة تشغيل مستقلة.

## الحالة الواقعية

| المنصة أو الطبقة | الحالة | الدليل المسموح | ما لا يجوز ادعاؤه |
|---|---|---|---|
| Android | مسار build/CI داخلي موثق؛ real-device/store ما زالا خارجيين. | compile/lint/JVM/R8 unsigned/instrumentation API 29/native في CI. | تطبيق موقّع أو منشور أو تحقق شامل على جهاز فعلي. |
| `core-domain` | مشاركة محدودة لسياسات/نماذج نقية مع desktop test. | CI تبني الاختبارات المشتركة. | نواة منتج كاملة أو تطبيق سطح مكتب. |
| Windows وLinux | `PLANNED`. | قرارات ومخاطر ترحيل موثقة فقط. | حزمة أو جلسة agent أو native runtime مدعوم. |
| Web | `PLANNED`. | حدود أمن/تخزين/تشغيل مقترحة فقط. | واجهة ويب مدعومة أو local inference في المتصفح. |

## حدود العمل

`architecture-refactor` مرجع محمي ولا يُعدل أو يُدمج ضمن هذه الدفعة. لا يستخرج هذا البرنامج Room أو JNI أو Compose UI أو أسرار أو runtimes إلى `commonMain` تلقائياً. أي استخراج لاحق يبدأ بعقد خالص قابل للاختبار ويحتاج دليل بناء وتشغيل منفصل.

## وثائق التخطيط

| المستند | الغرض |
|---|---|
| [فحص تبعيات المنصة](PLATFORM_DEPENDENCY_SCAN.md) | جرد مواضع Android/native في المصدر. |
| [رسم التبعيات](PLATFORM_DEPENDENCY_GRAPH.md) | تصور اتجاهات التبعيات ومخاطر النقل. |
| [البنية المستهدفة](CROSS_PLATFORM_ARCHITECTURE.md) | حدود modules وعقود مستقبلية، لا implementation مُعلن. |
| [خطة الترحيل](MIGRATION_PLAN.md) | milestones وبوابات قبول مستقلة. |
| [سجل المخاطر](RISK_REGISTER.md) | المخاطر والمالكية ومعيار الإغلاق. |

## أوامر تحليل مستقبلية

نفّذ التحليل من جذر المستودع النشط، لا من مسار sandbox تاريخي:

```bash
python3 scripts/airi_platform_dependency_scan.py
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_core_health.py
python3 scripts/supply_chain_inventory.py
```

لا يُعاد فتح برنامج المنصات قبل إغلاق signing/device/provider/legal/store gates للإصدار Android أو قرار نطاق مستقل موثق.
