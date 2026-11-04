# تدقيق جذور مسار Skills / Connectors / Tools / Terminal

التاريخ: 2026-11-04
الفرع: `main`
النطاق: من التسجيل والتهيئة حتى `ChatViewModel → RuntimeToolCatalog → AgentLoop → ToolDispatcher`.

## النتيجة التنفيذية

المشكلة ليست غياب جسر واحد. المسار يحتوي على عدة حدود مستقلة، وبعضها لا يستخدم نفس تعريف الجاهزية أو نفس مصدر الحقيقة. لذلك قد تعرض AIRI قدرة في الجرد، أو تعرض schema للنموذج، ثم تفشل عند التنفيذ أو تحجبها طبقة لاحقة. هذا يفسر سلوك «لدي أدوات/مهارات/موصلات» ثم «لا أستطيع الوصول إليها».

## العيوب الجذرية المثبتة

1. **جاهزية المهارات لا تدخل عقد الكتالوج.** `SkillToolBridge.asToolSchemas()` يضيف حالة الجاهزية داخل `description` فقط، بينما `RuntimeToolCatalog.assemble()` يحول كل skill schema إلى `RuntimeToolContract.skill()` بحالة `AVAILABLE`. النتيجة: الكتالوج والـinventory يقولان إن المهارة متاحة حتى لو كان التنفيذ سيرفضها.

2. **مصدر العرض مختلف عن مصدر inventory.** schemas تستخدم `getRegisteredSkillsForRouting()`، أما `skillCapabilityInventoryEntries()` فتستخدم `getAvailableSkills()`. الأولى تعرض مهارات غير متصلة، والثانية تسجلها كغير موجودة runtime. لا توجد projection واحدة للحالة.

3. **المهارة المخصصة لا تملك حالة صلاحية مستقلة.** `CustomSkillAiriSkillAdapter` لا يعلن permissions/connectors/model/memory، لذلك يعتمد التنفيذ على endpoint فقط، بينما proposal manifest قد يحتوي permissions لا تصل إلى adapter بعد الحفظ.

4. **نجاح مصادقة OAuth لا يمنح سطح الوكيل تلقائياً.** `ConnectorAccessProfile.NOT_CONFIGURED` يرفض كل actions. بعض المسارات تنتهي عند `Google identity signed in` أو token/consent، لكن الإظهار يتطلب profile لكل `surfaceId` إضافة إلى provider scopes.

5. **Google يملك ثلاث حالات منفصلة لا تُعرض للوكيل كحالة موحدة:** الهوية، authorization scopes لكل surface، وaccess profile. `GoogleConnector.stateForAction()` يعتمد surface state، بينما بعض الجرد يعتمد connector aggregate state. هذا يخلق «مصادق لكن غير قابل للتنفيذ» بدون تفسير موحد.

6. **الـconnector catalog يضيف actions غير المتاحة ثم يخفيها من schemas.** هذا صحيح أمنياً، لكنه يجعل سؤال الجرد يرى عدداً من الأسطح غير المسجلة تنفيذياً، ولا توجد استجابة مضمونة تقول: registered / authorized / exposed / executable في رد المستخدم نفسه.

7. **تحديد المهارات العامة مربوط بعبارات ضيقة.** `skillsRequested` لا يصبح true إلا مع `connectorIds == "*"` واحتواء النص على `skill/مهار`. أسئلة مثل «ما الذي يمكنك فعله؟» أو «ما عندك؟» قد تطلب `*` لكن لا تُعامل كطلب skills عند فشل schema build.

8. **سؤال القدرة قد يمر إلى مسار إجابة بدل تنفيذ capability inventory إذا لم تطابق العبارة حرفياً.** detector يعتمد قائمة phrases؛ لا يوجد fallback دلالي أو token-set يلتقط الصيغ العربية الحرة، الاختصارات، والتهجئات مثل «الموصلات الخارجية» أو «وش تقدر تسوي».

9. **الطلبات التي تقول «استخدم terminal» لا تُعد دائماً `TERMINAL_EXECUTE`.** detector يشترط target مع فعل من قائمة terminal، و«هل تستطيع استخدام الطرفية؟» سؤال capability لا يضيف terminal capability، فيحصل النموذج على إجابة عامة لا اختبار فعلي للأداة.

10. **النموذج المحلي لا يملك tool calling native.** `LocalToolCallPromptContract` يرسل الأدوات كنص، ثم parser صارم ينتظر JSON محدداً. أي markdown أو شرح حول JSON يحتاج retry واحد فقط؛ النماذج الصغيرة غالباً تفشل هنا حتى مع وجود schema.

11. **لا يوجد repair دلالي عندما يرفض النموذج استخدام أداة موجودة.** AgentLoop لا يعيد الطلب إلى النموذج بصيغة «الأداة موجودة وهذه capability مطلوبة» إلا في malformed `tool_call`. لذلك الرد البارد/القصير ينجح شكلياً ولا ينفذ.

12. **التعريفات العامة للمهارات لا تحمل هوية المهارة في اسم الأداة.** `SkillToolBridge.toolDefToSchema()` ينتج `skill_<toolDef.name>` لا `skill_<skillId>_<toolDef.name>`. هذا يسمح بتصادم أسماء أدوات مهارات مختلفة ويجعل routing يعتمد أول match.

13. **اختلاف required/optional بين SkillParamDef وToolSchema.** تحويل schema يحافظ على required، لكن `SkillToolBridge.invoke()` يضع `input` تلقائياً ويحوّل كل args إلى String. لا يوجد تحويل typed موحد لـint/boolean/object؛ المهارات والموصلات قد تتلقى أنواعاً غير متوقعة.

14. **أدوات create/import skill نفسها لا تضمن دورة حياة فورية.** بعد الموافقة تُحفظ المهارة، لكن لا توجد invalidation/event مشتركة للـcatalog؛ UI يستخدم polling، وAgentLoop الحالي لا يعيد بناء الطلب الجاري. النتيجة الصحيحة هي «الدور التالي»، لكنها غير واضحة للنموذج والمستخدم.

15. **تعدد SkillRegistry/CustomSkillRepository يسبب اختلاف lifecycle.** `ChatViewModel`, `PromptService`, `SkillService`, وواجهات الإدارة تنشئ registries منفصلة. Preferences مشتركة، لكن repository/executor/cache ليست عقداً مشتركة، ولا توجد snapshot/version للطلب.

16. **التعريفات تُحقن أكثر من مرة في prompt.** `PromptService` قد يضيف skill description block، ثم ChatViewModel يضيف schemas، ثم AgentLoop يضيف tool instruction/block. على النماذج الصغيرة يؤدي ذلك إلى ضغط context وتراجع اتباع JSON.

17. **`AgentPermissionProfile` يعتمد على QueryType قبل capability resolution النهائي.** terminal لا يسمح به إلا `ACTION`، وبعض capability questions أو الأسئلة غير المصنفة تحمل intent صحيحاً لكن query type غير مناسب، فتختفي الأدوات قبل dispatch.

18. **الـterminal يعتمد على task-owned context رغم أن المستخدم يتوقع جلسة محادثة مباشرة.** هذا صحيح أمنياً، لكن رسالته ليست capability negotiation؛ إذا لم يكن `executionContextFactory` أو policy ready، يختفي tool بدلاً من عرض «متاح بعد إنشاء task context».

19. **اختبارات الوحدة تغطي الأجزاء المنفصلة ولا تغطي vertical slice الحقيقي.** توجد اختبارات للـcatalog والـbridge منفردة، لكن لا يوجد اختبار يثبت: OAuth/profile → schema → permission profile → prompt → parser → dispatcher → result للمهارة/connector/terminal.

## إصلاحات المرحلة التالية

- إنشاء runtime capability projection واحدة تحمل `registered/readiness/exposed/admitted/dispatcherRoute` لكل skill وconnector.
- تمرير readiness حقيقية للمهارات إلى `RuntimeToolCatalog` بدلاً من إخفائها في الوصف.
- جعل أسماء أدوات المهارات namespace-safe.
- إضافة classifier fallback لجرد القدرات ولطلبات «استخدم/هل تستطيع» مع tests عربية متنوعة.
- إضافة مسار model-facing repair عند جواب refusal أو عدم استدعاء أداة مطلوبة.
- توحيد typed argument conversion والتحقق بين skill/connector/tool.
- إضافة vertical integration tests على مسار ChatViewModel/AgentLoop باستخدام fakes، ثم تشغيل CI.
