# خطة إغلاق استيراد النماذج المحلية في AIRI

> **للعاملين الوكيليين:** تُنفذ الخطوات بالتتابع مع إبقاء كل خطوة قابلة للاختبار.

**الهدف:** إغلاق دورة استيراد GGUF من اختيار الملف حتى `Import Success` و`Runtime Ready` مع إلغاء آمن، نشر ذري، أخطاء مفهومة، توصيات قابلة للتنفيذ، وواجهة AIRI متوافقة مع RTL والتوطين.

**المعمارية:** يبقى `ChatViewModel` و`ModelController` مصدر الحالة الحالي، وتُفصل نتيجة النسخ/التحقق عن نتيجة تحميل runtime. يحتفظ SAF بالـURI عبر `ACTION_OPEN_DOCUMENT`، وتُنسخ البيانات إلى مساحة التطبيق عبر ملف مؤقت ثم نشر ذري بعد اكتمال الحجم والتحقق؛ لا يُسجل النموذج ولا يظهر كجاهز قبل نجاح التحقق.

**التقنيات:** Kotlin، Coroutines، Android Storage Access Framework، Jetpack Compose Material 3، اختبارات JVM وCompose/Instrumentation.

**المواصفة:** `/home/ubuntu/upload/pasted_content.txt`.

## القيود العامة

- لا يُعرض `Unsupported` عندما تكون النتيجة `Unknown` أو `Not Verified`.
- لا يُعد نجاح النسخ نجاحاً للتشغيل؛ يجب عرض المرحلتين منفصلتين.
- لا يُكتب فوق ملف نموذج نشط ولا يُسجل ملف جزئي.
- كل النصوص الظاهرة للمستخدم تكون في موارد `values`, `values-ar`, `values-es`, `values-zh`.
- تدعم الواجهة RTL، والحركة الهادئة، وأهداف لمس واضحة، ورسائل خطأ قابلة للوصول.
- لا تُرسل تفاصيل المسار المحلي أو URI أو stack trace إلى تقرير المستخدم تلقائياً.

## نقاط المراجعة

- إلغاء النسخ أثناء منتصف الملف لا يترك `.part` ولا سجلاً وهمياً.
- URI بلا حجم معروف أو مع صلاحية مسحوبة يعيد حالة واضحة قابلة لإعادة المحاولة.
- اسم `model.gguf` ليس دليلاً على صحة GGUF؛ يجب فحص magic/version/counts/architecture.
- architecture غير المقروءة تُصنف `UNKNOWN`/`NOT_VERIFIED` لا `UNSUPPORTED` تلقائياً.
- فشل runtime بعد تسجيل الملف يحافظ على الملف المستورد كـ`Imported · needs retry` ولا يدّعي `Ready`.

---

### المهمة 1: عقد نتائج التحقق ورسائل المشكلة

**الملفات:**
- تعديل: `app/src/main/java/com/airi/assistant/ai/ModelValidator.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ModelController.kt`
- اختبار: `app/src/test/java/com/airi/assistant/ai/ModelValidatorTest.kt`

- [ ] إضافة نتائج typed منفصلة لـ`UnknownArchitecture`/`NotVerified` عن `UnsupportedArchitecture` مع إبقاء fail-closed قبل JNI.
- [ ] تغطية empty، invalid extension، fake GGUF، malformed counts، unsupported version، unreadable، unknown architecture في الاختبارات.
- [ ] تحويل كل نتيجة إلى `LoadErrorType` ورسالة + توصية قابلة للتنفيذ، دون نصوص ثابتة في Kotlin الإنتاجي.
- [ ] التحقق من أن تفاصيل تقنية التشخيص تبقى في السجل ولا تظهر كرسالة عامة.

### المهمة 2: استيراد SAF ذري وقابل للإلغاء

**الملفات:**
- تعديل: `app/src/main/java/com/airi/assistant/tools/FileUtils.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ModelLoadRequestPolicy.kt`
- اختبارات: `app/src/test/java/com/airi/assistant/tools/FileUtilsTest.kt` أو اختبار policy مستقل

- [ ] الاحتفاظ بصلاحية قراءة URI عند الإمكان، مع التعامل الصريح مع provider يرفضها.
- [ ] فحص الاسم والحجم والمساحة قبل النسخ، والنسخ إلى `.part` مع `ensureActive()`/استجابة للإلغاء.
- [ ] `flush` ثم rename ذري، وحذف `.part` في النجاح والفشل والإلغاء.
- [ ] منع التسجيل قبل اكتمال النسخ والتحقق، ومنع تكرار الاستيراد بالبصمة/الطول عند الإمكان.
- [ ] إضافة اختبار إلغاء وفشل حجم وفشل قراءة يثبت عدم وجود سجل أو ملف منشور جزئي.

### المهمة 3: فصل Import Success عن Runtime Ready

**الملفات:**
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ModelController.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/viewmodel/ModelUiState` أو موضع تعريف الحالة في `ChatViewModel.kt`
- اختبار: `app/src/test/java/com/airi/assistant/ui/viewmodel/ModelLoadRequestPolicyTest.kt`

- [ ] إضافة حالة import مستقلة: `IDLE`, `COPYING`, `VALIDATING`, `IMPORTED`, `FAILED`, `CANCELLED` مع progress/message/recommendation.
- [ ] إبقاء النموذج السابق جاهزاً عند فشل preflight أو runtime، وعدم مسح readiness بلا سبب.
- [ ] تسجيل النموذج بعد تحقق الملف، ثم جعل runtime نتيجة ثانية مستقلة قابلة لإعادة المحاولة.
- [ ] منع سباق اختيار نموذجين، وإهمال callback القديم مع اختبار regression.

### المهمة 4: واجهة AIRI الفاخرة والمتوطنة

**الملفات:**
- تعديل: `app/src/main/java/com/airi/assistant/ui/screens/ModelSettingsScreen.kt`
- تعديل: `app/src/main/java/com/airi/assistant/ui/screens/ModelLibraryScreen.kt`
- تعديل: `app/src/main/res/values/strings.xml`
- تعديل: `app/src/main/res/values-ar/strings.xml`
- تعديل: `app/src/main/res/values-es/strings.xml`
- تعديل: `app/src/main/res/values-zh/strings.xml`
- اختبار: `app/src/androidTest/.../ModelSettingsScreenTest.kt` أو اختبار Compose مناسب

- [ ] استبدال AlertDialog العام ببطاقة نتيجة متدرجة: الحالة، المرحلة، السبب البشري، التوصية، الإجراء الأساسي، وإظهار التفاصيل التقنية اختيارياً.
- [ ] عرض import progress منفصلاً عن runtime loading progress.
- [ ] إضافة `إعادة المحاولة` و`إلغاء` حيث يسمح العقد، و`الإبلاغ عن مشكلة` كخيار ثانوي يفتح نموذج AIRI الموجود مع context آمن.
- [ ] استخدام `AnimatedVisibility`/`AnimatedContent` بحركة قصيرة لا تقطع النص، مع احترام reduced motion حيث يدعمه النظام.
- [ ] إضافة content descriptions وsemantics وlive status، وتدقيق RTL/font scale/الأهداف اللمسية.

### المهمة 5: بوابات الإغلاق

- [ ] تشغيل اختبارات ModelValidator وFileUtils والسياسات.
- [ ] تشغيل فحص التوطين الصارم والتأكد من parity في اللغات الأربع.
- [ ] تشغيل compile/lint/unit tests وCompose/instrumentation المتاحة.
- [ ] مراجعة diff و`git diff --check` وعدم وجود نصوص خطأ أو أسرار في السجل.
- [ ] توثيق ما يظل runtime-pending إذا لم يتوفر جهاز ARM64 وGGUF مرخص، دون ادعاء اختبار native حقيقي.
