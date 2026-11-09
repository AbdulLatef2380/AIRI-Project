# إغلاق G-5 وG-6 وG-7 — 2026-11-10

## النتيجة التنفيذية

تم إغلاق العقود الجزئية في G-5 وG-6 وG-7 على نسخة العمل الحالية من `main` دون إدخال fallback صامت أو تخزين أسرار. التغيير حافظ على العقود السابقة حيث يلزم، وأضاف حدودًا صريحة قابلة للاختبار.

## G-5 — Skill lifecycle وexecution snapshot

كانت عدة طبقات تنشئ `SkillRegistry` و`CustomSkillRepository` منفصلين، ما يسمح بأن تقرأ واجهة الإعدادات نسخة مختلفة عن نسخة AgentLoop أو ToolDispatcher. أصبح المصنع المشترك هو المسار الإنتاجي لجميع هذه الطبقات، ويستخدم `applicationContext` لتجنب تسريب Activity context.

أضيفت نسخة monotonic إلى repository وإلى تفضيلات enablement. كل save/delete أو تغيير enablement يرفع النسخة، بينما `snapshotForExecution()` يعيد `SkillCatalogSnapshot` immutable منطقيًا يحتوي version وقائمة المهارات ومعرّفاتها. يستخدم ChatViewModel snapshot واحدًا عند بناء schema للطلب، ويمرره إلى `SkillToolBridge`، فلا تتغير قائمة schemas بسبب تعديل lifecycle في منتصف admission.

العزل يشمل أيضًا شاشات Skill Manager وSkill Details وSkill Builder، و`UnifiedCognitiveLoop` و`ToolExecutor` و`ToolDispatcher` و`SkillService` و`PromptService`. الاستثناءان الوحيدان لإنشاء الكائنات مباشرة هما constructors داخل المصنع المشترك نفسه.

## G-6 — إزالة تكرار capability prompt

أضيفت `PromptCapabilityBlockPolicy` كسياسة pure تمنع إدخال الوصف narrative عندما يكون structured `AVAILABLE TOOLS` موجودًا، أو عندما يكون الوصف نفسه قد أضيف سابقًا. وربطت السياسة بمسار `PromptService` الفعلي بدل أن تبقى اختبارًا منفصلًا.

يظل AgentLoop مصدر الحقيقة لمخططات الأدوات عند `hasAgentTools=true`، بينما يظل الوصف narrative متاحًا للمسار العادي الذي لا يملك structured tools. هذا يمنع تكرار schema والوصف ويقلل استهلاك السياق دون حذف capability مطلوبة بصمت.

## G-7 — Model-facing repair fail-closed

أضيف `ModelFacingRepairContract` بقرارات typed: إصلاح arguments، إعادة محاولة read transient، شرح auth أو permission أو عدم الاتصال، إيقاف write ذات النتيجة غير المؤكدة، أو الإنهاء النهائي. القرار يعتمد على `ToolErrorCodes` و`ToolSchema.SideEffectPolicy` ووجود idempotency key، وليس على نص الخطأ فقط.

يمرر AgentLoop النص الأصلي المنقح إلى repair، ويضع حدًا صريحًا مقداره محاولتان. invalid arguments يمكن إصلاحها، وقراءة network/timeout يمكن إعادة محاولتها، بينما الكتابة ذات outcome غير مؤكد لا تعاد تلقائيًا بلا idempotency key. لا يضع repair raw error message داخل prompt؛ تبقى التفاصيل خارج model-facing contract.

## الاختبارات والبوابات

نجحت البوابات الساكنة التالية:

- `git diff --check`
- `python3 tools/security_scan.py` — `status: PASS` ولا توجد `secret_findings`
- `python3 tools/verify_core_changes.py` — `summary: 100/100 checks passed`
- فحص call sites: لا توجد إنشاءات إنتاجية مباشرة خارج factories المشتركة.
- تحقق وجود الملفات والعقود والاختبارات الجديدة.

تعذر تشغيل Gradle focused tests في هذه البيئة لأن Android SDK غير موجود، ورسالة Gradle كانت: `SDK location not found` مع غياب `ANDROID_HOME` و`ANDROID_SDK_ROOT`. لذلك لا تُسجل نتيجة unit-test كنجاح زائف. يجب تشغيل الأمر التالي في CI أو جهاز يحوي SDK:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.airi.assistant.ai.skills.SkillCatalogSnapshotTest' \
  --tests 'com.airi.assistant.domain.prompt.PromptCapabilityBlockPolicyTest' \
  --tests 'com.airi.assistant.ai.agent.ModelFacingRepairContractTest' \
  --tests 'com.airi.assistant.ai.agent.SelfHealingExecutorTest' \
  --no-daemon --max-workers=1 --console=plain
```

## حدود متبقية معلنة

الـsnapshot يثبت قائمة schemas عند admission. مسار invocation يعيد التحقق من registry وauthorization لحظة التنفيذ، وهو مقصود كدفاع lifecycle إضافي وليس bypass. لم تُضف محاولة جديدة للكتابات الخارجية؛ أي retry idempotent لكتابة ما زال يتطلب أن يكون connector قد أعلن المفتاح والسياسة صراحة.
