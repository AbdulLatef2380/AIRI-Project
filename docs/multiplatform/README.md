# برنامج تحويل AIRI إلى منصة متعددة المنصات

## الهدف وحدود العمل

يحوّل هذا البرنامج AIRI من منتج Android أولاً إلى **منصة وكيل ذكاء اصطناعي ذات نواة مشتركة** تدعم Android اليوم، ثم سطح المكتب على Windows وLinux، ثم Web حين تتوفر أدلة بناء وتشغيل حقيقية. لا يعني وجود هدف Gradle أو واجهة فارغة أن المنصة مدعومة؛ الدعم لا يُعلن إلا مع أدلة قابلة للتكرار.

> الخط المرجعي `architecture-refactor` محمي ومخصص لاستقرار Android. لا تُنفذ عليه تجارب متعددة المنصات ولا يُدمج إليه أي عمل من هذا البرنامج قبل اجتياز بوابة ترقية مستقلة.

| عنصر التحكم | الحالة الفعلية | الدليل |
| --- | --- | --- |
| خط Android المرجعي | `architecture-refactor` عند `1027dee2` | فرع بعيد محمي، لا توجد عليه تعديلات من البرنامج. |
| نقطة الاستعادة | `checkpoint/cp-main-preflight` عند `c81ecd6b` | فرع منشور قبل أي عمل متعدد المنصات. |
| فرع العمل | `cp-foundation` | مشتق من `main` ثم يدمج الخط المرجعي في اتجاه واحد فقط لحفظ إصلاحات Android. |
| اتجاه الدمج المسموح حالياً | `architecture-refactor` → `cp-foundation` | لا يوجد دمج عكسي أو تعديل للخط المحمي. |
| النواة متعددة المنصات | `BUILDS` لنطاق `core-domain` المحدود | سياسة قبول الذاكرة تبني من `commonMain` لاختبار JVM Desktop وAndroid؛ لا يوجد بعد منتج Desktop/Web أو نواة كاملة. |

## حالة المنصات

تستخدم AIRI الكلمات التالية حصراً: `PLANNED` و`ARCHITECTED` و`IMPLEMENTED` و`BUILDS` و`RUNTIME_VERIFIED` و`EXTERNAL_VERIFICATION_REQUIRED` و`BLOCKED`. تعني `RUNTIME_VERIFIED` اختبار السلوك في بيئة منصة حقيقية أو محاكي موثق؛ أما اكتمال الترجمة وحده فحالته `BUILDS`.

| منصة | حالة المنتج | ما هو مثبت الآن | ما لا يجوز ادعاؤه الآن |
| --- | --- | --- | --- |
| Android | `RUNTIME_VERIFIED` لبوابة الاختبارات الأساسية | مسار CI وinstrumentation مستقلان في الخط المرجعي. | لا يعد هذا دليلاً منفصلاً على تشغيل كل مزود أو ميزة على جهاز مادي. |
| Windows | `PLANNED` | قرار البدء بسطح المكتب موثق. | لا توجد حزمة أو جلسة دردشة أو runtime متحقق منها. |
| Linux | `PLANNED` | قرار البدء بسطح المكتب موثق. | لا توجد حزمة أو جلسة دردشة أو runtime متحقق منها. |
| Web | `PLANNED` | حدود الأمن والتخزين والتشغيل المحلي موثقة. | لا توجد واجهة ويب مدعومة أو استدلال محلي في المتصفح. |

## قرار التقنية

تم اعتماد **Kotlin Multiplatform (KMP)** للنواة المشتركة، مع إبقاء تبني Compose Multiplatform تدريجياً ومنفصلاً عن أول استخراج. هذا يتفق مع نموذج KMP الذي يمنع APIs الخاصة بالمنصة من `commonMain` ويوفر source sets خاصة بالمنصات عند الحاجة [1]. كما أن KMP وCompose Multiplatform يعلنان Android وDesktop (JVM) مستقرين، بينما Compose Web/Wasm في Beta [2]. لذلك يكون Desktop أول هدف خارجي، وتبقى Web هدفاً مستقلاً بعد استقرار طبقات النواة والأمن.

| قرار | السبب | النتيجة العملية |
| --- | --- | --- |
| استخراج تدريجي لا إعادة كتابة | Android يعمل ويحتوي على منطق وكيل وذاكرة وأمان متراكم. | يبدأ النقل بالسياسات والنماذج الخالصة مع اختبارات، لا بالواجهات أو Room أو JNI. |
| KMP للنواة فقط في البداية | يحقق مشاركة منطق الأعمال دون فرض UI موحد. | أول milestone: وحدة مستقلة تبني بلا Android. |
| Desktop قبل Web | استقرار Android/Desktop أعلى، وبيئة الملفات وnative أقرب إلى runtime المحلي. [2] | Windows/Linux لا يعلنان مدعومين إلا بعد بناء وتشغيل اختبارات القبول. |
| Repository contracts قبل قرار تخزين موحد | Room الحالي مرتبط بحالة Android، وWeb بيئة أمن وتخزين مختلفة. | تبقى Room adapter على Android؛ لا تُنقل قاعدة البيانات كما هي إلى `commonMain`. |
| ModelRuntime كحد صريح | JNI/NDK الخاصان بـ Android غير قابلين للنقل المباشر. | تبقى مكتبة Android native في Android، وتُبنى runtimeات مستقلة لسطح المكتب وWeb عند إمكانها. |

## مخرجات التحليل

| المستند | الغرض |
| --- | --- |
| [فحص تبعيات المنصة](PLATFORM_DEPENDENCY_SCAN.md) | نتائج قابلة للتكرار لكل ملفات Kotlin وnative. |
| [رسم تبعيات المنصة](PLATFORM_DEPENDENCY_GRAPH.md) | يبيّن مواضع تسرب Android عبر طبقات المنتج. |
| [البنية المستهدفة](CROSS_PLATFORM_ARCHITECTURE.md) | حدود الوحدات، العقود، واتجاه التبعيات. |
| [خطة الترحيل](MIGRATION_PLAN.md) | milestones وبوابات القبول وعدم الانحدار. |
| [مصفوفة المنصات](PLATFORM_MATRIX.md) | حالة كل قدرة ودليلها المطلوب. |
| [استراتيجية runtime](RUNTIME_STRATEGY.md) | فصل llama.cpp وcloud runtimes حسب المنصة. |
| [استراتيجية التخزين](STORAGE_STRATEGY.md) | فصل الـrepositories عن Room وتحديد قرار النقل. |
| [استراتيجية المصادقة](AUTH_STRATEGY.md) | عقد OAuth/PKCE مشترك وتفاصيل callback حسب المنصة. |
| [نموذج الأمن](SECURITY_MODEL.md) | حدود الثقة وتحديداً Web والـsecrets. |
| [سجل المخاطر](RISK_REGISTER.md) | المخاطر الحقيقية، المالك، ومعيار الإغلاق. |

## أوامر التحقق

```bash
cd /home/ubuntu/AIRI-Project-git
python3 scripts/airi_platform_dependency_scan.py
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_core_health.py
python3 scripts/supply_chain_inventory.py
```

لا يُنشأ extraction جديد قبل اكتمال بوابة التحليل. وكل milestone لاحق يجب أن يسجل الأوامر الفعلية ونتائجها في المستندات المرتبطة به.

## المراجع

[1]: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html "The basics of Kotlin Multiplatform project structure"
[2]: https://kotlinlang.org/docs/multiplatform/supported-platforms.html "Stability of supported platforms | Kotlin Multiplatform"
[3]: https://developer.android.com/kotlin/multiplatform "Kotlin Multiplatform | Android Developers"
