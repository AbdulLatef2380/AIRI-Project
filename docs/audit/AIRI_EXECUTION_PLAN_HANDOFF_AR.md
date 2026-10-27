# خطة تنفيذ AIRI — Handoff للحساب التالي

**الإصدار:** 1.0  
**تاريخ إعداد الخطة:** 27 أكتوبر 2026  
**المستودع:** `AbdulLatef2380/AIRI-Project`  
**الفرع المرجعي:** `main`  
**آخر commit مثبت:** `dbdf19a8` — `feat(chat): harden response completion and continuation`  
**حالة الفرع:** `main` مطابق لـ `origin/main` عند إعداد هذه الوثيقة.

---

## 1. الغرض من هذه الوثيقة

هذه الوثيقة هي نقطة نقل رسمية للحساب التالي. يجب أن يبدأ التنفيذ منها، لا من التخمين أو من إعادة بناء تاريخ المحادثة. الهدف هو إكمال جاهزية AIRI للإطلاق العام GA، مع الحفاظ على التصميم الحالي لشاشة الدردشة وتحسين موثوقيتها ووضوحها وراحة استخدامها تدريجياً، من دون إعادة تصميم شامل أو استبدال الهوية البصرية الحالية.

النطاق لا يقتصر على Live Execution Trace. يشمل أيضاً طبقات الوكيل، الذاكرة، المهارات، الموصلات، OAuth، دورة استدعاء الأدوات، إرجاع النتائج إلى النموذج، ميزانية الإخراج، الردود غير المكتملة، شاشة الدردشة، CI، Android instrumentation، الأمان، والتوثيق النهائي لقرار GA.

---

## 2. قواعد إلزامية قبل بدء أي عمل

1. ابدأ دائماً بجلب `origin/main` وفحص `git status` وقراءة آخر commits. لا تفترض أن الفرع المحلي يعكس المستودع.
2. لا تستخدم `reset --hard` أو `git clean -fd` قبل حفظ أي عمل محلي والتحقق من طلب المستخدم. لا تحذف تعديلات غير مدفوعة دون تأكيد صريح.
3. لا تعلن `GA READY` بناءً على Oracle وحده. يلزم Android CI وinstrumentation ودليل runtime أو قبول مخاطر رسمي.
4. لا تعالج فشل CI بتغيير عشوائي. استخرج أول خطأ سببي، افصل عطل الاختبار عن عطل المحاكي وعن عطل البنية التحتية، ثم نفّذ أقل تعديل يثبت السبب.
5. لا تعِد بناء شاشة الدردشة أو تستبدل تصميمها. كل تحسين UI يجب أن يكون incremental، متوافقاً مع `AiriTheme` والمكونات الحالية، وقابلاً للتراجع.
6. لا ترسل continuation تلقائياً. يجب أن يراجع المستخدم prompt ويضغط إرسال بنفسه.
7. لا تعرض للمستخدم raw prompts أو secrets أو مسارات حساسة أو أجسام أخطاء المزود الخام.
8. لا تغيّر عقود callback أو نماذج lifecycle دون تحديث كل المستدعين والاختبارات والمدققين.
9. أي تغيير في الموارد يجب أن يحافظ على parity بين `values`, `values-ar`, `values-es`, و`values-zh`، مع مطابقة placeholders.
10. أي اختبار لم يُشغّل فعلياً يجب وصفه بأنه `NOT RUN` مع السبب، وليس `PASS`.
11. لا تنفذ commit أو push أو merge إلا بطلب واضح. عند طلب الدفع، استخدم commit واحداً واضحاً لكل مجموعة مترابطة، وتحقق من الشجرة بعد الدفع.
12. لا تنفذ OAuth حقيقياً أو تستخدم provider credentials أو تنشر خارجياً دون مسار تفويض واضح ومالك وقرار مخاطر.

---

## 3. الحالة المثبتة وما تم إنجازه

### 3.1 تسلسل commits المرجعي

الـ commits الأخيرة التي يجب فهمها قبل مواصلة العمل:

| Commit | النطاق |
|---|---|
| `2f936ce4` | إغلاق مراحل إعادة تصميم سجل الوكيل الحي الأساسية |
| `bdc6152a` | مواءمة instrumentation مع حالة الواجهة المطوية |
| `4b352e72` | إصلاح selector مكرر لمجموعات المهام |
| `2bc48b08` | اختيار مجموعة المهام الظاهرة بعد LazyColumn scroll |
| `768db6d3` | انتظار استقرار locale في المحاكي |
| `178903e5` | استخدام صورة Google APIs مستقرة للمحاكي |
| `f9b86dd2` | تحسين حجم محاكي locale instrumentation |
| `620c7c9f` | تثبيت محاكي API 29 English |
| `67347396` | تتبع crash لمحاكي API 34 Arabic |
| `77917393` | تشغيل instrumentation عبر dispatcher واحد |
| `f32f6d96` | تحسين عرض code والردود وartifacts |
| `c1a425e9` | حفظ completion reasons وartifacts القابلة لإعادة الاستخدام |
| `dbdf19a8` | metadata، continuation، output budget، والفصل بين code المكتمل وغير المكتمل |

### 3.2 ما تم إنجازه في مسار Live Trace

تم إنجاز أو إدخال الأساس البرمجي للمراحل A–E القديمة الخاصة بـ Live Trace، مع بقاء إثبات Android/runtime النهائي مطلوباً:

- تثبيت هويات التنفيذ والإجراءات في مسارات trace الأساسية.
- إضافة وتحسين إسقاط الحالات والمجموعات وعرض lifecycle.
- إضافة عدادات وعمليات الذاكرة ضمن نموذج trace حيث كان ذلك مفقوداً.
- تحسين الترجمة وresource parity للغات العربية والإنجليزية والإسبانية والصينية.
- فصل `WAITING`, `RUNNING`, `COMPLETED`, `FAILED`, و`CANCELLED` في العقد أو العرض حيث تسمح البنية الحالية.
- إضافة وتحسين `stableActionId` ومسارات deduplication.
- ربط مجموعات التنفيذ بواجهة `AgentLifecycleTrackerPanel` واختبارات Compose الأساسية.
- إضافة اختبارات RTL وfont scale وsemantics في مسار instrumentation.
- إضافة stress fixtures كبيرة واختبارات out-of-order/concurrent trace على مستوى JVM/Compose.
- إصلاح عدد من مشاكل CI الخاصة بالـ emulator والـ locale والـ ADB.

**تنبيه:** هذه البنود تعتبر منجزة على مستوى المصدر/الفحوصات الموجودة، لكنها لا تُغلق مرحلة GA وحدها. يلزم تشغيل CI على commit الإصدار ومراجعة artifacts ونتائج Android الفعلية.

### 3.3 ما تم إنجازه في مسار الردود والتنفيذ

في `dbdf19a8` تم تنفيذ ما يلي:

#### Completion metadata

تم تمرير metadata عبر المسار:

```text
Cloud Adapter
  → CloudBackend / LocalLlamaBackend
  → HybridOrchestrator
  → AgentLoop.LoopResult
  → ChatViewModel
  → GenerationResponsePolicy
```

وتتضمن metadata سبب انتهاء المزود، مصدر التنفيذ، والسبب الخام المنقّى من المحتوى الحساس.

تمت إضافة/تطبيع أسباب مثل:

- `LENGTH_LIMIT`
- `CONTENT_FILTER`
- `CONNECTION_LOST`
- `DISCONNECTED`
- `MESSAGE_STOP`
- `LOCAL_NATIVE_COMPLETE`

وأصبحت `GenerationResponsePolicy` تميز الرد غير المكتمل عن النجاح العام.

#### Continuation

تم إنشاء `ResponseContinuationPolicy` التي:

- تسمح بالمتابعة فقط عندما يكون الرد `INCOMPLETE` وغير فارغ.
- تحتفظ بحد أقصى من نهاية الرد السابق.
- تمنع تكرار النص السابق في continuation.
- تبني prompt واضحاً لاستكمال الرد دون إعادة الجزء السابق.
- لا ترسل الطلب تلقائياً.
- تملأ composer فقط بعد ضغط المستخدم على زر المتابعة.

#### Output budget

تم إنشاء `OutputBudgetPolicy` كمصدر موحد لميزانية الإخراج:

- brief: `384`
- standard: `1024`
- analytical detailed: `4096`
- creative detailed: `3072`
- detailed general: `2560`

النموذج المحلي يخضع للـ `nCtx` الفعلي مع reserve آمن، بينما السحابي يحتفظ بالميزانية المطلوبة حسب نوع الطلب.

#### فصل artifacts عن الردود الجزئية

- لا تعرض fenced code غير المغلق كـ artifact مكتمل.
- لا تظهر له أفعال النسخ/التوسيع/المشاركة/الحفظ قبل إغلاق fence.
- يعرض مؤقتاً كنص monospace أثناء البث.
- تمت إضافة `isComplete` إلى `AssistantCodeBlock`.

#### الموارد والاختبارات

تمت إضافة موارد زر continuation إلى جميع اللغات المدققة، وإضافة اختبارات لـ:

- output budget.
- continuation eligibility.
- bounded prompt.
- overlap removal.
- incomplete code fence.
- callback contract.

### 3.4 بوابات التحقق المنجزة

آخر تحقق ساكن موثق:

```text
verify_core_changes.py
summary: 96/96 checks passed
```

كما نجح:

```text
git diff --check
```

أما Gradle المحلي فلم يبدأ لأن بيئة Sandbox لا تحتوي Android SDK:

```text
SDK location not found
Define a valid SDK location with ANDROID_HOME
```

لذلك يجب عدم وصف اختبارات Kotlin المحلية بأنها ناجحة قبل تشغيلها في بيئة Android صحيحة أو GitHub Actions.

---

## 4. الفجوات المتبقية ذات الأولوية

### P0 — إثبات البناء وAndroid runtime

- تشغيل unit tests وinstrumentation على GitHub Actions للـ commit الحالي.
- تشغيل API matrix باللغتين `en-US` و`ar-SA`.
- مراجعة نتائج process death وrotation وfont scale وoffline.
- التحقق من عدم وجود crash أو ANR أو failure ناتج عن emulator.
- جمع APK/AAB، test reports، logcat، uiautomator، accessibility evidence، hashes.

### P0 — Google OAuth والتكاملات

يجب تنفيذ تدقيق تشخيصي مستقل قبل أي إصلاح:

1. تتبع زر Google في شاشة Integrations.
2. تتبع `IntegrationsViewModel`.
3. تتبع `ConnectorAuthorizationManager` و`ConnectorAuthManager`.
4. فحص `OAuthConfiguration` و`OAuthStateRegistry` وPKCE/state/redirect URI.
5. فحص `MainActivity` وManifest وdeep link callback.
6. فحص BuildConfig وclient ID وSHA/package identity دون كشف secret.
7. فصل الأسباب المؤكدة عن الاحتمالات:
   - redirect URI mismatch.
   - state ضائع أو منتهي.
   - client ID غير مهيأ.
   - callback لا يصل للنشاط.
   - token exchange يفشل.
   - credential تخزن في namespace خاطئ.
   - UI تعرض رسالة عامة تخفي سبباً قابلاً للإصلاح.
8. بعد Google، تابع Gmail وDrive وبقية الموصلات، ثم skills والأدوات وToolDispatcher.

لا يُعتبر Google OAuth مغلقاً بمجرد أن تظهر شاشة المتصفح؛ يلزم إثبات callback، token exchange، storage، readiness، tool availability، وفشل آمن.

### P1 — دورة الأداة الكاملة

يجب توثيق واختبار المسار التالي لكل فئة أداة:

```text
User request
 → intent/capability detection
 → effective tool selection
 → model request
 → tool-call parser
 → strict argument validation
 → permission/side-effect policy
 → ToolDispatcher
 → ConnectorRuntime/Skill runtime
 → result normalization
 → conversation history
 → next model turn
 → final answer
 → trace/UI projection
```

يجب التحقق من:

- منع tool غير موجود في `effectiveTools`.
- منع connector غير authorized.
- منع argument غير صالح قبل dispatch.
- عدم تحويل failure إلى synthetic success.
- عدم تسريب raw provider error أو secret أو prompt.
- إعادة نتيجة الأداة إلى النموذج بنفس الهوية والسياق الصحيح.
- عدم تكرار dispatch عند retry أو duplicate event.
- وجود `executionId`, `sessionId`, `actionId`, `toolName`, `connectorId` حيث يلزم.

### P1 — جودة واجهة الدردشة الحالية

هذه ليست إعادة تصميم. يجب تحسين السلوك الحالي فقط:

- الحفاظ على layout وtheme وhierarchy الحالية.
- تحسين وضوح حالة التوليد دون زيادة الضوضاء البصرية.
- إبقاء composer مثبتاً ومتاحاً وعدم دفعه خارج الشاشة بسبب trace أو error bubble.
- جعل زر الإلغاء واضحاً أثناء التوليد، مع منع النقر المكرر.
- عرض حالة `waiting/running/complete/failed/cancelled` بنص وأيقونة وsemantics متسقة.
- عرض continuation فقط عندما تكون قابلة للتنفيذ، وعدم إظهارها بعد نجاح الرد.
- حفظ draft عند تبديل session وعدم تسريب draft بين الجلسات.
- جعل رسائل الخطأ قابلة للفهم مع إجراء مناسب، دون عرض stack trace.
- دعم RTL/LTR وfont scale 1.3 و2.0 دون قص أو تداخل.
- تحسين scroll anchoring: أثناء البث يتبع آخر المحتوى فقط إذا كان المستخدم قريباً من الأسفل، ولا يخطف التمرير إذا كان يراجع رسالة قديمة.
- عدم فتح trace تلقائياً بعد اكتمال التنفيذ إلا إذا كان المستخدم يتابعه بالفعل.
- منع artifact actions قبل اكتمال code fence أو اكتمال provenance.
- إظهار وقت/حالة/مصدر التنفيذ عند الحاجة بطريقة مختصرة، مع تفاصيل موسعة اختيارية.
- تحسين semantics وcontent descriptions للمكونات الحالية دون تغيير شكلها الأساسي.
- اختبار dark theme، offline banner، empty state، streaming، error state، attachments، voice state، وsession switching.

### P1 — Resource parity

Oracle الحالي يمر، لكن يجب الحفاظ على ذلك في كل commit. أي string جديد يجب أن يضاف إلى:

- `values/strings.xml`
- `values-ar/strings.xml`
- `values-es/strings.xml`
- `values-zh/strings.xml`

ويجب فحص placeholders والتنسيق وعدم إضافة نص خام داخل Kotlin/Compose.

### P2 — الأداء والضغط

- قياس projection وrecomposition عند 1,000 و5,000 حدث.
- التحقق من bounded buffer والاحتفاظ بالأخطاء والـterminal events.
- منع إعادة composition غير الضرورية عبر stable keys وderived state.
- اختبار event arrival خارج الترتيب وstart/terminal races.
- اختبار تنفيذين متزامنين وعدم اندماجهما.
- مراقبة الذاكرة وزمن فتح/إغلاق مجموعات trace.

---

## 5. المراحل التنفيذية الجديدة

## المرحلة 0 — استلام الحساب الجديد وتثبيت الحالة

### الهدف
تثبيت نقطة العمل قبل أي تعديل.

### التنفيذ

```bash
git fetch origin main --prune
git checkout -B main origin/main
git status --short --branch
git log --oneline --decorate -20
python3 tools/verify_core_changes.py
```

تحقق من أن HEAD هو `dbdf19a8` أو commit أحدث موثق، وأن الشجرة نظيفة قبل بدء العمل.

### بوابة الخروج

- branch مطابق للـ remote.
- Oracle يمر أو يتم توثيق الفرق الجديد.
- لا توجد تعديلات غير معروفة.

---

## المرحلة 1 — تدقيق Google OAuth والتكاملات، تشخيص فقط

### الهدف
إنتاج تقرير سببي كامل يبدأ من فشل Google ولا يتوقف عند أول عطل.

### الملفات والمسارات

- `GoogleConnector.kt`
- `ConnectorAuthManager.kt`
- `ConnectorAuthorizationManager.kt`
- `OAuthConfiguration.kt`
- `OAuthStateRegistry.kt`
- `IntegrationsViewModel.kt`
- `ConnectorBootstrap.kt`
- `ConnectorRuntimeManager.kt`
- `ConnectorRegistry.kt`
- `OfficialConnectorCatalog.kt`
- `GmailAssistantSkill.kt`
- `DriveSearchSkill.kt`
- `ConnectorSkillRuntime.kt`
- `RuntimeToolCatalog.kt`
- `ToolDispatcher.kt`
- `MainActivity.kt`
- `AndroidManifest.xml`

### القيود

- لا تعدل المصدر أثناء مرحلة التشخيص.
- لا تستخدم credentials الحقيقية في التقرير.
- لا تخلط بين فشل Google وفشل Live Trace.
- لكل finding اكتب: evidence، السبب المؤكد، الاحتمال، impact، reproduction، الأولوية، الإصلاح المقترح.

### بوابة الخروج

تقرير Google/Gmail/Drive/connector/skills/tools مع خريطة تنفيذ فعلية ومصفوفة failures، دون تعديلات مصدرية.

---

## المرحلة 2 — إغلاق عقود الهوية والحالة في Live Trace

### الهدف
ضمان أن كل lifecycle row قابل للربط بتنفيذ وإجراء واحد.

### التنفيذ

- تثبيت `executionId`, `sessionId`, `actionId`, `connectorId` حسب نوع الحدث.
- توحيد status mapping وعدم تحويل cancellation إلى pending.
- معالجة terminal-before-start كـrecovered event مع warning داخلي.
- إزالة dedupe المبني على title وحده.
- تثبيت ownership عند projector والـbuffer والـUI.

### الاختبارات

- start → running → complete.
- start → failed.
- start → cancelled.
- waiting → running → complete.
- terminal-before-start.
- تنفيذان متوازيان بنفس اسم الأداة.

### بوابة الخروج

لا يوجد lifecycle event مرئي بلا هوية ثابتة أو تفسير موثق.

---

## المرحلة 3 — localization وresource contract

### الهدف
إزالة raw dynamic labels وضمان ترجمة كل سطح trace والدردشة.

### التنفيذ

- إنشاء/تثبيت catalog للـmachine tags.
- تمرير locale التطبيق إلى presentation layer.
- ربط labels بالموارد لا بـhard-coded map داخل projector.
- استخدام fallback آمن ومترجم للفئات غير المعروفة.
- فحص placeholders والـpluralization.

### بوابة الخروج

```text
values-ar: Missing keys: 0
values-es: Missing keys: 0
values-zh: Missing keys: 0
```

مع فحص عدم ظهور machine tag أو raw error للمستخدم.

---

## المرحلة 4 — تحسين Live Trace داخل شاشة الدردشة

### الهدف
رفع الجمالية والدقة والراحة دون إعادة إنشاء الشاشة.

### مبادئ UX

- incremental change فقط.
- الحفاظ على الهوية البصرية الحالية.
- collapsed by default بعد اكتمال التنفيذ.
- auto-follow أثناء البث فقط عندما يكون المستخدم في نهاية القائمة.
- composer لا يغادر الشاشة.
- التفاصيل عند الطلب، لا كضوضاء دائمة.
- status icon مستقل عن category icon.
- ألوان theme متوافقة مع accessibility.
- لا توجد صفوف مكررة بين group view وtask tree.

### تحسينات مقترحة

1. تحسين summary row ليعرض الأدوات والملفات والبحث والذاكرة بعدد صحيح.
2. تحسين group headers مع state واضح وcontent description.
3. تمييز waiting/running/completed/failed/cancelled بصرياً ودلالياً.
4. عرض elapsed time عند توفره دون جعل الواجهة مزدحمة.
5. عرض provider/model origin في التفاصيل الآمنة فقط.
6. تثبيت ارتفاع panel مع LazyColumn داخلي.
7. الحفاظ على scroll position عند streaming updates.
8. إضافة retry/continuation إلى error bubble فقط عندما يسمح contract بذلك.
9. منع artifact actions للردود غير المكتملة.
10. تحسين empty/loading/error states دون تغيير layout الرئيسي.
11. دعم long text وRTL وfont scale دون overflow.
12. إضافة test tags مستقرة بدلاً من selectors هشة أو عناوين متكررة.

### بوابة الخروج

مراجعة Compose يدوية، semantic tests، وعدم وجود regressions في composer أو scroll أو session switching.

---

## المرحلة 5 — ربط دورة الأداة والمهارة والموصل

### الهدف
إثبات أن نتيجة الأداة تعود للنموذج وتظهر للمستخدم بشكل صحيح.

### التنفيذ

- توثيق effective tool catalog.
- التحقق من ToolDispatcher وstrict validation.
- ربط connector identity بالskill identity.
- فحص authorization/readiness لكل connector.
- اختبار success/failure/timeout/cancel.
- اختبار retry وعدم تكرار side effects.
- اختبار memory read/write وGmail وDrive وNotion وGitHub حسب availability.
- منع side effects غير الموافق عليها.

### بوابة الخروج

كل tool call يملك evidence من parser إلى dispatcher إلى normalized result إلى model context إلى final UI.

---

## المرحلة 6 — اختبارات Android وCompose وRTL

### الهدف
تحويل الاختبارات المنطقية إلى evidence على Android.

### التنفيذ

- تشغيل unit tests.
- تشغيل `AgentLifecycleTrackerPanelTest`.
- تشغيل API matrix باللغتين العربية والإنجليزية.
- اختبار groups، expand/collapse، parent/child، statuses، RTL، font scale، accessibility.
- حفظ screenshots/golden diffs عند الفشل.
- استخراج logcat وuiautomator وtest reports.

### بوابة الخروج

كل الوظائف المطلوبة ناجحة على CI، والفشل المتبقي مصنف بوضوح وليس “unknown”.

---

## المرحلة 7 — الضغط والاستقرار

### الهدف
منع crash/ANR/duplication تحت الحمل.

### التنفيذ

- 5,000 حدث موزعة على 5–10 مجموعات.
- out-of-order events.
- concurrent executions.
- start/terminal races.
- expansion state أثناء updates.
- bounded memory.
- قياس زمن projection/recomposition.
- الاحتفاظ بالterminal failures والأخطاء حتى عند pruning.

### بوابة الخروج

لا crash، لا ANR، لا duplication، ولا فقدان terminal evidence ضمن الحمل المحدد.

---

## المرحلة 8 — CI/release proof وقرار GA

### التنفيذ

1. تشغيل `verify_core_changes.py`.
2. تشغيل localization health strict.
3. تشغيل unit tests.
4. تشغيل debug/release build.
5. تشغيل instrumentation matrix.
6. مراجعة artifacts والتقارير.
7. اختبار offline/process death/rotation/font scale.
8. طلب ARM64/device evidence أو توثيق accepted risk.
9. تحديث audit matrix لكل finding.
10. كتابة قرار صريح:
   - `GA READY`
   - `CONDITIONAL / RELEASE CANDIDATE`
   - `NO-GA`

### لا يجوز إعلان GA إذا

- فشل Android CI.
- بقي resource parity غير صفري.
- بقي stable identity أو dedupe غير مثبت.
- لا يوجد دليل device/runtime أو accepted-risk رسمي.
- بقيت P1 غير مملوكة.
- فشل Google OAuth أو connector readiness دون تصنيف وقرار إطلاق.

---

## 6. المهارات التي يجب على الحساب الجديد قراءتها واستخدامها

### إلزامية

- `deep-research` عند الحاجة لأي بحث خارجي أو مصادر متعددة.
- `data-analysis` عند تحليل نتائج CI أو stress metrics أو جداول findings.
- `workflow-composer` فقط عند تقسيم العمل إلى 5 عناصر مستقلة أو أكثر أو عند طلب fan-out/parallel research.
- `webdev-worklocally` فقط إذا كان العمل مرتبطاً بمشروع WebDev/local managed workflow؛ لا يستخدم لتعديل Android العادي بلا حاجة.
- `git-attribution` قبل أي commit coding عندما تنطبق سياسة الجلسة.
- `office-documents` أو `office` عند إنشاء/تحرير DOCX/XLSX/PDF، وليس مطلوباً لخطة Markdown الحالية.
- `image-processing` أو `read-special-images` عند مراجعة screenshots أو صور CI أو لقطات الواجهة غير المقروءة.
- `persistent-computing` إذا احتاجت الاختبارات إلى emulator/service/VM دائم أو Docker أو جهاز مستمر.
- `session-self-config` عند الحاجة إلى connector خارجي غير مفعّل.
- `manus-config` عند إدارة integrations أو scheduled tasks أو project configuration.
- `github-integration` عند استخدام تكامل GitHub الرسمي، مع احترام scope والاعتمادات القائمة.

### حسب المهمة

- `manus-api` لإدارة Manus عبر API، وليس لتشغيل GitHub repository بدلاً من GitHub CLI.
- `triggers` أو `automation-and-scheduling` فقط للمهام المستقبلية أو المراقبة الشرطية؛ لا تستخدم automation لمجرد أن CI يعمل حالياً.
- `finance-pro-playbooks` غير مرتبط بهذه المهمة ولا يستخدم.
- `slides` غير مطلوب؛ لا تنشئ عرضاً لهذه الخطة.
- `imagegen` غير مطلوب إلا إذا طلب المستخدم visual mockup جديداً؛ مراجعة الصور الحالية تستخدم image-processing/read-special-images.

---

## 7. الأدوات والأوامر المعتمدة

### Git/GitHub

```bash
git fetch origin main --prune
git status --short --branch
git log --oneline --decorate -20
git diff --check
gh run list --repo AbdulLatef2380/AIRI-Project --branch main
gh run view <run-id> --repo AbdulLatef2380/AIRI-Project --json jobs
```

استخدم `gh api` لسجلات jobs عند الحاجة، ولا تعيد تشغيل CI الفاشل قبل فهم السبب. إذا كان الفشل transient emulator بوضوح، يمكن rerun للوظيفة الفاشلة فقط مع توثيق السبب.

### Oracle والفحوصات

```bash
python3 tools/verify_core_changes.py
python3 tools/final_runtime_integration_audit.py
python3 -m py_compile tools/*.py
git diff --check
```

### Gradle

```bash
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
./gradlew --no-daemon --max-workers=1 :app:assembleDebug
./gradlew --no-daemon --max-workers=1 :app:assembleRelease
```

إذا لم يوجد Android SDK، لا تنشئ `local.properties` عشوائياً. انقل التحقق إلى CI أو بيئة Android مجهزة وسجل `NOT RUN` محلياً.

### Android/CI

استخدم workflows الحالية وscripts الموجودة في:

- `.github/workflows/android_build.yml`
- `.github/workflows/android_api34_en_us_adb_trace.yml`
- `.github/scripts/run-instrumentation.sh`
- `.github/scripts/set-emulator-locale.sh`

لا تعدل صورة emulator أو locale أو timeouts دون evidence من السجل.

### البحث النصي

استخدم `rg` لتتبع:

- `executeStream`
- `onComplete`
- `CompletionMetadata`
- `ToolDispatcher`
- `RuntimeToolCatalog`
- `OAuthStateRegistry`
- `connectorId`
- `executionId`
- `stableActionId`
- `continuationPrompt`
- `memoryOperations`

---

## 8. شكل التقرير المطلوب لكل مرحلة

لكل مرحلة يجب تسليم قسم بهذه الصيغة:

1. **Scope:** ما الذي فُحص أو عُدّل.
2. **Evidence:** مسارات الملفات، أسماء الدوال، أرقام الأسطر، commits، ونتائج الأوامر.
3. **Confirmed findings:** أعطال مثبتة بدليل.
4. **Hypotheses:** احتمالات تحتاج اختباراً.
5. **Changes:** الملفات المعدلة ولماذا.
6. **Tests run:** الأمر، النتيجة، والبيئة.
7. **Tests not run:** السبب الدقيق.
8. **Risk:** ما تبقى من مخاطر.
9. **Exit gate:** هل اجتازت المرحلة أم لا.
10. **Next step:** خطوة واحدة واضحة فقط.

لا تستخدم عبارات مثل “تم الإصلاح” إذا تم تعديل المصدر فقط دون اختبار مناسب، واستخدم بدلاً منها “تم تنفيذ التعديل، والتحقق الساكن ناجح، والاختبار Android ما زال مطلوباً”.

---

## 9. مصفوفة القبول النهائية

| الطبقة | الدليل المطلوب | شرط القبول |
|---|---|---|
| Git | HEAD وremote | متزامنان ولا تعديلات مجهولة |
| Oracle | `verify_core_changes.py` | 96/96 أو نتيجة أحدث موثقة |
| Localization | ar/en/es/zh | parity وplaceholders = صفر |
| AgentLoop | unit tests + trace | metadata والنتائج صحيحة |
| Tools | dispatcher contract | لا dispatch غير مصرح ولا synthetic success |
| OAuth | callback/token/readiness | Google وبقية الموصلات مصنفة ومثبتة |
| Chat UI | Compose/RTL/accessibility | لا قص أو تداخل أو regression |
| Continuation | policy + manual review | لا إرسال تلقائي ولا تكرار |
| Artifacts | complete fence/provenance | لا actions للردود الجزئية |
| Stress | 5,000 events | لا crash/ANR/duplication |
| CI | Android matrix | build/tests/instrumentation خضراء |
| Runtime | ARM64/process death/offline | evidence أو accepted-risk رسمي |
| Release | APK/AAB/mapping/hash | artifacts كاملة وقابلة للمراجعة |

---

## 10. الترتيب العملي المقترح للحساب الجديد

### أول جلسة

1. قراءة هذه الوثيقة.
2. مزامنة `main`.
3. تشغيل Oracle.
4. قراءة `AIRI_LIVE_TRACE_GA_CLOSURE_PLAN.md`.
5. مراجعة `dbdf19a8` و`c1a425e9`.
6. عدم تعديل الكود قبل تثبيت الفروقات.

### المهمة الأولى بعد الاستلام

تنفيذ **المرحلة 1: Google OAuth والتكاملات، تشخيص فقط**، لأن ذلك كان الطلب الأصلي الأوسع ولم يُغلق بمجرد إكمال Live Trace أو response policies.

### المهمة الثانية

تشغيل CI الحالي وتحليل أي فشل على `dbdf19a8`، ثم تنفيذ أقل إصلاح سببي مطلوب، مع عدم خلط إصلاحات Google أو chat UI بفشل emulator.

### المهمة الثالثة

إكمال أي فجوة P0/P1 في Live Trace أو tool lifecycle، ثم تحسين Chat UI تدريجياً وفق المرحلة 4 واختبار كل تغيير.

### المهمة الرابعة

تشغيل Android matrix وstress وruntime proof، ثم تحديث قرار GA.

---

## 11. قرار الحالة عند كتابة هذه الوثيقة

الحالة الصحيحة ليست `GA READY` بعد.

الحالة الحالية:

```text
SOURCE / STATIC CONTRACTS: READY FOR CONTINUATION
ORACLE: PASS (96/96)
LOCAL GRADLE: NOT RUN — Android SDK unavailable in Sandbox
ANDROID INSTRUMENTATION: REQUIRES CI CONFIRMATION ON CURRENT HEAD
GOOGLE OAUTH AUDIT: REQUIRES DEDICATED DIAGNOSTIC PASS
RUNTIME ARM64 / PROCESS DEATH / OFFLINE: EXTERNAL EVIDENCE PENDING
RELEASE DECISION: NO-GA UNTIL THE ABOVE GATES CLOSE
```

لا تغيّر هذا القرار إلى `GA READY` إلا بعد إغلاق بوابات Android، التكاملات، الأداة، الأمان، والـruntime حسب هذه الخطة.
