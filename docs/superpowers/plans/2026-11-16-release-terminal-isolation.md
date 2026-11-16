# Release Terminal Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** نقل `terminal_execute` في Release إلى Android isolated process مع حدود ورفض fail-closed، وعدم تفعيل الحظر إلا بعد إثبات capability فعلي.

**Architecture:** يبقى `TerminalExecutionGateway` نقطة الدخول الوحيدة. يقرر `SandboxIsolationPolicy` ما إذا كان التنفيذ المحلي مسموحًا، ثم يرسل الطلب إلى `IsolatedTerminalService` عبر Binder. الخدمة المعزولة لا تصل إلى app-private files؛ تستقبل فقط command وmetadata ونسخًا محدودة من seed content، وتشغل argv المسموح مع watchdog. تبقى sandbox JVM الحالية لمسار Debug والاختبارات، بينما Release يستخدم provider المعزول.

**Tech Stack:** Kotlin, Android Service, Binder/AIDL, Coroutines, Compose-independent domain policies, Android instrumentation.

**Spec:** `docs/specs/release-terminal-isolation.md`

## Global Constraints

- لا تغيير لـ `OS_SANDBOX_AVAILABLE` إلى true قبل probe واختبارات الجهاز.
- لا `sh -c` ولا مسارات app-private files داخل الخدمة.
- deny-by-default للشبكة ولـ environment.
- كل نتيجة تحمل `sessionId`, `messageId`, `executionId`, `traceId`.
- كل command mutation يحتاج approval قبل الإرسال للخدمة.

## Review Focus

- Binder request forgery أو فقد correlation: يختبره `TerminalExecutionRequest` وinstrumentation.
- وصول الخدمة المعزولة إلى `filesDir`: marker test.
- descendant timeout: process-tree test.
- memory/output abuse: bounded-output and watchdog tests.
- network escape: denied-by-default probe.

---

### Task 1: Security contracts and capability proof

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/agent/sandbox/SandboxIsolationPolicy.kt`
- Create: `app/src/main/java/com/airi/assistant/agent/sandbox/OsSandboxCapability.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/sandbox/SandboxIsolationPolicyTest.kt`

- [ ] أضف `OsSandboxCapability` immutable result يحتوي `isolatedProcess`, `uidSeparated`, `appFilesDenied`, `networkDenied`, `limitsEnforced` وسبب الفشل.
- [ ] اجعل `SandboxIsolationPolicy` تقبل capability proof بدل Boolean ثابت، وتبقي Release deny عند غياب proof.
- [ ] أضف اختبارات لكل proof ناقص وproof كامل.

### Task 2: Binder contract and isolated service

**Files:**
- Create: `app/src/main/aidl/com/airi/assistant/terminal/ISandboxTerminalCallback.aidl`
- Create: `app/src/main/aidl/com/airi/assistant/terminal/ISandboxTerminalService.aidl`
- Create: `app/src/main/java/com/airi/assistant/terminal/IsolatedTerminalService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/androidTest/java/com/airi/assistant/terminal/IsolatedTerminalServiceInstrumentationTest.kt`

- [ ] صمم callback للنتائج والـprobe والـcancel.
- [ ] سجّل الخدمة `exported=false`, `isolatedProcess=true`, بدون permission network.
- [ ] نفذ command runner داخل الخدمة باستخدام argv policy نفسها، workspace مؤقت للخدمة، output cap وwatchdog.
- [ ] لا تقبل network أو environment أو app path من Binder.
- [ ] نفذ probe يثبت UID مختلفًا ورفض marker الخاص بـapp files.

### Task 3: Resource limits and termination

**Files:**
- Create: `app/src/main/java/com/airi/assistant/terminal/IsolatedProcessLimits.kt`
- Modify: `app/src/main/java/com/airi/assistant/terminal/IsolatedTerminalService.kt`
- Modify: `app/src/main/java/com/airi/assistant/agent/sandbox/ProcessTreeTerminator.kt`
- Test: `app/src/test/java/com/airi/assistant/terminal/IsolatedProcessLimitsTest.kt`

- [ ] ثبت الحدود المركزية للزمن والذاكرة وCPU والخرج والملف.
- [ ] راقب elapsed wall time وCPU time وPSS، وأنهِ العملية/الخدمة عند التجاوز.
- [ ] اجعل cancellation وtimeout يقتلان descendants وينظفان workspace.
- [ ] اختبر الحدود دون الاعتماد على توقيت هش.

### Task 4: Gateway integration and approval

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/terminal/TerminalExecutionGateway.kt`
- Modify: `app/src/main/java/com/airi/assistant/terminal/TerminalExecutionRequest.kt`
- Modify: `app/src/main/java/com/airi/assistant/domain/terminal/TerminalExecutionPolicy.kt`
- Test: `app/src/test/java/com/airi/assistant/terminal/TerminalExecutionGatewayTest.kt`

- [ ] أضف `approvalId`/expiry إلى request للأوامر التغييرية.
- [ ] اربط gateway بمستقبل isolated service مع timeout وbinder death handling.
- [ ] اجعل debug fallback صريحًا، وRelease يمر فقط بعد capability proof.
- [ ] حافظ على session/message/execution/trace correlation في كل outcome.

### Task 5: AgentLoop and instrumentation coverage

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt`
- Create: `app/src/androidTest/java/com/airi/assistant/agent/loop/ReleaseTerminalExecutionInstrumentedTest.kt`
- Modify: `app/src/test/java/com/airi/assistant/terminal/TerminalExecutionRequestTest.kt`

- [ ] تحقق من أن AgentLoop يرسل correlation IDs ويعرض approval required بدل execution.
- [ ] أضف instrumentation للنجاح والفشل والtimeout والهروب والشبكة.
- [ ] تحقق من عدم إنشاء success evidence عند فشل execution.

### Task 6: Verification and documentation

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/security/README.md`
- Test: CI workflows as needed.

- [ ] شغّل unit tests وlint وassembleRelease وconnected tests.
- [ ] لا تغيّر gate إلى Release-ready إلا إذا نجحت device proof.
- [ ] سجّل ما بقي disabled إذا تعذر تشغيل جهاز instrumentation.
