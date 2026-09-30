# AIRI — خطة طويلة الأجل لسطح التنفيذ الحي

> **الحالة عند البدء:** `PLANNED / FIRST_INCREMENT_IN_PROGRESS`
> **نطاق هذه الدفعة:** `main` و`cp-foundation` عند الرأسين المتناظرين، دون إنشاء runtime أو event bus موازٍ.

## 1. المرجع الذي بُنيت عليه الخطة

- `main`: `dacc317bfa39af1e79665d636a0e46a471c69403`
- `cp-foundation`: `d51602f885016de07901bdc9b75bba0b59897213`
- آخر تحديث مشترك: **Harden Phase 3 agent and memory reliability**.
- آخر بوابات CI الظاهرة عند التدقيق: Android CI وArchitecture Audit وDeep Audit وOracle على الفرعين **success**.
- الملكية الحالية التي يجب الحفاظ عليها:
  - `ChatViewModel` يملك حالة المحادثة والإرسال والإلغاء.
  - `ExecutionStatusBus` يملك حالة التنفيذ وtrace المملوك بـ`executionId`.
  - `TaskExecutionTracker` يسقط أحداث التنفيذ إلى خطوات مرئية ولا يخترع خطوات من عدد المحاولات.
  - `AgentPlanViewModel` يملك projection الواجهة والحفظ المؤقت الآمن.
  - `ChatScreen` يركب السطح فوق Composer ويمرر الإلغاء إلى `ChatViewModel.cancelGeneration()`.

## 2. الهدف المنتجّي

عند تشغيل مهمة متعددة الخطوات، يرى المستخدم في المحادثة نفسها:

1. ملخصاً صغيراً لا يزاحم Composer.
2. الحالة الحالية والهدف والتقدم الحقيقي.
3. الخطوة الجارية والأداة/النشاط المختصر الآمن.
4. تفاصيل موسعة عند الطلب، مع trace مصفى ومحدود.
5. فرقاً واضحاً بين queued/running/retrying/completed/failed/cancelled.
6. إلغاءً حقيقياً يوقف التنفيذ، لا مجرد إخفاء animation.
7. استعادة آمنة ومعلنة بعد lifecycle/process recreation، أو حالة `BLOCKED` إذا لم يثبت ذلك.

لا يدخل في الهدف عرض chain-of-thought الخام أو payloads أو credentials أو headers أو cookies أو تخمينات UI.

## 3. مراحل التنفيذ

### M0 — تدقيق وإقفال مصدر الحقيقة

- جرد كل producer لـ`ExecutionStatusBus` و`AgentActivityBus` و`TaskExecutionTracker`.
- تسجيل علاقة `sessionId → requestId → executionId → actionId`.
- فصل الأدلة المثبتة عن التوقعات، وتسجيل قيود Android SDK والجهاز/provider.
- بوابة الإغلاق: تقرير تدقيق + root-cause matrix + لا ادعاء runtime غير مثبت.

### M1 — عقد التنفيذ الحي (الدفعة الحالية)

- توحيد terminal visibility لكل الحالات، بما فيها `CANCELLED`.
- منع stale execution من تعديل projection الحالي.
- تثبيت اختبارات policy للظهور، الإلغاء، lifecycle، والخصوصية.
- إضافة semantics للسطح التفاعلي دون نقل الملكية من ViewModel.

### M2 — أحداث الأدوات الفعلية

- تمرير `executionId` و`actionId` إلى مسارات SkillService/legacy tools المتبقية.
- ضمان start ثم terminal واحد فقط لكل action، مع duration وsummary sanitized.
- رفض callbacks المتقادمة أو المكررة قبل وصولها إلى UI.

### M3 — Progress tree حقيقي

- تمثيل waves/children من graph الفعلي بدلاً من قائمة مسطحة عند توفر العقد.
- إبقاء الخطوات غير المنفذة queued فقط إذا وصل admission موثوق لها.
- ربط artifact/evidence بالـstep المالك مع project/session boundary.

### M4 — Composer surface وRTL/accessibility

- ملخص collapsed منخفض الارتفاع فوق Composer.
- تفاصيل expanded عبر السطح الحالي دون ModalBottomSheet جديد غير ضروري.
- `paneTitle`, progress semantics، labels مترجمة، touch targets، bidi آمن للمعرفات التقنية.
- اختبار font-scale وRTL/LTR وscreen-reader على جهاز/محاكي عند توفره.

### M5 — Cancellation and lifecycle

- إثبات Chat → ViewModel → orchestrator → backend cancellation.
- عدم تسليم success بعد cancellation أو partial stream غير terminal.
- rotation/process recreation: restore snapshot المسموح فقط، وعدم استئناف side effect تلقائياً.
- حفظ trace/history فقط عبر المخازن القائمة وبحدود retention واضحة.

### M6 — Performance and hardening

- تثبيت نماذج UI immutable/stable حيث يلزم.
- bounded trace/activity buffers ومراقبة الضغط.
- منع recomposition الناتج من clock reads أو list identity غير المستقرة.
- instrumented benchmark/screenshot evidence عند توفر Android SDK/device.

### M7 — CI وrelease evidence

- `verify_core_changes.py` وlocal unit/static checks.
- Android CI + Deep + Architecture + Oracle على كل دفعة.
- أي device/provider/OAuth gate يسجل `BLOCKED` أو `EXTERNAL_PENDING` ولا يتحول إلى `DONE` بالـCI وحده.

## 4. تعريف DONE

لا يغلق بند إلا بوجود: root cause مثبت، source change، positive/negative regression tests، CI ناجح، ودليل runtime/UI عندما ينطبق. نجاح CI وحده يثبت المصدر والبناء والاختبارات التي شغّلها workflow فقط.

## 5. أول تعديل مقصود

البدء بإصلاح projection واضح في سياسة ظهور اللوحة: **الإلغاء نتيجة نهائية مرئية مثل الفشل والنجاح**. الرأس الحالي يسجل `CANCELLED` داخل tracker، لكن `PlanPanelVisibilityPolicy` لا يسمح بظهوره في المسار العادي؛ لذلك يمكن أن تختفي نتيجة الإلغاء من Chat رغم أن runtime نشرها. سيضاف اختبار انحدار قبل الانتقال إلى تحسينات semantics وtrace.

## 6. مبادئ خارجية تم تدقيقها

تتفق الخطة مع توثيق Manus الرسمي لـ[Plan Mode](https://manus.im/blog/manus-plan-mode) وواجهات [task.listMessages](https://open.manus.ai/docs/v2/task.listMessages): الخطة المراجعة يجب أن تكون مميزة عن التنفيذ، و`plan_update` هو snapshot مرجعي، بينما `new_plan_step` و`tool_used` أحداث تفصيلية، و`status_update` هو مؤشر دورة الحياة. لذلك لا يجوز اعتبار وصول حدث أداة دليلاً على نجاحها، ولا يجوز عرض explanation/التفكير الخام للمستخدم.

وتتبع طبقة Compose مبادئ [unidirectional data flow](https://developer.android.com/develop/ui/compose/architecture) و[state hoisting](https://developer.android.com/develop/ui/compose/state-hoisting): الحالة تنزل من `AgentPlanViewModel` والأحداث تصعد عبر callbacks، ولا تنشئ عناصر العرض مالكاً ثانياً للحالة. كما ستُستخدم [semantics الرسمية](https://developer.android.com/develop/ui/compose/accessibility/semantics) لـ`paneTitle` وprogress وerror والـcollection، مع أهداف تفاعل لا تقل عن 48dp. وستُراجع التغييرات المتجاوبة وفق [window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)، وليس حسب نوع الجهاز.

أما الأداء، فمرجعه [Compose stability](https://developer.android.com/develop/ui/compose/performance/stability) و[lists](https://developer.android.com/develop/ui/compose/lists): نماذج العرض immutable قدر الإمكان، مفاتيح LazyColumn ثابتة، وعدم قراءة clock متغير داخل composition بلا حاجة. وللنص العربي المختلط ستُستخدم سياسة العرض المبنية على [Android BidiFormatter](https://developer.android.com/training/basics/supporting-devices/languages) للرموز التقنية، مع إبقاء القيم الخام للمنطق والنسخ والطلبات.

## 7. حالة الدفعة الأولى بعد التدقيق

`verify_core_changes.py`: **96/96 PASS**، و`git diff --check`: **PASS**. محاولة اختبار Gradle المستهدف توقفت قبل compilation بسبب عدم وجود Android SDK (`ANDROID_HOME`/`sdk.dir`)، لذلك لا يُسجل اختبار Android المحلي كنجاح. تبقى بوابة CI هي الدليل المطلوب للبناء والاختبارات على الرأس بعد نشر التغيير.
