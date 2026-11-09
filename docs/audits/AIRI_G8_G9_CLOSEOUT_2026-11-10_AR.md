# إغلاق G-8 وG-9 — 2026-11-10

## G-8 — terminal capability negotiation

أضيف `TerminalCapabilityNegotiation` كعقد pure للتفاوض request-scoped. الطرفية لم تعد تُعرض فقط عبر `sandbox_process_execution` العام؛ أصبح `terminal_execute` نفسه يظهر في inventory مع:

- `registered`: هل gateway مسجل.
- `availability`: `READY` أو `CONFIGURATION_REQUIRED` أو `BLOCKED`.
- `inRequestCatalog`: هل الطلب يطلب terminal/shell/sandbox/command.
- `exposedToAgent` و`admittedToDispatcher`: لا يصبحان true إلا إذا كانت gateway وworkspace والسياسة جاهزة.
- `detail`: سبب typed يشرح إنشاء task-owned ephemeral workspace أو سبب block.
- `dispatcherRoute`: `ToolDispatcher → TerminalExecutionGateway`.

عند غياب gateway أو `SandboxManager` تظهر capability كـ`CONFIGURATION_REQUIRED` ولا تختفي. وعند رفض `TerminalExecutionPolicy` تظهر كـ`BLOCKED` ولا تُعرض كأداة قابلة للتنفيذ. يظل `TerminalExecutionGateway` هو الحارس النهائي، لذلك لا تمنح negotiation صلاحية تنفيذ بحد ذاتها.

أضيفت اختبارات تغطي registered-but-not-ready، policy blocked، وready/executable.

## G-9 — vertical slice fake integration tests

أضيف `CapabilityVerticalSliceTest` في JVM test source، ويستخدم العقود الفعلية لا mocks شكلية:

- skill: catalog → readiness → inventory → typed trace lifecycle.
- connector: unavailable/auth-like readiness لا يدخل `exposed`، ثم ready connector يدخل route الصحيح → trace lifecycle.
- terminal: malformed required argument، approval decision عبر model-facing repair contract، configuration-not-ready، policy block، terminal failure/timeout كـterminal غير ناجح، cancellation/restart ownership.

الاختبار يثبت كذلك أن action terminal القديم لا يمكن أن يُنهى بعد بدء execution جديد، وأن terminal successful يتطلب verification.

## البوابات

نجحت:

- `git diff --check`
- `python3 tools/security_scan.py` — `PASS` ولا توجد secret findings.
- `python3 tools/verify_core_changes.py` — `100/100 checks passed`.

تمت محاولة تشغيل الاختبارات المركزة:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.airi.assistant.agent.loop.tool.TerminalCapabilityNegotiationTest' \
  --tests 'com.airi.assistant.agent.loop.tool.CapabilityVerticalSliceTest' \
  --no-daemon --max-workers=1 --console=plain
```

لكن Gradle توقف قبل compilation بسبب غياب Android SDK:

```text
SDK location not found
ANDROID_HOME is not set
ANDROID_SDK_ROOT is not set
```

لا توجد نتيجة compiler سلبية يمكن نسبتها إلى G-8/G-9؛ يلزم تشغيل الاختبارات على CI أو جهاز يملك SDK.
