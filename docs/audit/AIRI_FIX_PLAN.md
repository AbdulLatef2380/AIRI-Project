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
