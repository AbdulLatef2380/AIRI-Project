# برنامج AIRI Agent OS للإغلاق المنتجّي

## الهوية التشغيلية

> **AIRI = Personal AI Operating Environment**: وكيل محلي أولاً، يربط المشروع والذاكرة والمعرفة والتنفيذ والأجهزة والنماذج والأتمتة ضمن Trust Fabric واحدة.

لا يساوي هذا البرنامج AIRI بمنتج سحابي أو بواجهة دردشة موسعة. كل انتقال بين المراحل يتطلب **تنفيذاً حياً وتكاملاً واختبار فشل ودليل UI/runtime وتوثيقاً**؛ وجود class أو screen وحده لا يغير حالة الطبقة إلى مكتملة.

## خريطة المراحل المرفقة إلى تيارات التنفيذ

| التيار | مراحل المرفق | الوضع الحالي في AIRI | بوابة الإغلاق التالية |
|---|---:|---|---|
| Architecture / dead-path | 01–02 | تم جرد أساسات المنتج ومسارات dead runtime السابقة؛ لا يزال audit دورياً ضرورياً. | call graph لكل مسار قبل الحذف، وعقد owner لكل حالة دائمة. |
| Agent OS / missions / durable execution | 03–04 و15–17 | DurableTask/Run/Step/Approvals/Recovery موجودة ومسارات الجدولة تمر بالمنسق الإنتاجي. | Mission ككيان مستقل، replay كامل، وواجهة Trust تعرض الحدود الحية لا metadata فقط. |
| Context / Memory / Knowledge | 05–08 | scoped RAG وRoom v8 وadmission/provenance وProject files موجودة. | semantic/hybrid retrieval وversion/restore وsync incremental مع قياس وعزل. |
| Studio / editor / terminal / sandbox / browser | 09–13 و31–32 | Workspace/Terminal/Sandbox/Artifacts موجودة؛ File/Code Editor وBrowser runtime وCanvas ما زالت جزئية أو غير موجودة. | File editor مع diff/history/approval، ثم Browser بمستويات المخاطر وtakeover؛ لا VNC قبلها. |
| Artifact / recovery / approval / secrets | 14–18 | artifacts والتعافي والموافقات وKeystore/SecretVault موجودة بحواجز سياسة. | graph recovery قابل للشرح، approval scopes كاملة، rotation/revoke وexport غير حساس. |
| Model / desktop / benchmark / mesh / continuity | 19–23 | routing/team policy وAndroid local GGUF وcontinuity opt-in موجودة؛ Desktop runtime محلي غير موجود. | Desktop llama runtime وbenchmark على جهاز حقيقي ثم handoff آمن بين nodes. |
| Automation / voice / vision | 24–26 | Scheduler/WorkManager وvoice state guard وattachment validation موجودة. | event triggers بعقد ownership، voice barge-in device validation، وOCR/evidence لا مجرد attachment marker. |
| MCP / connectors / skills | 27–30 | MCP/connector/skill governance وسياسات trust موجودة. | package signing/tests/simulation وconnector lifecycle/health حقيقيان لكل مزود. |
| Research / teams / projects | 33–35 | Research evidence policy وAgent teams/Project kernel موجودة. | missions multi-run تربط research/evidence/artifact وتبقي budget/permission قابلة للمراجعة. |
| Collaboration / marketplace / SDK / CLI / API | 36–40 | marketplace وCLI داخل الطرفية وconnectors موجودة جزئياً. | لا team sharing أو Marketplace عام أو SDK/CLI/API مستقل قبل identity/backend وأدوار/revocation. |
| Backup / updates / observability / privacy | 41–44 | deletion/privacy/diagnostics/telemetry consent موجودة محلياً. | encrypted backup/restore/migration وsigned update/rollback وtelemetry opt-in مع backend. |
| Accessibility / i18n / UX / commercial / release | 45–50 | العربية/الإنجليزية والإسبانية/الصينية وتحديثات UI موجودة؛ أدلة الجهاز وCI الخارجي ما زالت منفصلة. | TalkBack/RTL/IME/large-text على أجهزة، ثم acceptance scenarios قبل أي إعلان تجاري. |

## تسلسل الأولوية المعتمد

### P0: جعل الوكيل قابلاً للاستمرار والتحكم

تغلق هذه الحزمة: Mission hierarchy فوق DurableTask، replay وartifacts، Project-owned context، Approval/Trust مركزية، واستمرارية العملية/الجهاز. لا تبدأ Automation event-driven أو Device Mesh قبل أن يكون لكل عمل `owner` و`project` و`permission` و`audit` و`recovery`.

### P1: بيئة عمل المطوّر والكمبيوتر الشخصي

تغلق هذه الحزمة: File/Code Editor حول ملفات مساحة العمل الخاصة، diff/history/rollback، Terminal 2.0 بما تسمح به منصة Android، Browser Agent بحواجز أخذ التحكم البشري، ثم Desktop local model runtime. **VNC ليس بديلاً** عن هذه الطبقات وليس مرحلة مبكرة.

### P2: قابلية التوسع والمنتج الخارجي

تغلق هذه الحزمة: Connector SDK وSkill simulation وMarketplace الموقّع وCLI مستقل وAPI/Webhooks وBackup/Update center وCollaboration. تتطلب الهوية والخلفية وsecrets وrevocation، ولذلك لا تُنفذ كواجهات قبل تحقيق شروطها.

## الدفعة الحالية: استمرارية إيقاف المنسق

عالجت الدفعة الحالية عيباً مباشراً في مسار Agent OS: كان `ProductionAgentOrchestrator.cancelAll()` يلغي scope دائمًا؛ وبذلك قد يمنع أي خطة لاحقة من العمل. الآن تلتقط كل خطة scope خاصاً بها، ويلغي `cancelAll()` scope الحالي فقط ثم يجهز scope جديداً للخطط اللاحقة. يثبت اختبار JVM المضاف ودليل النواة هذا القصد؛ يبقى تشغيل اختبار JVM الكامل معلّقاً في هذه الجلسة بسبب metaspace في Gradle.

## سيناريوهات القبول الحاكمة

| السيناريو | شرط النجاح المحلي | دليل خارجي متبقٍ |
|---|---|---|
| Developer | Project → files → plan → terminal → diff → artifact يمر عبر ownership/approval/audit. | جهاز Android وGit credential ومشروع حقيقي. |
| Research | sources → scoped retrieval → citation/evidence → report artifact. | مزود بحث/متصفح ومصادر حقيقية. |
| Automation | trigger → durable task → approval → result/recovery. | WorkManager على جهاز وإشعار/شرط خارجي. |
| Continuity | المهمة تحفظ progress/artifact metadata ولا تسرّب المحتوى أثناء sync opt-in. | جهازان وهوية/خدمة sync. |
| Offline | local model/files/memory/terminal/task تعمل من دون route سحابي. | جهاز Android فعلي ونموذج GGUF محمل. |

## قرارات منع التشتت

لا تبدأ Billing أو Social أو VNC أو عشرات الموصلات أو متجر عام أو توليد وسائط مستقل قبل P0/P1. تعد الصور/الصوت/الفيديو **capabilities** داخل Model OS وArtifact OS لا شاشات شكلية مستقلة.

## مراجع التصميم

توضح [ملاحظات المراجع](AGENT_OS_REFERENCE_NOTES.md) ما يمكن الاستلهام منه موثقاً من مساحة Open WebUI ووثائق Claude Code، مع عدم استخدام تفاصيل Manus التي لم تكن صفحة مصدرها متاحة أثناء المراجعة.
