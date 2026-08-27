# AIRI — خطة الإصلاح المرحلية

> الخطة مبنية على تدقيق الرأس `b4ea8397`. لا تعني «مكتمل» إلا بدليل الاختبار المناسب؛ device/provider/store/legal تبقى بوابات خارجية.

| Milestone | النطاق | Root causes المعالجة | المخرجات | معيار الانتقال |
|---|---|---|---|---|
| M0 — Audit | جرد 658 Kotlin و55 شاشة و66 اختباراً، Manifest، build/release، resources، ownership، providers، evidence | RC-01 إلى RC-05 | التقارير الستة، خريطة التكامل، مصفوفات root cause/runtime/external | مراجعة المصدر وتسجيل كل issue بحالة صادقة؛ لا patch تجميلي. |
| M1 — Foundation | Account/Profile، auth/session، localization architecture، theme tokens، state ownership | RC-01، RC-02، RC-05 | `AccountContext` وprofile ownership، outcomes موحدة، localization/theme contracts | unit/JVM + source contracts + CI؛ runtime account/device يبقى pending. |
| M2 — Conversation Core | lifecycle، drafts، attachments، input، history، model binding | RC-01، RC-03، attachment boundary | typed conversation/draft/attachment states، request/session ownership | policy/unit، Room/instrumentation، UI matrix، CI. |
| M3 — AI Execution | router، connectors، streaming، errors، retries، cancellation، planning، safe execution summaries | RC-01، RC-02، RC-03 | requestId/executionId events، explicit model fallback، no fake progress/CoT | unit/integration contract + instrumentation/CI؛ providers الحية منفصلة. |
| M4 — Usage/Credits | accepted/inference/complete/fail/cancel event ledger، local/cloud cost semantics | RC-01 | idempotent metering وrollback/no-charge rules | unit failure/retry/cancel/partial-stream tests + CI؛ provider cost pending. |
| M5 — Permissions/Audio | runtime permission map، Accessibility disclosure، microphone، STT، wake word، TTS lifecycle | RC-03، RC-04 | capability-derived status، explicit download، release-safe services | unit + Android instrumentation؛ ARM64 API 26/35/36 device matrix. |
| M6 — Integrations | Google/GitHub auth، connector capability، OAuth state، consent، revoke؛ Zapier/IFTTT contract disabled | RC-02، RC-04 | `NotConfigured/Authorized/Failed/Revoked` states، no secrets in APK | source/unit/CI؛ provider test accounts مطلوبة للـruntime. |
| M7 — Developer Surfaces | Terminal/Workspace/Sandbox/Secrets/Scanner، approvals، filesystem/process cleanup | RC-02، RC-04 | internal-only release boundary، typed ownership، redaction، security evidence | security/source/CI؛ جهاز أو بيئة تنفيذ مصرح بها عند الحاجة. |
| M8 — Product UX | Home/About/Conversations/Attachments/Profile/Settings، feedback، empty/error/loading/cancel، accessibility | RC-01، RC-04، localization/theme | UX موحد مبني على state حقيقي، بلا claims ثابتة أو notices زائدة | Compose/UI tests، RTL/LTR/font-scale snapshots، device visual review. |
| M9 — Release | artifact/R8/native/signing، permission/runtime، API 26/35/36 ARM64، Play/Data Safety/Privacy/Legal | RC-05 | artifact hashes، mapping، certificate evidence، matrices الخارجية | CI لا يكفي: يلزم device/provider/store/legal evidence بحسب الصف. |

## ترتيب الدفعات التنفيذية

1. لا تغيّر أي surface قبل تثبيت M0 والتقرير الحالي.
2. عالج ownership المشترك في account/conversation/model/event قبل polish.
3. لكل دفعة: audit → pure policy أو contract → regression test → guards → diff review → commit صغير مرتبط بالـIssue IDs → دفع `cp-foundation` ثم fast-forward إلى `main` → CI كامل → توثيق.
4. إذا كشف CI عطلاً عادياً، يُصلح ويعاد الاختبار؛ إذا احتاج الدليل جهازاً أو مزوداً أو حساباً، تُسجل الحالة `EXTERNAL_PENDING` ولا تُحاكى.
5. يمنع تغيير `architecture-refactor` أو إعادة كتابة المعمارية أثناء هذه الخطة.

## قواعد منع التوسع الوهمي

لا يُسمح بإضافة fallback عشوائي، fake provider، simulated progress، fake update، hardcoded account، أو نجاح placeholder. الميزة التي لا تملك backend/credential/authorization/جهازاً مناسباً تعرض `Not Configured` أو `Not Available` مترجمة، مع إبقاء عقد الربط المستقبلي واضحاً.


## سجل تنفيذ M2 — Conversation Core

أُغلقت هذه الدفعة في commit `3770e221cf97d7e0c875f9e72a9fd07cda119adb` بعد نجاح Architecture Audit وDeep Audit وOracle وAndroid CI (`33066904307`، `33066904321`، `33066904340`، `33066904320`). شمل الإغلاق session-owned attachment metadata، expected session admission، model snapshot عبر AgentLoop/ExecutionRequest، وfail-closed model mismatch في LocalLlamaBackend. يبقى الاختبار المحلي Gradle محجوباً فقط لغياب Android SDK في البيئة، بينما نفّذ CI compile/unit/lint/packaging/instrumentation/native verification بنجاح. التفاصيل في `AIRI_M2_CONVERSATION_CORE_REPORT.md`.

بعد هذا الإغلاق تنتقل الخطة إلى M3 — AI Execution، ولا يُعاد فتح M1/M2 إلا إذا كشف CI أو runtime دليلاً جديداً.


## سجل تنفيذ M3 — AI Execution

أُغلقت دفعة هوية أحداث التنفيذ في commit `06115624fac5954ca9919ff4368392fada88c99b` بعد نجاح Android CI `33070581767` وDeep Audit `33070581782` وOracle `33070581804` وArchitecture Audit `33070581824`. عالجت الدفعة fail-closed execution event ownership في `ExecutionStatusBus`، وربطت `AdaptiveGraphEngine` بهوية graph ثابتة من admission إلى completion/cancellation/recovery، وأضافت regression tests. حدث فشل أولي في Android CI على commit `0ccf5175` بسبب استدعاء Android Log من JVM test؛ أُصلح الاختبار دون حذف التغطية، ثم نجح CI المعاد. التفاصيل في `AIRI_M3_AI_EXECUTION_REPORT.md`.

العناصر التالية المفتوحة ضمن M3 هي cancellation الشبكي العميق، منع retry/fallback بعد الإلغاء، وضمان terminal event idempotency.


## متطلب محوري مضاف — Agent Planning & Execution Trace

المستند `AGENT_TRACE_IMPLEMENTATION_REGISTER.md` هو سجل التنفيذ الملزم لنظام تخطيط الوكيل وتتبع التنفيذ الحي. يغطي structured planning، execution identity، safe reasoning summaries، tool tracing، stream bounded، شجرة التقدم، سجل حي في Chat، cancellation، persistence الملائم، وربط local/cloud/tools. هذا المسار يعالج 09 و23 و27B مباشرةً، ويدعم الأدلة المطلوبة للبنود 07 و08 و11 و12 و19 و24 و25. لا يعيد بناء AIRI أو يضيف transport شبكياً غير مطلوب؛ كل تعديل يوسع الطبقات الحالية ويخضع لبوابات source/test/CI/runtime المحددة في السجل.
