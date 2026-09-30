# AIRI — تدقيق جنائي لسطح التنفيذ الحي

## النطاق والمرجع

أُجري التدقيق على الرأسين المتناظرين `main:dacc317b` و`cp-foundation:d51602f` بعد آخر تحديث **Harden Phase 3 agent and memory reliability**. نتائج GitHub Actions الأخيرة الظاهرة وقت المراجعة كانت ناجحة للـAndroid CI وArchitecture Audit وDeep Audit وOracle على الرأسين. هذا يثبت ما نفذته البوابات، ولا يثبت جهازاً فعلياً أو provider/OAuth حيّاً.

## مصادر الحقيقة المثبتة

| المجال | المصدر | الحكم |
|---|---|---|
| حالة التنفيذ | `ExecutionStatusBus.status` | المصدر الوحيد لحالة stage وexecution ownership |
| trace | `ExecutionStatusBus.trace` و`ExecutionTraceBuffer` | bounded، sequence-ordered، وsanitized قبل العرض |
| إسقاط الخطوات | `TaskExecutionTracker` | ينشئ الخطة بعد admission ذي `executionId` وgoal، ويقبل أفعالاً بدأت فعلياً |
| projection UI | `AgentPlanViewModel` | ينسق trace/filter/scroll/snapshot؛ لا ينفذ أدوات |
| التركيب | `ChatScreen` | يعرض overlay فوق Composer ويربط الإلغاء بـ`ChatViewModel.cancelGeneration()` |
| activity feed | `AgentActivityBus` | قناة منفصلة bounded للملخصات، وليست بديلاً عن execution trace |

## تدفق الهوية

التدفق الحالي هو `sessionId → request identity → executionId → active node/action → trace actionId`. الحماية المطبقة تمنع الأحداث ذات executionId الفارغ أو المختلف من تعديل التنفيذ النشط، وتمنع lifecycle الأداة من terminal مكرر. يجب ألا تستنتج الواجهة هذه الهوية من نص المحادثة أو من index الخطوة.

## العيب المثبت الذي بدأ إصلاحه

`TaskExecutionTracker` كان يضع الخطوات في حالة `CANCELLED` ويجعل `isVisible=true`، لكن `PlanPanelVisibilityPolicy` كان يسمح بظهور اللوحة في `PLANNING/EXECUTING/RECOVERING/REFLECTING/COMPLETED/FAILED` فقط. لذلك كان مسار Chat العادي يستطيع إخفاء نتيجة الإلغاء النهائية رغم وجود evidence صحيح في runtime. أضيفت `CANCELLED` إلى policy وأضيف اختبار انحدار مستقل.

## الفجوات المفتوحة

1. لا تزال بعض مسارات SkillService/legacy tools بحاجة إلى تمرير execution/action identity صريحة.
2. لا يوجد دليل process recreation أو rotation لسطح التنفيذ؛ snapshot الحالي حماية UI محدودة وليس checkpoint لاستئناف side effects.
3. لا يوجد دليل مرئي فعلي لـRTL/font-scale/TalkBack أو جهاز ARM64 في هذه البيئة.
4. provider/OAuth/network runtime لا يُعلن نجاحه من CI المحلي.
5. overlay القديم يحتوي عناصر عرض يجب رفع مستوى semantics والدلالة البصرية فيها، مع عدم إنشاء event bus أو state owner جديد.

## بوابة الدفعة الأولى

- [x] إثبات آخر commit وCI قبل التعديل.
- [x] توثيق architecture/source of truth وroot cause.
- [x] إبقاء trace آمناً bounded وعدم إضافة CoT.
- [x] إصلاح visibility regression للإلغاء مع اختبار سلبي/إيجابي.
- [ ] تشغيل Android build محلياً؛ البيئة تحتاج Android SDK.
- [ ] تشغيل CI بعد commit الدفعة.

## قرار التصميم

المرحلة التالية يجب أن تركز على semantics وterminal evidence في السطح القائم، ثم على legacy producer identity، ثم lifecycle/device evidence. لا ينبغي إعادة بناء `ModalBottomSheet` أو orchestrator جديد قبل إغلاق هذه الفجوات.
