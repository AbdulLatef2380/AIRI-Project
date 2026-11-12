# المرحلة 7 — مصفوفة الاختبارات والقياس

## الغرض

تربط هذه المرحلة المخاطر الوظيفية باختبارات محددة وبيئة تشغيل ودليل قابل للتنزيل. لا تُستخدم نسبة أسطر عامة كبديل عن دليل السلوك، ولا يُسجّل غياب emulator أو JaCoCo كنجاح.

المصدر القابل للتحقق هو [`config/phase7_test_matrix.json`](../../config/phase7_test_matrix.json)، ويُفحص عبر:

```bash
python3 scripts/phase7_test_matrix.py --output phase7-test-matrix-report.json
python3 scripts/phase7_test_matrix.py --evidence-root app/build --output phase7-test-matrix-runtime-report.json
python3 scripts/phase7_coverage_observability.py --output phase7-coverage-report.json
python3 scripts/phase7_environment_gates.py --output phase7-environment-gates-report.json
```

## سياسة الحالات

- **PASS:** الاختبار نُفّذ بنجاح والدليل محفوظ في التقرير أو artifact.
- **SKIP:** الاختبار غير مطلوب لهذا التشغيل، مع ذكر سبب قابل للتدقيق.
- **BLOCKED:** الاختبار مطلوب لكن البيئة أو dependency تمنع تشغيله؛ لا يُحسب نجاحًا.
- **NOT_VERIFIED:** يوجد test target أو contract، لكن لم يُثبت تشغيله في هذا التقرير.

لا يُسمح بتحويل وجود ملف اختبار إلى `PASS`. مدقق المصفوفة يفصل `inventory` عن `runtime_evidence`.

## طبقات التشغيل

| الطبقة | متى | ما الذي تشغله | البيئة |
|---|---|---|---|
| Fast PR | كل pull request | static contracts، Python audits، core-domain/JVM tests حيث تتوفر الأدوات | بلا emulator |
| Main CI | push إلى الفروع وخصوصًا `main` | Android compile، unit tests، lint، debug/release source checks | GitHub runner + Android SDK |
| Extended Device | matrix أو تشغيل يدوي | UI، RTL، accessibility، migration، JNI/native، process/restart recovery | emulator أو جهاز Android |
| Release Verification | نجاح main مع signing evidence | APK/AAB، hash، mapping، manifest، توقيع وOAuth certificate checks | runner مع أو بدون secrets بحسب البند |

أضيف workflow مستقل `.github/workflows/fast_pr.yml` لطبقة Fast PR بلا Android SDK أو emulator. ويحتوي workflow Android الرئيسي على `build` و`instrumentation_matrix` وrelease evidence؛ وتضيف المرحلة 7 manifest موحدًا وتقارير صريحة حتى لا تختلط نتائجها.

## مصفوفة المخاطر

المخاطر P0/P1 التالية لها test targets محددة: AgentLoop، Tool Routing، Memory isolation، Connectors، Streaming، Local inference، Trace، Security، UI، JNI/native، recovery/migrations، وremote-control rules. إذا غاب target عن شجرة المصدر يفشل مدقق المصفوفة. إذا كان target موجودًا لكن لم تُحفظ نتيجة تشغيله، تبقى الحالة `NOT_VERIFIED`.

## Coverage observability

عند إنتاج JaCoCo XML، يسجل التقرير counters الخاصة بـline وbranch وmethod/function دون وضع threshold عام. عند غياب XML تكون الحالة `BLOCKED`، وليس `0%` أو `PASS`. أما changed-code coverage فتظل `NOT_VERIFIED` ما لم توجد خريطة موثوقة بين ملفات التغيير وcounter report.

تُعرّف خريطة المكونات في [`config/phase7_coverage_components.json`](../../config/phase7_coverage_components.json)، ويجمع المدقق counters على مستوى classes المطابقة لمسارات المصدر. يسجل التقرير صراحةً حالة كل مكوّن، و`SKIP` عند عدم وجود ملفات متغيرة، و`NOT_VERIFIED` للـtrend حتى يُمرر تاريخ artifacts فعلي.

Trend تُقرأ من artifacts المتعاقبة في CI، ولا تُبنى من قيم مخترعة محليًا. المقارنة المطلوبة مستقبلًا هي: تغير coverage للمكونات المتأثرة فقط، عدد الاختبارات، وعدد PASS/SKIP/BLOCKED/NOT_VERIFIED.

Baseline الحالي محفوظ في `reports/phase7/`: inventory المصفوفة مكتمل (12/12 targets موجودة)، بينما التنفيذ runtime وJaCoCo مصنفان `NOT_VERIFIED`/`BLOCKED` لأنهما يحتاجان CI أو Android SDK.

وتحفظ `config/phase7_environment_gates.json` البنود التي لا يجوز إسقاطها أو عدّها ناجحة بسبب غياب البيئة: process death/restart، Firebase emulator، sandbox provider بحساب مؤقت، وJNI أو UI device evidence. لكل بند status وسبب واحتياج ودليل متوقع؛ لذلك تكون النتيجة `BLOCKED` أو `NOT_VERIFIED` بدل نجاح صامت.

## فجوات مقصودة وحالات BLOCKED

- Process death الكامل وcorruption recovery يحتاجان emulator/device ومشهد persistence فعلي؛ وجود اختبارات migration/recovery لا يثبت التشغيل من Sandbox.
- JNI smoke يحتاج native library وmodel fixture مناسب؛ لا يُعاد استخدام unit test كبديل له.
- Fake providers موجودة في عدة عقود JVM، أما sandbox provider بحسابات مؤقتة فيبقى تشغيلًا موسعًا لا يدخل PR الافتراضي.
- UI golden/scrolling/dynamic font/accessibility تُثبت فقط من instrumentation artifacts، لا من compile أو unit tests.
