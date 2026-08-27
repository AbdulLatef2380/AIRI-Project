# AIRI — تقرير تدقيق وإصلاح Conversation Core (M2)

## نطاق الدفعة

عالجت هذه الدفعة ملكية دورة الإرسال في المحادثة، وربط المسودة والمرفقات بالجلسة، وتثبيت هوية النموذج المختار عند قبول التنفيذ. لم تُفتح إعادة كتابة معمارية أو ميزات تجارية خارج نطاق M2.

## العيوب الجذرية والإصلاحات

| Root cause | العيب المثبت | الإصلاح المنفذ | دليل التحقق |
|---|---|---|---|
| RC-01 / RC-03 | metadata المرفق كان يُحمل في حقول pending عامة، ويمكن أن يُقرأ بعد تبديل الجلسة | أضيف `pendingAttachmentSessionId`، ولا تُستهلك metadata إلا عند تطابق sessionId | اختبارات سياسة ownership وAndroid CI |
| RC-01 / RC-03 | مسار attachment النصي كان يمكن أن يبدأ coroutine بجلسة مختلفة عن جلسة staging | أضيف `expectedSessionId` إلى `sendMessageInternal`، وتُرفض العملية عند mismatch قبل الكتابة إلى Room | Deep Audit وAndroid CI |
| RC-01 / RC-03 | مسار الصورة كان يقرأ الجلسة والنموذج العالميين بعد بدء الطلب | ثُبت `sessionAtDispatch` و`modelIdAtDispatch`، ويُرفض التنفيذ إذا تغيّرت الجلسة أو النموذج قبل تسجيل الرسالة | Android CI instrumentation |
| RC-01 | جولات AgentLoop اللاحقة لم تكن تحمل هوية نموذج صريحة | أضيف `modelId` إلى `AgentLoop.run` و`requestedModelId` إلى `ExecutionRequest` عبر جميع الجولات | Architecture Audit وAndroid CI |
| RC-01 | Local backend كان ينفذ على النموذج المحمل حالياً حتى لو تغيّر عن النموذج المقبول | أضيف fail-closed guard في streaming وbatch؛ mismatch يرجع `model_changed` ولا يبدأ native generation | Deep Audit وAndroid CI |
| Validation | `validate_airi.sh` كان يتوقف كاذباً مع grep بلا نتائج وpost-increment تحت `set -e`، ويفوّت route arguments | إصلاح التعامل مع zero-match، والعدادات، وصيغ `composable(AiriRoute.X)` و`${AiriRoute.X}/{id}` | `bash -n` ومرحلة source contracts في CI |

## الاختبارات والأدلة

| الفحص | النتيجة | المرجع |
|---|---|---|
| `git diff --check` | PASS | قبل commit |
| `bash -n scripts/validate_airi.sh` | PASS | قبل commit |
| AIRI Architecture Audit | PASS | [33066904307](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904307) |
| AIRI Deep Audit | PASS | [33066904321](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904321) |
| AIRI Oracle | PASS | [33066904340](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904340) |
| AIRI Android CI | PASS؛ شمل compile، unit tests، lint، signed/unsigned packaging، instrumentation، native output والتحقق من artifacts | [33066904320](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904320) |
| Gradle المحلي | BLOCKED_ENVIRONMENT؛ Android SDK غير موجود في sandbox | لا يُحتسب فشلاً في المصدر |

## حالة الدفعة

الـ commit هو `3770e221cf97d7e0c875f9e72a9fd07cda119adb`، ودُفع إلى `cp-foundation` ثم إلى `main`. شجرة العمل نظيفة بعد الدفع. لا يثبت هذا التقرير اختبار جهاز ARM64 أو مزوداً حياً؛ تلك الأدلة تبقى ضمن مصفوفة M9 الخارجية.

## الخطوة التالية

تنتقل الخطة إلى M3 — AI Execution: تثبيت requestId/executionId عبر router/orchestrator، ومراجعة cancellation وfallback ونتائج التخطيط دون fake progress، مع إبقاء model binding الذي أُغلق في M2.

## References

[1]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904320 "AIRI Android CI — commit 3770e221"

[2]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904321 "AIRI Deep Audit — commit 3770e221"

[3]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904307 "AIRI Architecture Audit — commit 3770e221"

[4]: https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/33066904340 "AIRI Oracle — commit 3770e221"
