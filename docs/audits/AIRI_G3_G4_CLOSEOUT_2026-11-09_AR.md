# إغلاق G-3 وG-4 — 2026-11-09

## القرار

أُغلقت فجوتا `G-3` و`G-4` وظيفياً في المصدر المحلي على فرع `main` الحالي (`6af6e789`) مع اختبارات عقدية جديدة. لا أعتبر Android CI ناجحاً محلياً لأن بيئة Sandbox لا تحتوي Android SDK؛ هذه البوابة يجب تشغيلها على runner CI المجهز.

## G-3 — توحيد connector authorization

أضيف `ConnectorAuthorizationState` و`ConnectorAuthorizationProjection`. القرار الآن يجمع في projection واحدة:

- حالة هوية الاتصال: `SIGNED_OUT` أو `SIGNED_IN`.
- OAuth/provider scopes المطلوبة والممنوحة.
- surface access profile الفعلي.
- الحالة النهائية: profile مطلوب، identity مطلوبة، scope/health مطلوب، authorized أو blocked.
- `reasonCode` قابل للعرض والتشخيص، دون token أو access/refresh secret أو PII.

`ConnectorToolBridge.authorizationForTool()` يعيد projection نفسها للـUI/inventory، كما يضيف schema وصفاً آمناً لحالة authorization حتى لا يرى النموذج connector كأنه غير موجود. التنفيذ يعيد الرفض قبل runtime إذا لم يسمح profile بالفعل، ولا يخلط نجاح identity مع جاهزية surface.

الاختبار `ConnectorAuthorizationStateTest` يثبت أن grant profile وحده لا يتجاوز identity المفقودة، وأن سبب الرفض دقيق ولا يحتوي أسراراً.

## G-4 — typed arguments

أضيف `TypedArgumentContract` كـsingle parser/validator، و`TypedArgumentAdapters` لتحويل:

- `ToolSchema.Param`
- `SkillParamDef`
- `ConnectorAgentParameter`

إلى العقد نفسه. يغطي العقد `string`, `int`, `number`, `boolean`, `object`, `list`, `url`, `date`, و`enum`، إضافة إلى required/default/nullable، الحدود، nested properties/items، unknown keys، ورسائل `code + message + repairHint`.

البوابات التنفيذية أصبحت:

1. `ToolDispatcher` يتحقق من المخطط قبل دخول أي handler.
2. `SkillToolBridge` يتحقق قبل `skill.execute()` ويعيد `invalid_argument` قابلاً للإصلاح.
3. `ConnectorToolBridge` يتحقق قبل بناء `ConnectorInput`، ولا يمرر إلا القيم canonical بعد validation.
4. schemas تعرض القيود الجديدة بدلاً من تحويل كل parameter إلى string بلا contract.

الاختبار `TypedArgumentContractTest` يغطي scalar/default/nullable، object/list، unknown/missing/wrong type، ورسائل الإصلاح. تبقى `StrictArgumentValidator` القديمة للتوافق مع `ToolSpec` القديم، أما مسار AgentLoop/skill/connector الموحد فيستخدم العقد الجديد.

## الأدلة والبوابات

- `python3 tools/security_scan.py`: PASS، ولا توجد secret findings.
- `python3 tools/verify_core_changes.py`: PASS، `100/100 checks passed`.
- `git diff --check`: PASS.
- `./gradlew ...`: لم يبدأ بسبب غياب `ANDROID_HOME` وAndroid SDK (`SDK location not found`)، وليس بسبب compiler diagnostic من التغييرات.

## المتبقي قبل اعتبار الإغلاق منشوراً

تشغيل اختبارات JVM وAndroid CI على runner يملك SDK، ثم مراجعة نتائج CI. لا ينبغي دفع أو إعلان نجاح Android build اعتماداً على static gates وحدها.
