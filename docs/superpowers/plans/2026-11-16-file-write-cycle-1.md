# AIRI File Creation Cycle 1 Implementation Plan

> **For agentic workers:** تنفيذ الخطة داخل الجلسة الحالية Native مع مراجعة نهائية مستقلة.

**Goal:** جعل طلبات إنشاء الملفات مسارًا موحدًا وقابلًا للتحقق من نية المستخدم حتى بطاقة الملف، دون نجاح زائف أو تسريب خارج مساحة AIRI الخاصة.

**Architecture:** نثبت عقدًا pure لقرار file-write admission يميز بين registered/exposed/admitted/executable، ونستخدمه في ChatViewModel وAgentLoop بدل قرارات متفرقة. نضيف receipt موثوقًا إلى نتيجة الكتابة يتضمن هوية التنفيذ وخصائص الملف، ثم لا تُنشأ بطاقة الملف إلا من receipt متحقق وملف موجود فعلًا. يبقى `AgentFileWriter` الحارس النهائي للمسار والحجم والامتداد والـatomic write.

**Tech Stack:** Kotlin, Coroutines/Flow, MVVM, AgentLoop, Jetpack Compose, JVM unit tests, Android instrumentation where available.

**Spec:** `docs/superpowers/plans/2026-11-15-response-experience-abc.md` and the Cycle 1 requirement: a user request to create a file must produce a verifiable file artifact or an explicit, localized readiness/failure state.

## Global Constraints

- لا كتابة خارج `context.filesDir/agent_files`.
- لا نجاح دون ملف موجود فعليًا وreceipt يطابق الاسم والحجم والمحتوى المكتوب.
- لا تفعيل للطرفية أو أي side effect آخر كحل بديل لإنشاء الملف.
- لا تسجيل prompts أو محتوى الملف أو PII في telemetry؛ تسجل metadata غير الحساسة فقط.
- يجب أن يبقى المسار آمنًا مع العربية وRTL، ومع نماذج native tool وtext-protocol.
- أي رفض يجب أن يشرح السبب والإجراء التالي، لا أن يسقط إلى نجاح صامت أو يدعي أن الأداة غير موجودة بلا دليل.

## Review Focus

- طلب عربي مختصر مثل «أنشئ ملف calculator.py» يمر إلى `write_file`.
- نموذج لا يرسل tool calls: يظهر السبب الحقيقي ولا يدعي إنشاء الملف.
- ميزانية prompt صغيرة: لا تُسقط `write_file` إذا كان required؛ يظهر فشل budget واضحًا.
- فشل durable context/governance/disk: لا تُنشأ بطاقة ولا رسالة نجاح.
- overwrite واسم عربي ومحاولات traversal: تبقى مقيدة ومثبتة بالاختبارات.

### Task 1: Canonical file-write admission

**Files:**
- Create: `app/src/main/java/com/airi/assistant/agent/request/FileWriteAdmission.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/request/FileWriteAdmissionTest.kt`

- [x] تعريف result صريح للجاهزية والسبب والإجراء التالي.
- [x] اختبار أن request intent + model support + required tool + durable context تنتج `EXECUTABLE`.
- [x] اختبار الحالات `MODEL_NO_TOOLS`, `TOOL_NOT_EXPOSED`, `CONTEXT_BUDGET`, `DURABLE_CONTEXT`, `GOVERNANCE`.

### Task 2: Verified write receipt

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/tool/AgentFileWriter.kt`
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/loop/tool/AgentFileWriterTest.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/loop/tool/AgentFileCardTest.kt`

- [x] إضافة receipt machine-readable إلى نتيجة `write_file` بعد إعادة قراءة الملف والتحقق من الاسم والحجم.
- [x] إبقاء النص مقروءًا للنموذج مع metadata غير حساسة (`file`, `chars`, `sha256`).
- [x] جعل البطاقة لا تُنشأ إلا من receipt صحيح ومطابق للملف الفعلي.
- [x] اختبار mismatch/deleted file وعدم إنشاء بطاقة.

### Task 3: End-to-end request wiring

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/viewmodel/ChatViewModel.kt`
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`
- Modify: `app/src/main/java/com/airi/assistant/agent/request/AgentRequestPlanner.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/request/AgentRequestPlannerTest.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/loop/tool/RuntimeToolCatalogTest.kt`

- [x] تمرير قرار file-write المطلوب إلى AgentLoop بدل قبول fallback النصي كنجاح.
- [x] منع fallback الكودي من إخفاء سبب unavailable عندما يكون الملف مطلوبًا.
- [x] إبقاء `write_file` required داخل prompt budget في code task.
- [x] دعم aliases `path`/`file_name`/`code` دون تخفيف قيود المسار.

### Task 4: UI and instrumentation proof

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/components/AgentFileCardView.kt` عند الحاجة.
- Test: `app/src/androidTest/java/com/airi/assistant/agent/WriteFileAcceptanceInstrumentedTest.kt`.

- [ ] حالات البطاقة: موجود، حفظ فشل، مشاركة غير متاحة، فتح غير متاح.
- [ ] semantics عربية واسم الملف وحجمه.
- [ ] اختبار instrumentation لمسار الملف وFileProvider والموارد.

### Verification

- [ ] `./gradlew :app:testDebugUnitTest --tests '*FileWriteAdmissionTest' --tests '*AgentFileWriterTest' --tests '*AgentFileCardTest' --tests '*AgentRequestPlannerTest'` — تعذر التنفيذ لأن Android SDK غير موجود في البيئة (`ANDROID_HOME` و`ANDROID_SDK_ROOT` فارغان).
- [x] `python3 tools/security_scan.py`.
- [x] `python3 tools/verify_core_changes.py` (101/101).
- [x] `python3 tools/stability/static_contract_scan.py`.
- [x] `git diff --check` وXML parity ضمن بوابات core.
- [ ] تشغيل instrumentation — يتطلب Android SDK/جهاز CI.
