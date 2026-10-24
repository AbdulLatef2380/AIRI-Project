# تنفيذ المرحلة الثانية من إعادة تصميم Live Agent Log

## النطاق المنفذ

تم تنفيذ الدفعة الثانية المصغرة الخاصة بتوحيد السطح المدمج وتقليل التكرار البصري. لم يتم تنفيذ Bottom Sheet أو إعادة تصميم التفاصيل العميقة أو ربط الإيقاف بالـruntime؛ تلك مراحل لاحقة حسب الخطة المعدلة.

## التغييرات

### `AgentLifecycleTrackerPanel.kt`

تم تعديل السلوك البصري بحيث:

- يبدأ الملخص مطويًا افتراضيًا بدل فتح كل التفاصيل.
- لا تُفتح المجموعات تلقائيًا أثناء البث.
- تبقى تفاصيل التنفيذ خلف إجراء صريح من المستخدم.
- تُجمع إحصاءات الفئات في نص ملخص واحد بحد أقصى سطرين مع ellipsis.
- أضيف `testTag("agent-trace-summary")` إلى منطقة الملخص.
- بقيت مفاتيح `group-*` و`event-*` مستقرة.
- لم تتغير حالة runtime أو مصدر الأحداث أو سياسة التنقيح.

### `ChatScreen.kt`

تمت إضافة قاعدة سطح واحد للتنفيذ الحالي:

- إذا كان للسجل `executionId` وأحداث مرتبطة بجلسة المحادثة الحالية، فالسجل المدمج داخل دور المساعد هو السطح الأساسي.
- لا يعرض `AgentPlanOverlay` نسخة ثانية من التنفيذ فوق صندوق الإدخال.
- يبقى `AgentPlanOverlay` fallback فقط عندما لا يتوفر السجل المدمج.
- لم يتم اعتبار إخفاء overlay إلغاءً للتنفيذ.

### اختبارات Compose

تم تحديث `AgentLifecycleTrackerPanelTest.kt` ليغطي:

- بقاء الملخص ظاهرًا مع عدم ظهور قائمة التفاصيل تلقائيًا.
- فتح قائمة التفاصيل عند ضغط الملخص.
- استمرار اختبار المجموعات والحالات وRTL وحجم الخط والسجل الكبير.
- الحفاظ على اختبار تحديث مجموعة مفتوحة أثناء وصول حدث جديد.

## ما لم يُنفذ

- لا يوجد Bottom Sheet بعد.
- لا توجد شاشة تفاصيل مستقلة بعد.
- لم تُضف `CANCELLING`.
- لم يتغير زر Stop أو runtime cancellation.
- لم تُضف animations جديدة.
- لم تُضف Baseline Profile.
- لم تُحذف مكونات قديمة أو APIs عامة.

## التحقق

### فحص diff

```text
git diff --check: ناجح
```

آثار `.gradle` التي أنشأها تشغيل Gradle أزيلت من حالة العمل، ولم تبقَ ضمن التغييرات.

### اختبار Android المستهدف

تم تشغيل:

```text
./gradlew :app:connectedDebugAndroidTest \\
  --tests 'com.airi.assistant.ui.activity.AgentLifecycleTrackerPanelTest'
```

النتيجة الفعلية:

```text
BUILD FAILED
SDK location not found
ANDROID_HOME غير معرف
ANDROID_SDK_ROOT غير معرف
local.properties لا يحتوي sdk.dir صالحًا
```

لذلك لم يبدأ اختبار Compose على جهاز أو emulator، ولا يمكن وصفه بأنه ناجح. يحتاج التحقق إلى GitHub Actions أو بيئة تحتوي Android SDK وemulator صالحًا.

## حالة الملفات

التغييرات المتتبعة:

- `app/src/main/java/com/airi/assistant/ui/activity/AgentLifecycleTrackerPanel.kt`
- `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt`
- `app/src/androidTest/java/com/airi/assistant/ui/activity/AgentLifecycleTrackerPanelTest.kt`
- `app/src/test/java/com/airi/assistant/ui/trace/UnifiedExecutionTraceProjectorTest.kt` من المرحلة الأولى

الوثائق المحلية:

- `docs/design/LIVE_AGENT_LOG_REDESIGN_PLAN_2026-10-24_AR.md`
- `docs/design/LIVE_AGENT_LOG_PHASE1_DATA_CONTRACT_2026-10-24_AR.md`
- هذا التقرير.

لم يتم إنشاء commit أو push أو merge.

## التقييم

- **مؤكد:** الملخص مطوي افتراضيًا في الكود، والمجموعات لا تفتح تلقائيًا، وoverlay لا يعرض نسخة ثانية عندما توجد أحداث inline مرتبطة بنفس التنفيذ.
- **غير محسوم:** صحة البناء واختبارات Compose في غياب Android SDK.
- **مرحلة لاحقة:** فتح التفاصيل في سطح مستقل، وربط التحكم بالـruntime، ثم تشغيل Android CI واختبارات الجهاز.
