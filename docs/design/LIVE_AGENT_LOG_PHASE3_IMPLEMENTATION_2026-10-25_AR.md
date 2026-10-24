# تنفيذ المرحلة الثالثة من إعادة تصميم Live Agent Log

## نطاق المرحلة

تم تنفيذ مرحلة التفاصيل والمجموعات واللغة دون الدخول في المرحلة الرابعة الخاصة بالإيقاف والاستعادة وإعادة المحاولة.

المستهدف كان:

- صفوف أحداث مختصرة وواضحة.
- فصل النص التفسيري عن الاسم التقني.
- تفاصيل معنونة ومنقحة ضمن حدود العرض.
- حالات انتظار ونجاح وفشل وإلغاء واضحة.
- دعم RTL وLTR.
- تحسين semantics والتوطين.

## التغييرات المنفذة

### `AgentLifecycleTrackerPanel.kt`

صفوف الأدوات والاتصالات أصبحت تعرض مستويين:

1. تسمية بشرية موطنة:
   - `Tool action` / `إجراء أداة`.
   - `Connection action` / `إجراء اتصال`.
2. الاسم التقني الفعلي بخط monospace مع اتجاه `LTR`، مثل:
   - `Technical name: skill_github_repository_read`.
   - `الاسم التقني: skill_github_repository_read`.

هذا يمنع خلط اسم الأداة الداخلي مع شرح النشاط، ويحافظ على قراءة معرفات الأدوات داخل واجهة RTL.

عند فتح الحدث، تعرض التفاصيل تحت عنوان موطن `Details` / `التفاصيل`، مع بقاء الحد الأقصى الحالي لأربعة أسطر وellipsis. لم تتم إضافة مسار بيانات جديد؛ التفاصيل تأتي من projection المنقح الحالي.

بقيت الحالات الحالية واضحة عبر `StatusIcon` و`statusLabel`:

- Waiting.
- In progress.
- Completed.
- Failed.
- Cancelled.

لم يتم تغيير نموذج الحالة أو runtime.

### موارد الترجمة

أضيفت المفاتيح إلى:

- `app/src/main/res/values/strings.xml`.
- `app/src/main/res/values-ar/strings.xml`.

المفاتيح هي:

- `agent_trace_event_tool_action`.
- `agent_trace_event_connection_action`.
- `agent_trace_event_technical_name`.
- `agent_trace_event_details`.

### اختبارات Compose

تمت إضافة اختبار يثبت المسار الكامل:

1. فتح الملخص.
2. فتح مجموعة الأدوات.
3. ظهور التسمية البشرية.
4. ظهور الاسم التقني منفصلًا.
5. فتح صف الحدث عبر semantics.
6. ظهور عنوان التفاصيل وتفصيل الحدث.

كما بقيت اختبارات RTL، حجم الخط، الحالات، السجلات الكبيرة، والتحديث أثناء فتح المجموعة.

## التحقق الفعلي

### الفحوص التي نجحت

- `git diff --check`: ناجح.
- تحليل XML لموارد اللغة الإنجليزية والعربية: ناجح.
- فحص النطاق: لا توجد تغييرات في Gradle أو runtime أو اعتماديات جديدة.
- آثار `.gradle` الناتجة من التشغيل أزيلت من شجرة العمل.

### الاختبار الذي تعذر تشغيله

تم تشغيل:

```text
./gradlew :app:connectedDebugAndroidTest \\
  --tests 'com.airi.assistant.ui.activity.AgentLifecycleTrackerPanelTest'
```

وتوقف Gradle أثناء تهيئة المشروع قبل تنفيذ الاختبارات بسبب:

```text
SDK location not found
ANDROID_HOME غير معرف
ANDROID_SDK_ROOT غير معرف
local.properties لا يحتوي sdk.dir صالحًا
```

لذلك لا توجد نتيجة نجاح أو فشل لاختبار Compose نفسه؛ الاختبار لم يبدأ. يلزم Android SDK وemulator أو GitHub Actions لإثبات البناء والتنفيذ.

## الملفات المعدلة

- `app/src/main/java/com/airi/assistant/ui/activity/AgentLifecycleTrackerPanel.kt`.
- `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt` من المرحلة الثانية الحالية.
- `app/src/androidTest/java/com/airi/assistant/ui/activity/AgentLifecycleTrackerPanelTest.kt`.
- `app/src/main/res/values/strings.xml`.
- `app/src/main/res/values-ar/strings.xml`.
- `app/src/test/java/com/airi/assistant/ui/trace/UnifiedExecutionTraceProjectorTest.kt` من المرحلة الأولى.

لم يتم إنشاء commit أو push أو merge.

## معايير القبول

| المعيار | الحالة | الدليل |
|---|---|---|
| فصل الاسم التقني عن النص البشري | اجتاز فحص المصدر، لم يثبت باختبار جهاز | صف الأداة وsemantics والاختبار المضاف |
| عرض تفاصيل معنونة ومحدودة | اجتاز فحص المصدر، لم يثبت باختبار جهاز | `Details` وحد أربعة أسطر |
| حالات التنفيذ واضحة | اجتاز فحص المصدر | `StatusIcon` و`statusLabel` القائمة |
| RTL/LTR للمعرفات | اجتاز فحص المصدر، لم يثبت باختبار جهاز | `TextDirection.Ltr` واختبار RTL الموجود |
| التوطين | اجتاز | XML صالح ومفاتيح عربية وإنجليزية |
| عدم تغيير runtime أو التنقيح | اجتاز فحص النطاق | لا ملفات runtime أو اعتماديات جديدة |
| تشغيل Android Compose tests | لم يُختبر | Android SDK غير متوفر |

## المخاطر المتبقية

1. **عائق بيئي مؤكد:** لا يمكن إثبات compilation أو Compose test في Sandbox الحالي.
2. **غير محسوم:** شكل الاسم التقني على أجهزة صغيرة أو عند أسماء أدوات طويلة جدًا؛ يحتاج اختبار جهاز حقيقي.
3. **خارج نطاق المرحلة:** زر الإيقاف لا يزال يحتاج ربطًا بمالك runtime، ولا يجوز اعتباره محسّنًا بمجرد إخفاء الواجهة.
4. **خارج نطاق المرحلة:** الاستعادة والتمييز بين trace حي وسجل سابق لم تُنفذ بعد.

## القرار

المرحلة الثالثة منفذة على مستوى الكود والموارد والاختبارات، لكن بوابة Android CI لم تُجتز بعد بسبب غياب SDK. لا ينبغي إعلان اكتمال التحقق النهائي أو الانتقال إلى المرحلة الرابعة قبل تشغيل CI أو بيئة Android كاملة.
