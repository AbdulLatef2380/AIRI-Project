# AIRI Core Architecture Overview

## الغرض

AIRI Core يفصل واجهة Android عن تنفيذ الوكيل، والنماذج، والذاكرة، والموصلات، والخدمات النظامية. لا يهدف الفصل إلى زيادة الطبقات؛ كل حد أدناه يملك سبباً عملياً: عزل البيانات، قابلية الاختبار، أو استبدال المزود أو الامتداد بأمان.

```text
Compose UI + ViewModels
        │ intents / UI state
        ▼
AgentLoop + HybridOrchestrator + ExecutionGenerationGate
        │ plan / cancellation / execution result
        ├───────────────┬──────────────────┬─────────────────┐
        ▼               ▼                  ▼                 ▼
Model routing      Skills + tools       Memory/RAG        Android services
local llama.cpp    Connectors            Room/embeddings   Voice / WorkManager
cloud providers    Permissions           Attachments       Files / notifications
```

## الحدود الأساسية

| الحد | المالك | المسؤولية | ما لا يملكه |
|---|---|---|---|
| UI وViewModels | `ui/` | عرض الحالة، معالجة نوايا المستخدم، واستدعاء حالات الاستخدام. | مفاتيح مزود أو سياسة وصول خام إلى الملفات. |
| التنفيذ | `agent/` و`execution/` | تخطيط، تشغيل، إلغاء، استرداد، وحماية callbacks من الجيل القديم. | تخزين واجهة Compose أو فتح مرفقات خارج السياسة. |
| النماذج | `ai/` و`execution/backend/` | سجل النماذج، قدراتها، التوجيه، محلي/سحابي، وبث النتيجة. | قرار ذاكرة طويل المدى أو منح صلاحية موصل. |
| المهارات والموصلات | `ai/skills/` و`connector/` | المهارة workflow؛ الموصل مصدر بيانات/أداة خارجية؛ الصلاحيات وحالة الاتصال. | تحويل محتوى خارجي إلى تعليمات موثوقة. |
| الذاكرة | `memory/` | admission، Room، embeddings، RAG المعزول بالجلسة، وحذف الجلسة. | إرسال الذاكرة إلى مزود بلا تكوين التنفيذ. |
| مرفقات وملفات | `domain/AttachmentPolicy` و`ChatViewModel` | التحقق، النسخ إلى التخزين الخاص، سياق نصي محدود، والتنظيف عند حذف الجلسة. | تحليل فيديو غير مدعوم أو حفظ URI منتقي الملفات الخام. |
| Android runtime | `voice/` و`agent/scheduler/` و`core/` | الصوت، WorkManager، المكونات النظامية، والقيود الحياتية. | تشغيل وكيل خارج القيود أو منح أذونات ضمنية. |

## تدفقات التشغيل

### المحادثة

```text
Composer → ChatViewModel → AgentLoop → HybridOrchestrator
        → LocalLlamaBackend أو CloudProviderAdapter
        → stream/result → persistence → UI
```

`ExecutionGenerationGate` يربط callbacks بجيل التنفيذ النشط. عند الإلغاء أو بدء جيل جديد تُرفض النتيجة أو الخطأ أو صورة من جيل قديم.

### الذاكرة

```text
Message → admission → normalization → persistent transcript
        → optional embedding → session-scoped retrieval → bounded RAG context
```

الحقائق طويلة المدى تتطلب نية صريحة وتُرفض بيانات حساسة. يُوسم سياق RAG وملف النص المرفق كبيانات غير موثوقة، وليس تعليمات قابلة للتنفيذ.

### المرفقات

```text
Select → validate size/type/duplicate URI → app-private copy → preview queue
       → bounded text context or image-capable request → metadata persistence
       → delete with chat session
```

لا تحفظ AIRI URI المصدر. لا تدّعي طبقة النماذج الحالية فهم الفيديو؛ يُعامل الفيديو كمرفق وصفي إلى أن يُضاف معالج قدرة فيديو صريح.

### الصوت

```text
Permission → Vosk model check → Listening → partial transcript → final transcript
           → composer or voice chat → TTS / stop / release
```

النص الجزئي مرئي أثناء الاستماع فقط ولا يتحول إلى رسالة حتى تصدر النتيجة النهائية.

## ملكية البيانات والحالة

| البيانات أو الحالة | المالك | الاستمرارية |
|---|---|---|
| جلسات ومحادثات | Room عبر `MemoryManager` و`SessionDao` | دائمة محلياً |
| حقائق الذاكرة وembeddings | Room وخدمة embedding | دائمة محلياً وفق سياسة admission |
| ملفات المرفقات | `filesDir/attachments` | خاصة بالتطبيق وتحذف مع الجلسة |
| حالة الجيل والإلغاء | `ExecutionGenerationGate` | في الذاكرة لكل تنفيذ |
| مفاتيح المزود | التخزين الآمن المخصص | محلي محمي؛ لا تُسجل في السجل |
| أعمال مجدولة | Room + WorkManager | مستردة وفق معرف عمل فريد |

## قاعدة البيانات وترحيلاتها

قاعدة AIRI الأساسية تستخدم Room **v7** مع `exportSchema = true`. ترحيل `v6→v7` يضيف حالة تثبيت الجلسة. لا يضاف ترحيل جديد إلا مع اختبار ترحيل صريح وتحديث سجل التحقق.

## حدود JNI والنموذج المحلي

`LlamaManager` ينسق الاستدعاء إلى JNI و`llama.cpp`. يُحمّل النموذج المحلي عند اختيار مسار محلي متوافق، وتبقى سياسة الإلغاء فوق طبقة callback. لا تعدّل إعدادات native أو thread counts بلا قياس على أجهزة واقعية، لأن سلوك الذاكرة والحرارة يعتمد على النموذج والجهاز.

## نقاط الامتداد الصحيحة

| الامتداد | المسار المفضل |
|---|---|
| مزود نموذج جديد | سجل النماذج وadapter خلف حدود التنفيذ، مع قدرات وصحة وtimeout. |
| مهارة جديدة | manifest/workflow في سجل المهارات، مع صلاحيات وحالة ثقة منفصلة عن الموصل. |
| موصل جديد | `ConnectorRegistry`، OAuth/PKCE أو سر آمن، وحالة صحة قابلة للرصد. |
| نوع مرفق جديد | `AttachmentPolicy` ثم نسخ خاص ومعالج قدرة معلن؛ لا تضف parsing إلى Compose. |
| عمل مجدول | orchestrator وWorker فريدان مع constraints وسجل نتيجة. |

> هذا المستند يصف المصدر الحالي ويحدد أماكن الامتداد. لا يَعِد بواجهة SDK عامة أو خدمة backend غير موجودة.
