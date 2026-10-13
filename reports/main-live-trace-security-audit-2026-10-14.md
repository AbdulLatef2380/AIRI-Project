# تدقيق آخر نسخة من `main` — Live Trace والأمان

## النتيجة

تم التدقيق على commit:

`6b99f58f fix: make knowledge active-state contract explicit`

النسخة المحلية من `main` نظيفة، ونتائج GitHub Actions لهذا commit ناجحة:

- [AIRI Android CI](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/38472920523)
- [AIRI Deep Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/38472920447)
- [AIRI Architecture Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/38472920611)
- [AIRI Oracle](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/38472920490)

تعذر تشغيل Gradle محليًا لأن بيئة التدقيق لا تحتوي Android SDK. هذا عائق بيئي، وليس فشلًا مثبتًا في الكود. تم الاعتماد على نتائج CI الرسمية مع فحص ساكن للكود.

## النتائج المؤكدة

### 1. ميزة Live Trace Log المضمنة في المحادثة غير موجودة في `main`

المرفق يطلب نموذجًا جديدًا باسم `ChatItem`، ومكونات `LiveTraceNode` و`TraceGroup`، وتحديث `ChatScreen` ليعرض UserMessage ثم AgentTrace ثم AgentFinalResponse داخل نفس القائمة.

هذه الملفات غير موجودة في `main`:

- `agent/trace/TraceStep.kt`
- `agent/trace/TraceNodeState.kt`
- `data/ChatItem.kt`
- `ui/trace/LiveTraceNode.kt`
- `ui/trace/TraceGroup.kt`
- `ui/trace/TraceNodeDetail.kt`

والتنفيذ الحالي في `ChatScreen.kt` ما زال يعتمد على:

- `List<ChatMessage>`
- `messages.reversed()`
- `LazyColumn(reverseLayout = true)`
- `itemsIndexed(reversedMessages, ...)`
- صف عام `AiriThinkingRow` أثناء التوليد

كما أن `ChatViewModel` يمرر `onStepComplete` إلى `AgentLoop` ويعالج `ToolExecuted` و`FinalAnswer` للأخطاء والتأكيدات، لكنه لا ينشئ `ChatItem.AgentTrace` ولا يرسل عقدًا مرئية إلى قائمة المحادثة.

يوجد في المشروع تتبع تنفيذ داخلي منفصل، مثل `AgentTraceManager` و`ExecutionStatusBus` و`AgentPlanOverlay`. هذا ليس Live Trace Log المضمن في تسلسل المحادثة المطلوب في المرفق.

**التصنيف:** فجوة ميزة مؤكدة، وليست تخمينًا أو فشل CI.

### 2. تسريب نصوص المستخدم إلى Logcat في مسار Accessibility

في `app/src/main/java/com/airi/assistant/accessibility/executor/ActionExecutor.kt`:

- `clickByText()` يسجل قيمة `text` كاملة.
- `inputText()` يسجل قيمة `text` كاملة بعد إدخالها إلى الحقل.

هذا النص قد يكون كلمة مرور أو رمزًا أو بريدًا أو بيانات شخصية، خصوصًا لأن المسار ينفذ إدخال نص عبر Accessibility.

قواعد `app/proguard-rules.pro` الحالية لا تحتوي قاعدة R8 لحذف `Log.d`/`Log.i` من release. وبما أن `release` يستخدم R8 للتقليص فقط دون حذف هذه السجلات، فإن السجل قد يصل إلى Logcat في إصدار الإنتاج.

هذا يتوافق مباشرة مع توثيق Android الرسمي الذي يحذر من Log Info Disclosure ويوصي بعدم تسجيل البيانات الحساسة، وتنقية السجلات، وإزالة مستويات السجل التفصيلية من production باستخدام R8 [1].

**التصنيف:** مشكلة أمن وخصوصية مؤكدة. الإصلاح الصحيح هو عدم تسجيل النص نفسه، والاكتفاء بنوع العملية وطول النص أو معرف منقح، مع حذف سجلات `INFO` و`DEBUG` من release.

### 3. آخر تعديل في `main` لا يضيف سلوكًا فعليًا

الـ commit الأخير يغير:

```kotlin
_profiles.value.filter { it.active }
```

إلى:

```kotlin
_profiles.value.filter { profile -> profile.active }
```

هذا تغيير تسمية للمعامل فقط ولا يغير السلوك. لا يوجد خطأ مثبت فيه، لكنه لا يمثل تنفيذ Live Trace أو إصلاحًا وظيفيًا جديدًا.

## تقييم مواصفة المرفق نفسها

المرفق ليس دليلًا على أن التنفيذ تم. هو Prompt تنفيذ، وبعض أجزائه لا تصلح للدمج كما هي:

1. دالة `formatUiAction()` تحتوي سلسلة Kotlin غير صحيحة نحويًا في حالة `TYPE_TEXT`:

   ```kotlin
   "TYPE_TEXT" -> "Typed: "${result.take(50)}""
   ```

   يجب إصلاح escaping قبل محاولة البناء.

2. المواصفة تقترح `TraceStep.Thinking(val thought: String)`، كما تجعل الأداة غير المعروفة تتحول إلى `Thinking`. هذا قد يعرض سلسلة التفكير الداخلية للمستخدم. التنفيذ الحالي في `ChatViewModel` يستخدم `ReasoningStreamParser` ويعرض النص المرئي فقط، وهو أكثر أمانًا. لا ينبغي إضافة عرض raw reasoning؛ يجب عرض حالة مختصرة قابلة للتدقيق مثل “تشغيل أداة” دون نص التفكير الخاص.

3. إنشاء حالة `RUNNING` داخل `onStepComplete` لا يمثل بداية تشغيل الأداة؛ هذا الحدث يصل بعد انتهاء الأداة. وإضافة `delay(150)` تعطي تأثيرًا بصريًا فقط ولا تحقق مؤشرًا حيًا حقيقيًا. يلزم حدث بدء مستقل من `AgentLoop` أو `ExecutionStatusBus`.

4. أزرار `Retry` و`Skip` في المرفق تحتوي `TODO` ولا تملك callbacks تنفيذية. إذا أضيفت الواجهة دون ربطها بدورة التنفيذ فستظهر أزرار غير فعالة.

5. المقترح يحتفظ بـ`_messages` و`_chatItems` بالتوازي. هذا يخلق خطر اختلاف المصدرين، خصوصًا عند استعادة السجل التاريخي أو الإلغاء أو فشل التوليد. توثيق Compose يوصي بحالة UI مرفوعة ومصدر واضح للحقيقة، وباستخدام مفاتيح مستقرة لعناصر القوائم [2] [3].

6. استبدال قائمة الرسائل دون تحويل تاريخ Room سيؤدي إلى اختفاء الرسائل القديمة من الواجهة. مقطع migration المقترح في المرفق يقرأ `_messages.value` داخل `init`، لكنه قد يعمل قبل اكتمال تحميل الرسائل؛ لذلك يحتاج إلى نقطة اكتمال تحميل صريحة واختبارات استعادة.

7. الواجهة المقترحة تحتوي ألوانًا ثابتة مثل `Color.White` و`Color.DarkGray` ونصوصًا إنجليزية مباشرة مثل `Searching the web`, `Retry`, و`Skip`. هذا يخالف نظام Material 3 الدلالي ويعيد مشكلة الوضع النهاري والترجمة. Material 3 يوصي بأدوار اللون الدلالية التي تحافظ على علاقات التباين بين السطح والنص [4]، وAndroid يوصي بموارد string للتعريب بدل النصوص المضمنة [5].

## ما هو سليم حاليًا

- نتائج CI الأربعة للـ commit الحالي ناجحة.
- `AgentTraceManager` ينقح المدخل الأصلي، معاملات الخطوات، النتائج، والأخطاء عبر `PrivacyGuard`.
- يوجد حد أقصى للتتبع المخزن: 50 سجلًا و5 خطوات لكل سجل.
- التتبع الحالي لا يعرض raw chain-of-thought داخل `ChatScreen`.
- مسار `ChatViewModel` يتعامل مع تأكيدات المستخدم وأخطاء الأدوات بدل عرضها كنص خام.
- آخر تعديل في Knowledge لا يغير سلوكًا خطيرًا.

## القرار

لا يمكن اعتبار العمل مطابقًا للمرفق بالكامل، لأن **Live Trace Log داخل المحادثة غير منفذ**. وفي الوقت نفسه، لا توجد حاجة لنسخ مواصفة المرفق حرفيًا؛ فيها أخطاء تنفيذية ومخاطر خصوصية وتجربة مستخدم.

المشكلة الأمنية التي يجب إصلاحها قبل اعتبار `main` خاليًا من الثغرات هي إزالة النصوص الكاملة من سجلات `ActionExecutor` وتقليص سجلات INFO/DEBUG في release. أما تنفيذ Live Trace فيحتاج تصميمًا إضافيًا آمنًا يعتمد أحداث بدء/انتهاء حقيقية، حالة UI واحدة أو مزامنة مضمونة، عدم عرض التفكير الداخلي، مفاتيح مستقرة، موارد ترجمة، ورموز Material 3.

## المراجع الرسمية

[1]: https://developer.android.com/privacy-and-security/risks/log-info-disclosure "Android Developers — Log Info Disclosure"
[2]: https://developer.android.com/develop/ui/compose/lists "Android Developers — Lazy lists and stable item keys"
[3]: https://developer.android.com/topic/libraries/architecture/lifecycle "Android Developers — Lifecycle-aware Flow collection in Compose"
[4]: https://developer.android.com/design/style/color.html "Material Design — Color system and accessible semantic roles"
[5]: https://developer.android.com/guide/topics/resources/string-resource "Android Developers — String resources"
