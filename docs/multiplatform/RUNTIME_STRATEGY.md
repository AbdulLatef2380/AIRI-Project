# استراتيجية تشغيل النماذج

## الوضع الحالي

تضم AIRI على Android مسار llama.cpp محلياً عبر JNI/NDK ومكتبة native. يثبت فاحص المنصة وجود JNI/C++ في `290` ملفاً وإعلان JNI/تحميل مكتبة في مسارات Android. لذلك لا يعاد استخدام `libairi_native.so` أو CMake الخاص بـAndroid كـruntime مشترك. يملك core سياسة اختيار النموذج وقدراته وحالاته، ولا يملك محمل مكتبة أو كوداً native.

## عقد runtime

```text
ModelRequest
  → ModelRoutingPolicy
  → ModelExecutionTarget
      → CloudModelProvider
      → PlatformModelRuntime
  → Stream<ModelEvent>
  → Cancellation / Recovery / Usage
```

| العنصر | مكانه | مسؤوليته | ممنوع عليه |
| --- | --- | --- | --- |
| `ModelCapabilities` و`ModelRequest` وrouting/fallback | `core` | قرار إن كان النموذج يوافق requirements وpolicy. | قراءة hardware أو تحميل library. |
| `ModelProvider` | `core/contracts` | API مشتركة للـstream/errors/cancellation. | حفظ API keys أو HTTP implementation محدد. |
| Cloud provider adapter | `data/providers` | REST/SSE وتحويل أخطاء provider. | Android context وsecrets في logs. |
| `PlatformModelRuntime` | `core/contracts` | عقد inference المحلي وتوفره وقدراته. | افتراض JNI أو filesystem. |
| Android runtime | `platform/android` | JNI/NDK وملفات النماذج وقيود thermal. | نشر API Android إلى core. |
| Desktop runtime | `platform/desktop` | build/link/load مخصص لـWindows/Linux وتحقق ABI. | استخدام `.so` Android أو افتراض OS واحد. |
| Web runtime | `platform/web` إن أثبتت الدراسة الإمكان | runtime browser-safe أو remote-only fallback. | إرسال model/private key للمتصفح أو ادعاء local inference بلا دليل. |

## ترتيب التنفيذ

| المرحلة | نطاق التنفيذ | الحالة الآن | معيار القبول |
| --- | --- | --- | --- |
| 1 | نقل models/capabilities/routing policies إلى core. | `ARCHITECTED` | `commonTest`، Android adapter يستهلك policy نفسها. |
| 2 | غلاف Android لـ`PlatformModelRuntime`. | `ARCHITECTED` | بناء Android وعدم تكرار routing؛ مسار JNI لا يتغير سلوكياً. |
| 3 | Desktop runtime لWindows وLinux كتنفيذين موثقين. | `PLANNED` | native build لكل OS، تحميل library، نموذج صغير، first token، cancel، cleanup. |
| 4 | دراسة Web local inference. | `PLANNED` | تقييم artifact size/memory/WebGPU/browser support وسياسة الخصوصية. |
| 5 | Web implementation أو remote-only declaration. | `BLOCKED` إلى انتهاء الدراسة | evidence من browser حقيقي؛ لا زر أو capability مضللة. |

## سياسة الاختيار والـfallback

يتخذ `core-routing` القرار بالترتيب: **قيود المهمة والخصوصية** → **متطلبات القدرات** → **تفضيل المستخدم** → **توفر platform runtime/provider** → **health/circuit-breaker**. لا يقرر core أن local runtime متاح اعتماداً على platform name؛ يطلب من adapter تقريراً قابلاً للفحص يتضمن readiness، memory budget، supported modalities، وسبب عدم التوفر غير الحساس.

| حالة runtime | سلوك AIRI | ما لا يحدث |
| --- | --- | --- |
| local runtime جاهز ومسموح | يختاره policy إذا وازن المتطلبات. | لا يحفظ المسار الخام للنموذج في logs. |
| local runtime غير متوفر | يعرض سبباً عملياً ويطبق fallback موافقاً للخصوصية أو يطلب اختيار المستخدم. | لا يسقط silently إلى cloud عندما تمنع policy network. |
| cloud key غير موجود | لا ينشئ request، ويوجه إلى إعداد آمن أو local model متوفر. | لا يضمّن key في config أو logs. |
| stream ألغي | يوقف المصدر النشط ويمنع events القديمة عبر generation/cancellation gate. | لا يستمر في إدراج chunks بعد الإلغاء. |
| provider يفشل | يصنف الخطأ، يحترم retry/fallback policy، ويحافظ على سبب قابل للتدقيق. | لا يعيد محاولات لا نهائية أو يكشف response حساساً. |

## Desktop runtime: شروط لا تقبل الاختصار

Windows وLinux مخرجان منفصلان، حتى إن تشاركا JVM UI أو wrapper. يجب أن يثبت لكل منهما: معمارية CPU المدعومة، artifact native المتوافق، سياسة توزيع النموذج وترخيصه، directory آمن وتحقق hash، budget ذاكرة، بدء inference، streaming، cancellation، unload، وسجل آمن. وجود CMake أو ملف `.dll` أو `.so` لا يرفع الحالة فوق `IMPLEMENTED`.

## Web runtime: معيار قرار لا افتراض

يخضع الاستدلال المحلي في Web لدراسة مستقلة. يمكن أن ينتهي القرار إلى `BLOCKED` أو إلى cloud-only browser product إذا كانت WASM/WebGPU أو حجم النموذج أو الذاكرة أو توافق المتصفح أو حماية البيانات غير كافية. لا تنقل AIRI مكتبة Android JNI إلى Web، ولا تُرسل أسرار provider إلى JavaScript؛ يعزل الوصول الذي يحتاج secret خلف خدمة موثوقة أو يعتمد OAuth/provider flow مصمم للمتصفح.

## مؤشرات الأداء والسلامة

يسجل adapter مقاييس مجردة لا تكشف أسماء ملفات أو prompts: وقت التهيئة، وقت أول token، tokens/s، peak memory إن توفرت، سبب fallback، وعدد عمليات الإلغاء. تقارن performance بين builds موثقة وليس عبر قيم مقدرة. تظل بيانات المستخدم والنموذج الخام خارج telemetry الافتراضي.

## أدلة كل ترقية

| من → إلى | الدليل |
| --- | --- |
| `PLANNED` → `ARCHITECTED` | عقد، ownership، وfallback semantics موثقة. |
| `ARCHITECTED` → `IMPLEMENTED` | adapter مكتوب مع اختبار وحدة أو integration. |
| `IMPLEMENTED` → `BUILDS` | أمر build وartifact محفوظان لكل target. |
| `BUILDS` → `RUNTIME_VERIFIED` | جلسة inference فعلية تشمل streaming وcancellation. |
| أي حالة → `EXTERNAL_VERIFICATION_REQUIRED` | يتطلب hardware أو credentials أو حساب provider غير متاح في بيئة CI. |
