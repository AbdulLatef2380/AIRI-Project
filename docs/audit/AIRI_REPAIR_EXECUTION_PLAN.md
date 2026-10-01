# AIRI — خطة إصلاح ما قبل الإطلاق

## التقسيم التنفيذي

### الحزمة الأولى — حدود الأمان وصحة البيانات

الأولوية: P1 security/correctness.

- منع `terminal_execute` من المرور كأداة قراءة.
- فرض allowlist الأدوات المعلنة للجلسة والتحقق من required/unknown parameters.
- منع memory recall غير المربوط بـ`sessionId`.
- إصلاح سباق إنشاء singleton لقاعدة Room.
- إضافة اختبارات انحدار لحدود الأدوات والذاكرة والتهيئة المتزامنة.

الحالة: **منفذ محلياً، بانتظار CI**.

### الحزمة الثانية — Lifecycle والتزامن وNative والموصلات

الأولوية: P1/P2 reliability.

- تمييز cancellation عن failure في orchestrator وconnectors.
- جعل حذف الجلسة يلغي generation ويمنع الكتابة اللاحقة.
- إصلاح `ExecutionStatusBus` وtrace ownership والتحديثات الذرية.
- إصلاح Vosk/TTS lifecycle وmodel swap/vision cancellation.
- إغلاق SSRF والـredirect/DNS ومشاكل HTTP resource lifecycle.
- تنظيف orphaned attachments وRoom corruption/backup recovery.

الحالة: **مخططة، لم تبدأ**.

### الحزمة الثالثة — الإصدار وCI وUX والخصوصية

الأولوية: P1/P2 release readiness.

- بناء AAB والتحقق من signing certificate/hash/mapping/provenance.
- versioning monotonic لـAndroid وDesktop.
- CI gates لـWindows/KMP/coverage/ARM64 وdiagnostic artifacts.
- إصلاح local-only navigation وRTL والتوطين وplan snapshot isolation.
- تشفير/تقليص execution history وplan snapshots وcrash reports.
- تحسين FileProvider، accessibility، compact layouts، وoffline UX.

الحالة: **مخططة، لم تبدأ**.

## قواعد التنفيذ

1. كل إصلاح يملك اختبار انحدار أو دليل تحقق مناسب.
2. لا تُدمج دفعة قبل `git diff --check` و`verify_core_changes.py` وCI.
3. لا تُخفى findings غير القابلة للاختبار؛ تُنقل إلى runtime-proof queue.
4. بعد كل حزمة تُحدّث مصفوفة المخاطر والتقرير، ولا يُعلن GA قبل إغلاق blockers أو قبولها بقرار موثق.

## الدفعة الأولى المنفذة

- `AgentLoopSideEffectPolicy`: إضافة `terminal_execute` إلى side-effect tools.
- `AgentLoop`: رفض الأدوات غير المعلنة، والوسائط غير المعروفة، والوسائط المطلوبة المفقودة قبل dispatch.
- `ToolDispatcher`: رفض memory recall عند غياب session scope، واستخدام recent query مربوطاً بالجلسة عند fallback.
- `AiriDatabase`: إعادة فحص `INSTANCE` داخل synchronized لمنع بناء Room مرتين.
- الاختبارات: توسيع regression policy ليشمل terminal execution.

التحقق المحلي: `tools/verify_core_changes.py` — **96/96 ناجح**.
اختبار Gradle المحلي: تعذر البدء بسبب غياب Android SDK/local.properties في Sandbox؛ يلزم CI للتحقق من compile/unit tests.
