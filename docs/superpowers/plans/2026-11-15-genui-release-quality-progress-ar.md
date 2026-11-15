# تقدم إغلاق Generative UI — 15 نوفمبر 2026

## نطاق هذه الدفعة

الفرع: `feat/genui-release-quality` مبني على `7874a1e1`.

بدأ التنفيذ من المرحلة الأولى كما يحدد تقرير التدقيق: حماية حدود أفعال GenUI، منع الإرسال التلقائي، والتحقق من هوية رسالة المصدر، ثم تمرير `messageId` إلى AgentLoop لإغلاق correlation مع الطرفية.

## ما تغير

- أضيفت معاينة صريحة داخل `GenerativeUiRenderer`: الضغط على Action/Form يفتح حوار مراجعة؛ التأكيد يمرر النص إلى التطبيق، ولا يرسل الرسالة.
- `ChatViewModel.handleGenerativeUiAction` أصبح يرفض المصدر الفارغ/القديم/غير المساعد أو التنفيذ المختلف، ويعيد بناء الإجراء من نص الرسالة المصدري بدلاً من الوثوق بـ`prompt` القادم من Compose، ثم يضعه في المحرر.
- قيم Form محصورة في حقول المخطط، مع required/length checks؛ Actions يعيد prompt المعلن في الرسالة.
- تمرير `userMessage.id` إلى `AgentLoop.run(messageId=...)` حتى لا تفشل الطرفية بسبب غياب message correlation.
- أضيفت نصوص المعاينة باللغات AR/EN/ES/ZH.
- شُدّد parser على `version` و`type` و`id` وallow-list للمفاتيح المتداخلة، ورفض الأرقام غير finite أو الخارجة عن المجال بدلاً من clamp، مع اختبارات للـprompt المزور والمفاتيح والإحداثيات.
- صُحح تمرير `AiBubble` ليستخدم `ChatMessage.id` الدائم؛ `uid` يبقى للـCompose فقط ولا يدخل حد الثقة.

## ما لم يُغلق بعد

- اختبارات ViewModel/Compose/end-to-end الخاصة بهذه الحدود.
- strict parser: ما زال يلزم استكمال حدود العمق والحجم والـfuzz؛ الجزء الأساسي الخاص بالمفاتيح والأنواع والأرقام نُفذ في هذه الدفعة.
- resolver للسياق الحالي، pipeline المخرجات/الأدلة، الرسم الدقيق، artifact cards، streaming/performance، وrelease evidence.

## التحقق

- `git diff --check`: ناجح.
- فحص XML وتكافؤ مفاتيح المعاينة في `values`, `values-ar`, `values-es`, `values-zh`: ناجح.
- `:app:testDebugUnitTest --tests ...GenerativeUiCompilerTest`: لم يبدأ بسبب غياب Android SDK (`ANDROID_HOME` و`ANDROID_SDK_ROOT` فارغان، ولا يوجد `android.jar` مكتشف). هذه بوابة blocking وليست نجاحاً صامتاً.

## قاعدة العمل

لا يوجد push أو merge إلى `main` في هذه الدفعة. كل ادعاء نجاح سيُربط بنتيجة اختبار على SHA الفعلي، وأي بوابة لا يمكن تشغيلها ستبقى معلّمة كـpending/blocking.
