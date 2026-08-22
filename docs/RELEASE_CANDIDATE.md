# AIRI Core — Release Candidate

> **سجل تاريخي:** يغطي هذا المستند revision `84f8e1b4` فقط. لا يغطي التزامات `cp-foundation` المحلية اللاحقة التي تبدأ من `31d06383`، إذ إن push البعيد رفض المصادقة في جلسة 2026-08-23 ولم يتوفر CI صالح للمراجعات الجديدة. الحالة الحالية ودليل القبول المحلي موجودان في [`AIRI_FINAL_ACCEPTANCE_REPORT.md`](AIRI_FINAL_ACCEPTANCE_REPORT.md) و[`product/LOCAL_ACCEPTANCE_EVIDENCE_2026-08-23.md`](product/LOCAL_ACCEPTANCE_EVIDENCE_2026-08-23.md).

## تعريف المرشح

هذا المستند يسجل قرار **Release Candidate** لفرع `cp-foundation` عند revision `84f8e1b4bcc2c07eb88e9609f9cb35cf09399ca3` بتاريخ 2026-08-21. لم يُدمج الفرع مع `architecture-refactor` ولم يُعدّل ذلك الفرع؛ يبقى خط المراجعة المحمي عند `1027dee20511b294437c4f47f08e9c2f54050eaf`.

> هذا المرشح دليل قابل للتدقيق على نجاح نطاق البناء والاختبارات والحراس المحدد. وهو **ليس** تصريحاً لتوزيع إنتاجي موقّع أو تشغيل Firebase الإنتاجي أو اعتماد تجاري نهائي من دون الضوابط الخارجية الموضحة أدناه.

| المجال | الحالة | الدليل | النتيجة العملية |
|---|---|---|---|
| Android | `TESTED` | بوابة Android CI ناجحة، بما فيها debug، unit، lint، release sources، instrumentation، والتحقق من native output [1] | لم يظهر انحدار Android في مسار CI المرجعي. |
| Desktop Windows | `TESTED` | بوابة Windows نجحت في اختبارات Desktop وبناء MSI [2] | MSI قابل للبناء والتحقق على runner Windows؛ تشغيل التطبيق للمستخدم النهائي يحتاج قبولاً مستقلاً على جهاز Windows. |
| Desktop Linux | `BUILD_VERIFIED` | اختبارات `:app-desktop:test` المحلية نجحت بعد revision المرشح. | النطاق المشترك يعمل في بيئة Linux؛ لا تُحوّل هذه النتيجة تلقائياً إلى قبول Windows runtime. |
| الهندسة متعددة المنصات | `SOURCE_VERIFIED` | Architecture Audit ناجح [3] | تظل حدود النواة المشتركة والمنصات قابلة للتدقيق في CI. |
| التدقيق العميق | `SOURCE_VERIFIED` | Deep Audit ناجح [4] | فحوص المصدر والحراس المتضمنة في البوابة اجتازت. |
| سلسلة التوريد | `BUILD_VERIFIED` | Gradle dependency verification بقي مفعلاً؛ أضيفت بصمات SHA-256 المطلوبة من Maven Central فقط. | لا توجد استثناءات أو تعطيل للتحقق من التبعيات. |
| توقيع الإنتاج | `EXTERNAL_VERIFICATION_REQUIRED` | CI يقصر تغليف APK/AAB الموقّع على `main` مع أسرار التوقيع الأربعة. | لا يوجد artifact إنتاجي موقّع من هذا الفرع. |

## بوابات CI

اجتازت البوابات الأربع جميعاً على **revision نفسه**. بوابة Android أعادت بناء واختبار النواة المشتركة، التطبيق Android، مصادر release، اختبارات instrumentation، والمكتبة الأصلية. بوابة Windows أعادت اختبار Desktop وإنتاج MSI على runner Windows، ولذلك لا يعتمد دليل الحزمة على نظام Linux المحلي فقط.

| البوابة | النتيجة | Run | الدليل |
|---|---|---:|---|
| AIRI Android CI | `success` | `32513740298` | [سجل التنفيذ][1] |
| AIRI Desktop Windows | `success` | `32513740332` | [سجل التنفيذ][2] |
| AIRI Architecture Audit | `success` | `32513740335` | [سجل التنفيذ][3] |
| AIRI Deep Audit | `success` | `32513740288` | [سجل التنفيذ][4] |

## تحقق محلي وأمن التحكم المقترن

أعيد تشغيل `:app-desktop:test` محلياً باستخدام JDK 17 وAndroid SDK المحلي بعد تحديث metadata، ونجحت المهمة. كما اجتازت أوامر الحراس التالية بخروج `0` على revision المرشح:

```bash
python3 scripts/airi_release_health.py
python3 scripts/airi_remote_control_health.py
python3 scripts/airi_remote_control_security.py
python3 scripts/airi_firestore_rules_test.py
python3 scripts/airi_localization_health.py
```

| الحارس | الحالة | الدليل المقرر |
|---|---|---|
| `airi_release_health.py` | `SOURCE_VERIFIED` | مصادر release مستقلة عن أسرار signing؛ لا تغليف موقّع إلا على `main` مع الأسرار الأربعة، ويحذف CI مادة التوقيع المؤقتة. |
| `airi_remote_control_health.py` | `SOURCE_VERIFIED` | تغطية dispatcher، حد النص، expiry قبل القبول، sequence monotonic، ورفض الجلسة المسحوبة. |
| `airi_remote_control_security.py` | `SOURCE_VERIFIED` | لا raw socket ولا cleartext HTTP ولا service account ولا secret مضمّن ضمن paired-control. |
| `airi_firestore_rules_test.py` | `SOURCE_VERIFIED` | المسارات scoped، وجلسات relay-managed، والأوامر غير قابلة للتعديل أو الحذف من العميل. |
| `airi_localization_health.py` | `HISTORICAL_SNAPSHOT` | في revision التاريخي سجّل الحارس 252 قيمة مرشحة. أعاد فحص 2026-08-23 بعد تطبيق الموارد `likely_untranslated_values=0`؛ تبقى المراجعة البشرية مطلوبة. |

## سلسلة التوريد في هذا الإغلاق

عالج الإغلاق فشل Windows CI تدريجياً من دون استثناءات verification. أضيفت فقط بصمات SHA-256 للـPOM/JAR/MODULE التي طلبها runner Windows: `desktop-jvm-windows-x64`، و`skiko-awt-runtime-windows-x64`، و`gradle-plugin-internal-jdk-version-probe`. جُلبت الملفات عبر HTTPS من Maven Central، وحُسبت بصماتها قبل إضافتها إلى `gradle/verification-metadata.xml`. لا تغير هذه الإضافات إصدارات التبعيات ولا سياسة Gradle؛ بل توسع قائمة allowlist المشفرة اللازمة لمسار Windows.

## شروط ما قبل النشر الخارجي

لا يحق لهذه الوثيقة رفع تصنيف البنود التالية. يجب تنفيذها بمالك الحسابات والأجهزة والإفصاحات اللازمة، ثم حفظ دليل منفصل مرتبط بالـcommit أو tag النهائي.

| البند الخارجي | الحالة | شرط الإغلاق |
|---|---|---|
| توقيع APK/AAB فعلي | `EXTERNAL_VERIFICATION_REQUIRED` | إنشاء وتشغيل job على `main` بالأسرار الأربعة، والتحقق من keystore وSHA-256 وAAB وmapping. |
| Firebase/Google OAuth الإنتاجي | `EXTERNAL_VERIFICATION_REQUIRED` | إعداد المشروع الحقيقي، مراجعة authorized origins/redirects، وتنفيذ smoke test بحسابات اختبار. |
| Remote Control عبر backend إنتاجي | `EXTERNAL_VERIFICATION_REQUIRED` | اختبار pairing وauthorization وreplay وrevocation مع مشروع Firebase إنتاجي محدود الصلاحيات، بلا service account في Desktop. |
| Windows runtime النهائي | `EXTERNAL_VERIFICATION_REQUIRED` | تثبيت MSI على Windows حقيقي، launch/render، keyboard/mouse، persistence/restart، resize/focus/close. |
| أجهزة Android فعلية | `EXTERNAL_VERIFICATION_REQUIRED` | تغطية ABI/ذاكرة/حرارة/شبكة وصوت وصلاحيات على أجهزة مادية. |
| الترخيص والامتثال التجاري | `EXTERNAL_VERIFICATION_REQUIRED` | إغلاق عناصر `docs/commercial/LICENSE_MATRIX.md` ومراجعة قانونية للنماذج والتبعيات والتوزيع. |

## قرار الإصدار

يعتمد هذا المستند **Release Candidate للبناء والاختبارات** على `cp-foundation` فقط. يجوز استخدامه كأساس لمرحلة التحقق الخارجي، لكنه لا يسمح بدمج الفروع ولا بالنشر الإنتاجي ولا بادعاء قبول runtime لم تُنفذ له الأدلة المحددة.

## المراجع

[1]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32513740298 "AIRI Android CI #32513740298"
[2]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32513740332 "AIRI Desktop Windows #32513740332"
[3]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32513740335 "AIRI Architecture Audit #32513740335"
[4]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32513740288 "AIRI Deep Audit #32513740288"
