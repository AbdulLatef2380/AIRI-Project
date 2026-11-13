# أدلة Android الرسمية لمسار استيراد النماذج المحلية

## Storage Access Framework

توثيق Android الرسمي يوضح أن `ACTION_OPEN_DOCUMENT` يعيد ملفاً بصيغة `content:` تديره `DocumentsProvider`، ويمكن فتحه عبر `ContentResolver`، كما يمنح التطبيق صلاحية قراءة طويلة الأمد عند استخدام النمط الصحيح. يجب أن يحدد الـIntent نوع MIME ويضيف `CATEGORY_OPENABLE`. هذا يدعم الاحتفاظ بصلاحية URI ثم نسخ المحتوى إلى مساحة AIRI الخاصة بدلاً من الاعتماد على مسار خارجي قد يتغير.

المصدر: https://developer.android.com/guide/components/intents-common#Storage

## التخزين الخاص بالتطبيق

توضح وثائق Android أن `filesDir` مناسب للملفات المستمرة الخاصة بالتطبيق، ولا تحتاج القراءة والكتابة فيه إلى صلاحية تخزين عامة. كما تحذر الوثائق من صغر مساحة التخزين الداخلية وتوصي بفحص المساحة المتاحة قبل الكتابة. لذلك يستخدم مسار الاستيراد ملفاً مؤقتاً ثم ينشره بعد اكتمال النسخ والتحقق، مع إزالة الملف الجزئي عند الفشل.

المصدر: https://developer.android.com/training/data-storage/app-specific

## الحركة في Compose

توثق Compose أن `AnimatedVisibility` مخصص لإظهار وإخفاء المحتوى مع انتقالات قابلة للتخصيص، وأن الحركة يجب أن ترتبط بتغير الحالة. لذلك يجب أن تكون بطاقة الاستيراد هي الحاوية المرئية للحالة، لا Dialog عام يقفز فوق الشاشة في كل تحديث.

المصدر: https://developer.android.com/develop/ui/compose/animation/composables-modifiers

## الحوارات

توثق Compose أن `AlertDialog` تجربة مقاطعة مناسبة لتأكيد إجراء أو طلب قرار، بينما `Dialog` المخصص مناسب للمحتوى المركب. بناءً على ذلك سيبقى الإبلاغ أو التفاصيل التقنية اختيارياً، بينما تعرض دورة النسخ والتحقق والجاهزية داخل بطاقة الحالة الأساسية.

المصدر: https://developer.android.com/develop/ui/compose/components/dialog
