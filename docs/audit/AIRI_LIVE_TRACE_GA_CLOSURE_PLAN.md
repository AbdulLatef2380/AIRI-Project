# AIRI — خطة إغلاق فجوات سجل التنفيذ الحي للوصول إلى GA

**النطاق:** ميزة سجل التنفيذ الحي المضمن أسفل رد AIRI  
**الرأس المرجعي:** `main` عند `9e9ab4ac`  
**الحالة الحالية:** بنية الإسقاط والاختبارات المنطقية موجودة، لكن الميزة ليست مغلقة إنتاجياً.  
**الهدف:** تحويل السجل من إسقاط منطقي جزئي إلى سطح إنتاجي موثوق، مترجم، قابل للوصول، ومثبت على Android قبل إعلان GA.

---

## 1. القرار التنفيذي ونطاق Definition of Done

لا تُعلن الميزة `GA READY` إلا إذا تحققت الشروط الآتية معاً:

1. كل حدث مرئي يملك `executionId` صحيحاً، وهوية إجراء ثابتة عند الحاجة، وحالة lifecycle قابلة للتفسير.
2. كل مجموعة محسوبة في `UnifiedExecutionTraceProjection` تُعرض فعلياً في Compose أو تُحذف من العقد؛ لا توجد بيانات محسوبة وغير مرئية.
3. الملخص يعرض الملفات والأوامر والأدوات والبحث والذاكرة والمهام بأعداد صحيحة.
4. الترجمة الديناميكية تمر عبر موارد Android، ولا يعتمد العرض على نصوص خام أو خرائط hard-coded داخل projector.
5. حالات `PENDING/WAITING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` مميزة دلالياً وبصرياً.
6. لا تتكرر دورة حياة الإجراء عند اتحاد `ExecutionTrace`, `ExecutionStatusBus`, `AgentActivityBus`, `AppEventBus`.
7. توجد اختبارات JVM، Compose UI، RTL، accessibility، stress، وinstrumentation أساسية.
8. تنجح جميع بوابات oracle وresource parity وAndroid CI، بما في ذلك اختبارات الميزة الجديدة.
9. لا يُقبل أي `EXTERNAL_PENDING` دون دليل جهاز/Emulator أو قرار مخاطر موثق من مالك الإصدار.

---

## 2. الفجوات العشر وخطة إغلاقها

### الفجوة 1 — المجموعات محسوبة لكنها لا تُعرض فعلياً

**المشكلة:** `UnifiedExecutionTraceProjector` يبني `projection.groups`، بينما `AgentLifecycleTrackerPanel` يعتمد أساساً على `taskTree`، و`ActivityGroupHeader` موجود لكنه غير مستخدم في مسار العرض.

**الإغلاق المطلوب:**

- اختيار عقد عرض واحدة واضحة:
  - السطر المختصر.
  - مجموعات قابلة للفتح والإغلاق.
  - Parent/Child داخل المجموعة عند وجوده.
- تعديل `AgentLifecycleTrackerPanel.kt` ليعرض `projection.groups` فعلياً.
- جعل ترتيب المجموعات ثابتاً: Plan/Tasks، Files، Commands، Tools/Connectors، Search، Memory، System.
- حفظ حالة فتح المجموعة لكل `executionId`، وليس حالة عامة مشتركة بين تنفيذين.
- إبقاء التمرير الداخلي محدوداً، مع عدم دفع composer خارج الشاشة.
- منع عرض نفس الحدث مرة في `groups` ومرة في `taskTree`.

**الاختبارات:**

- Compose test يثبت ظهور عناوين Files/Commands/Tools/Search/Memory/Tasks.
- Compose test يثبت الفتح والإغلاق لكل مجموعة.
- اختبار تنفيذين متتابعين يثبت عدم تسرب حالة التوسيع.
- اختبار snapshot/semantics يتأكد من عدم وجود صف مكرر.

**معيار القبول:** كل مجموعة موجودة في `projection.groups` تظهر أو تُستبعد صراحة من العقد، ولا يبقى `ActivityGroupHeader` غير مستخدم.

---

### الفجوة 2 — الملخص لا يتضمن عمليات الذاكرة

**المشكلة:** `UnifiedExecutionTraceSummary` لا يحتوي `memoryOperations` رغم وجود تصنيف MEMORY.

**الإغلاق المطلوب:**

- إضافة `memoryOperations: Int` إلى النموذج.
- احتساب `MEMORY` في projector.
- إضافة string resources عربية وإنجليزية وإسبانية وصينية للملخص.
- تحديد هل `toolsUsed` يشمل CONNECTOR أم يظهر كعداد مستقل؛ تثبيت القرار في العقد والاختبار.
- عدم عدّ تحديثات الحالة المتكررة كعمليات ذاكرة مستقلة إذا كانت تحمل `actionId` نفسه.

**الاختبارات:**

- ذاكرة قراءة واحدة وكتابة واحدة تنتجان `memoryOperations=2`.
- تكرار lifecycle لنفس عملية الذاكرة لا يضاعف العداد.
- resource parity لكل اللغات.

**معيار القبول:** السطر المختصر يعرض الذاكرة عند وجودها، ولا يظهر عداداً غير صحيح أو ناقصاً.

---

### الفجوة 3 — الترجمة العربية الديناميكية ليست طبقة موارد كاملة

**المشكلة:** `localizeDynamicTitle` يستخدم خرائط hard-coded داخل Kotlin، وتغطيته محدودة، وبعض النصوص الخام والأسماء الإنجليزية تظهر في الصور.

**الإغلاق المطلوب:**

- إنشاء طبقة `ExecutionTracePresentation` أو `ExecutionTraceLocalization` تستقبل `machineTag`, category, status, fallback.
- ربط كل machine tag معروف بمورد Android:
  - `file_created`
  - `file_read`
  - `command_executed`
  - `terminal_execute`
  - `tool_call`
  - `web_search`
  - `search_started`
  - `search_completed`
  - `memory_read`
  - `memory_write`
  - `github_issue`
  - connector actions
  - planning/task lifecycle
- تمرير `Context`/resource resolver إلى طبقة العرض بدلاً من `Locale.getDefault()` داخل projector.
- جعل fallback آمناً ومترجماً حسب الفئة، وليس النص الخام الكامل.
- فصل اسم الأداة المحلي عن التفاصيل الآمنة، بحيث لا يُعرض path أو secret أو prompt.
- إضافة ترجمة كاملة إلى `values-ar`, `values`, `values-es`, `values-zh`.

**الاختبارات:**

- اختبار locale عربي لكل machine tag.
- اختبار عدم ظهور raw machine tag للمستخدم.
- اختبار fallback عند machine tag مجهول.
- اختبار resource parity: صفر مفاتيح مفقودة.

**معيار القبول:** لا تظهر عناوين إنجليزية غير مقصودة في واجهة العربية، ولا تعتمد الترجمة على hard-coded map داخل projector.

---

### الفجوة 4 — حالات الانتظار والإلغاء غير دقيقة

**المشكلة:** `CANCELLED` يُحوّل إلى `PENDING`، و`WARN` قد يتحول إلى `RUNNING`. لا يوجد فصل دلالي واضح بين queued/waiting/awaiting confirmation/cancelled.

**الإغلاق المطلوب:**

- توسيع `TaskStatus` أو عقد الحالة ليشمل حالة صريحة `WAITING` و`CANCELLED` إذا لم تكن موجودة في العقد الموحد.
- تعريف جدول تحويل مركزي لكل مصدر:
  - queued → WAITING
  - awaiting_confirmation → WAITING
  - started/running → RUNNING
  - completed → COMPLETED
  - failed/error → FAILED
  - cancelled/stopped → CANCELLED
- عدم استنتاج الحالة من `message.contains("start")` إلا كـfallback موثق.
- إضافة `phase/status` صريح في كل حدث مهيكل.
- عرض icon/color/label مختلف لكل حالة.
- الحفاظ على دليل الإلغاء مرئياً بعد نهاية التنفيذ، وعدم اختفائه فوراً.

**الاختبارات:**

- اختبارات تحويل لكل machine tag وحالة.
- اختبار إلغاء لا يتحول إلى Pending.
- Compose test للأيقونات وcontent descriptions.
- اختبار حدث waiting ثم running ثم completed.

**معيار القبول:** يستطيع المستخدم تمييز الانتظار، التشغيل، النجاح، الفشل، والإلغاء من النص والأيقونة واللون.

---

### الفجوة 5 — `stableActionId` غير مضمون لكل أداة/موصل

**المشكلة:** `actionId` اختياري في النموذج، وبعض compatibility events تحمل executionId دون actionId.

**الإغلاق المطلوب:**

- إنشاء `ExecutionActionIdentity` أو factory مركزية.
- توليد `stableActionId` عند قبول الإجراء، قبل dispatch:
  - `executionId + stepId + toolName + invocationOrdinal`
  - أو UUID حتمي محفوظ في runtime context.
- تمرير الهوية إلى:
  - `ExecutionStatusBus`
  - `ToolDispatcher`
  - `ConnectorToolBridge`
  - `AgentActivityBus`
  - `GlobalAgentEventDispatcher`
  - trace persistence.
- منع terminal event بلا start event إلا عبر مسار recovery موثق.
- إبقاء compatibility events للتوافق فقط، مع ربطها بالهوية وليس بالعنوان.
- رفض/تسجيل الأحداث التي لا تملك ownership بدلاً من عرضها كإجراء مستقل.

**الاختبارات:**

- start/complete/fail/cancel بنفس `actionId` رغم اختلاف المصدر.
- تنفيذ أداتين متطابقتين بالتوازي لا يندمجان.
- terminal-before-start لا ينشئ صفاً مضللاً.
- إعادة المحاولة لا تعيد استخدام هوية invocation سابقة إلا إذا كان الإجراء نفسه idempotent.

**معيار القبول:** كل صف lifecycle مهيكل يمكن ربطه بإجراء واحد ثابت، ولا يعتمد dedupe على تشابه العنوان وحده.

---

### الفجوة 6 — السطح البصري لا يطابق Timeline إنتاجياً

**المشكلة:** الصور تُظهر صفوفاً متكررة/مسطحة، عناوين مقطوعة، وتبايناً محدوداً بين المجموعات والحالات.

**الإغلاق المطلوب:**

- إعادة تصميم `AgentLifecycleTrackerPanel` كـinline execution timeline:
  - header compact summary واضح.
  - group cards خفيفة لا تستهلك الشاشة.
  - خط زمني أو indentation ثابت للـParent/Child.
  - status icon مستقل عن category icon.
  - details عند النقر فقط.
- تثبيت حد الارتفاع مع LazyColumn داخلي.
- الحفاظ على composer أسفل الشاشة في كل الحالات.
- تحسين ellipsis: العنوان المختصر + تفاصيل آمنة عند التوسيع.
- استعمال ألوان theme/accessibility لا ألوان ثابتة غير قابلة للتمييز.
- إضافة content descriptions وsemantics لكل group/event/status.
- ضمان أن collapsed state هو الافتراضي بعد اكتمال التنفيذ، مع فتح تلقائي أثناء وصول بيانات جديدة فقط إذا كان المستخدم يتابع آخر الأحداث.

**الاختبارات:**

- Compose screenshot/golden أو semantic snapshot للحالات الأساسية.
- اختبار ارتفاع panel مع 1، 10، 100، 500 حدث.
- اختبار composer visibility.
- اختبار dark theme وfont scale 1.3 و2.0.

**معيار القبول:** الواجهة مفهومة في لقطة واحدة، مضغوطة، بلا تكرار، ولا تزيح حقل الإدخال أو تغطيه.

---

### الفجوة 7 — لا توجد UI/Compose/RTL instrumentation كافية

**المشكلة:** الاختبارات الحالية منطقية JVM، ولا تثبت الشكل الفعلي على Android/Compose.

**الإغلاق المطلوب:**

- إنشاء `AgentLifecycleTrackerPanelTest` باستخدام Compose test rule.
- إنشاء حالات test data ثابتة قابلة لإعادة الاستخدام.
- إضافة اختبارات:
  - render summary.
  - expand/collapse groups.
  - render parent/child.
  - running/completed/failed/waiting/cancelled.
  - Arabic RTL.
  - English LTR.
  - large font scale.
  - accessibility semantics.
  - live updates while list is scrolled.
- إضافة instrumentation test على Emulator في CI.
- عند الإمكان إضافة screenshot artifact لكل حالة.

**معيار القبول:** كل حالة تصميمية رئيسية لها اختبار Android قابل للتشغيل في CI، وليس مجرد اختبار projector.

---

### الفجوة 8 — لا يوجد اختبار ضغط فعلي لواجهة Compose

**المشكلة:** يوجد اختبار إسقاط كثيف، لكنه لا يختبر Compose عند مئات الأحداث والتحديثات المتزامنة.

**الإغلاق المطلوب:**

- إضافة stress fixture يولد 1,000–5,000 حدث موزعاً على 5–10 مجموعات.
- اختبار:
  - out-of-order arrival.
  - start/terminal races.
  - تنفيذات متزامنة.
  - updates أثناء streaming.
  - expansion state أثناء التحديث.
  - bounded memory/list size.
- قياس زمن projection وrecomposition التقريبي في benchmark أو macrobenchmark.
- وضع حد أقصى للأحداث المحفوظة في السجل المرئي، مع retention للملخص والأخطاء النهائية.
- منع إعادة حساب projection أكثر من اللازم عبر stable keys وderived state.

**معيار القبول المقترح:**

- لا crash أو ANR مع 5,000 حدث.
- projection ضمن حد زمني موثق على CI.
- لا تتضاعف الصفوف بسبب out-of-order events.
- لا يفقد المستخدم الحدث الفاشل أو النهائي.

---

### الفجوة 9 — فشل resource parity في es وzh

**المشكلة الحالية:**

```text
values-es: Missing keys: 13
values-zh: Missing keys: 13
```

**الإغلاق المطلوب:**

- استخراج قائمة المفاتيح الناقصة آلياً من oracle.
- إضافة الترجمات الصحيحة في:
  - `app/src/main/res/values-es/strings.xml`
  - `app/src/main/res/values-zh/strings.xml`
- مراجعة placeholders والتنسيق وعدم إدخال نص إنجليزي غير مقصود.
- تشغيل localization strict health.
- إضافة CI gate يمنع merge عند missing key > 0.

**معيار القبول:**

```text
Resource key parity values-ar: Missing keys: 0
Resource key parity values-es: Missing keys: 0
Resource key parity values-zh: Missing keys: 0
```

---

### الفجوة 10 — غياب إثبات Android/Emulator/Production runtime

**المشكلة:** اختبار Gradle المحلي لم يبدأ لغياب Android SDK، ولا توجد UI/RTL/device evidence مكتملة.

**الإغلاق المطلوب:**

- جعل GitHub Actions المرجع الرسمي لتشغيل:
  - unit tests.
  - Compose/instrumentation tests.
  - resource parity.
  - release oracle.
- إضافة Emulator matrix للإصدار الأدنى وإصدار Android حديث، مع locale عربي وإنجليزي.
- حفظ artifacts:
  - test reports.
  - screenshots/golden diffs عند الفشل.
  - accessibility output.
  - APK/AAB evidence.
- تشغيل smoke test على جهاز ARM64 فعلي أو اعتماد مختبر جهاز موثق.
- اختبار process death وrestore وrotation/font scale وoffline mode.
- فصل ما يحتاج provider/OAuth حقيقياً إلى `EXTERNAL_PENDING` مع مالك وموعد وقرار مخاطر.

**معيار القبول:** لا تبقى عبارة “لم يُختبر على Android” في قرار release النهائي، أو تكون مرفقة بقبول مخاطر رسمي صريح.

---

## 3. ترتيب التنفيذ المقترح

### المرحلة A — تثبيت العقود والهوية

**النطاق:** الفجوات 2، 4، 5.

- إضافة memory summary.
- تثبيت status model.
- فرض stableActionId.
- تحديث projector والاختبارات المنطقية.

**بوابة الخروج:** جميع اختبارات projector وtrace lifecycle ناجحة، ولا يوجد action lifecycle بلا هوية.

### المرحلة B — إكمال localization وresource parity

**النطاق:** الفجوة 3 و9.

- طبقة localization مستقلة.
- machineTag catalog.
- إكمال ar/es/zh/en resources.
- oracle parity صفر.

**بوابة الخروج:** `verify_core_changes.py` يمر بالكامل، ولا raw dynamic labels في الاختبارات.

### المرحلة C — ربط المجموعات بالواجهة

**النطاق:** الفجوتان 1 و6.

- عرض `projection.groups` فعلياً.
- دمج group header مع task tree دون ازدواج.
- تحسين compact timeline، الحالات، icons، details، composer anchoring.

**بوابة الخروج:** مراجعة UI يدوية على اللقطات المرجعية + semantic Compose tests أولية.

### المرحلة D — إثبات UI وRTL وaccessibility

**النطاق:** الفجوة 7.

- Compose UI tests.
- RTL/English.
- font scale.
- semantics/content descriptions.
- screenshots artifacts.

**بوابة الخروج:** Android instrumentation ناجح على Emulator matrix.

### المرحلة E — الضغط والاستقرار

**النطاق:** الفجوة 8.

- stress generator.
- out-of-order/concurrency tests.
- recomposition/memory bounds.
- retention policy.

**بوابة الخروج:** لا crash/ANR/duplication تحت الحمل المحدد.

### المرحلة F — release proof وقرار GA

**النطاق:** الفجوة 10 وكل النتائج المتراكمة.

- CI كامل على commit الإصدار.
- مراجعة artifacts.
- device/ARM64 evidence.
- تحديث audit matrix لكل finding.
- قرار `GA READY` أو `NO-GA` موثق.

**بوابة الخروج:** كل P1/P2 مغلق أو `accepted-risk` موقع، وجميع بوابات CI خضراء.

---

## 4. مصفوفة الاختبارات النهائية

| الطبقة | الاختبار | شرط النجاح |
|---|---|---|
| Projector | unit tests لكل category/status/dedupe | 100% نجاح |
| Identity | lifecycle من كل مصدر | actionId ثابت |
| Localization | ar/en/es/zh | parity صفر |
| Compose | summary/groups/tree | render صحيح |
| RTL | Arabic layout | لا قص/تداخل |
| Accessibility | semantics/content descriptions | كل التحكمات قابلة للوصول |
| Stress | 5,000 حدث وout-of-order | لا crash/ANR/duplication |
| Instrumentation | Emulator matrix | build + tests ناجحة |
| Release | APK/AAB/mapping/hash | artifacts كاملة |
| Runtime | ARM64/process death/offline | evidence أو risk acceptance |

---

## 5. المخاطر وقرارات التصميم التي يجب حسمها قبل التنفيذ

1. هل تعرض المجموعة `CONNECTOR` مستقلة أم تُدمج تحت Tools؟ يجب تثبيت القرار في UX والعقد.
2. هل الإلغاء terminal نهائي دائماً؟ إذا نعم، يجب ألا يُحوّل إلى Pending.
3. ما الحد الأقصى للأحداث المرئية؟ المقترح 500–1,000 مع حفظ summary/errors.
4. هل تُعرض التفاصيل الخام للمستخدم؟ الافتراضي لا؛ العرض يمر عبر redaction وsafe presentation.
5. هل الترجمة تعتمد على locale التطبيق أم locale النظام؟ يجب أن تكون locale التطبيق.
6. هل يسمح للمستخدم بتثبيت مجموعة مفتوحة؟ يجب حفظ preference لكل execution أو لكل مستخدم، لا global mutable state.
7. كيف يُتعامل مع terminal-before-start؟ يُسجل كـrecovered action مع warning داخلي، لا كصف طبيعي مكتمل.

---

## 6. قائمة الإغلاق قبل الدمج

- [ ] `projection.groups` مستخدمة في واجهة العرض أو أزيلت من العقد.
- [ ] `memoryOperations` موجودة في model/summary/UI/tests.
- [ ] machineTag catalog مترجم عبر resources.
- [ ] `WAITING` و`CANCELLED` منفصلتان دلالياً وبصرياً.
- [ ] stableActionId مفروض على الأدوات والموصلات.
- [ ] لا صف lifecycle مكرر في UI.
- [ ] Compose UI tests وRTL tests مضافة.
- [ ] stress/macrobenchmark أو بديل موثق مضاف.
- [ ] es/zh resource parity = 0.
- [ ] Android CI وinstrumentation ناجحان.
- [ ] ARM64/process-death/offline evidence أو accepted-risk موثق.
- [ ] مراجعة بشرية نهائية للصور على الجهاز.
- [ ] تحديث تقرير التدقيق وقرار GA.

---

## 7. قاعدة القرار النهائي

### GA READY

فقط عندما تكون كل البنود أعلاه مغلقة، وتنجح CI وinstrumentation، ولا توجد P1/P2 غير مملوكة.

### CONDITIONAL / RELEASE CANDIDATE

مسموح فقط داخلياً أو لمختبري beta إذا كانت فجوات device/provider موثقة وP1 مغلقة، مع منع الإعلان العام.

### NO-GA

يظل القرار سارياً عند فشل resource parity، أو فشل Android CI، أو غياب إثبات stable identity/dedup، أو عدم وجود قبول مخاطر رسمي للفجوات الخارجية.
