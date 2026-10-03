# مصفوفة التكامل النهائي لمسار AIRI

## النطاق

تتحقق هذه المرحلة من أن النموذج المحلي والسحابي يستخدمان مسار الأدوات الموحد نفسه، وأن المهارات والموصلات والذاكرة والمهام المجدولة لا تتوقف عند طبقة الإعلان أو عند نوع النموذج.

## نتيجة التدقيق الساكن

تم تشغيل:

```bash
python3 tools/final_runtime_integration_audit.py
```

والنتيجة: **PASS**.

| المسار | نتيجة العقد |
|---|---:|
| تجميع `RuntimeToolCatalog` عند حدود المحادثة | PASS |
| تمرير الأدوات إلى `AgentLoop` | PASS |
| تفعيل `requiresToolCalling` عندما توجد أدوات | PASS |
| النموذج المحلي والسحابي عبر `HybridOrchestrator` | PASS |
| تمرير `targetRequest` إلى كل backend | PASS |
| fallback بين السحابي والمحلي | PASS |
| المهارات عبر `SkillToolBridge` | PASS |
| الموصلات عبر `ConnectorToolBridge` | PASS |
| الموصل غير المتصل يبقى قابلاً للتشخيص | PASS |
| `current_time` و`memory_recall` | PASS |
| المهام الدائمة والمجدولة | PASS |
| اختبارات الذاكرة والنطاق | PASS |
| اختبارات دورة حياة الموصلات | PASS |

## ما تم تثبيته في الكود

1. `ChatViewModel` يبني كتالوجاً واحداً للأدوات قبل استدعاء النموذج.
2. الأدوات المطلوبة بسبب نية المستخدم لا تُحجب لمجرد أن السؤال قصير.
3. `AgentLoop` يرسل الأدوات نفسها إلى طبقة التنفيذ المحلية والسحابية عبر `ExecutionRequest`.
4. `HybridOrchestrator` يحافظ على سياق الطلب عند fallback ويعيد ربط هوية الطلب بالـ backend المحلي عند الحاجة.
5. المهارات والموصلات تمر عبر `ToolDispatcher` نفسه.
6. الموصلات غير الجاهزة لا تتحول إلى `tool_not_found`؛ بل تعيد كوداً مثل `not_connected` أو `auth_required`.
7. الذاكرة والمهام المجدولة تظل مرتبطة بالجلسة/المشروع/التنفيذ الدائم.

## اختبارات الوحدة الموجودة المرتبطة بالمسار

- `RuntimeToolCatalogTest`
- `ConnectorToolBridgeTest`
- `ConnectorRuntimeManagerTest`
- `MemoryScopeTransactionTest` — Android instrumentation
- `DurableTaskProductKernelTest`
- `ScheduledWorkerOutcomePolicyTest`
- `ToolResultContractTest`
- `ToolErrorPresentationPolicyTest`

## ما لا يمكن تشغيله داخل Sandbox الحالي

لا يحتوي Sandbox على Android SDK أو emulator، لذلك لم يمكن تنفيذ:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

ويظهر حالياً:

```text
SDK location not found
```

هذه ليست نتيجة فشل للكود، بل قيد في بيئة التنفيذ الحالية.

## بوابة CI

تمت إضافة المدقق إلى `.github/workflows/android_build.yml` قبل بناء Android. تسلسل التحقق في CI هو:

1. التدقيق الساكن النهائي.
2. محاكاة runtime.
3. فحص أداء النموذج المحلي.
4. فحص دورة حياة الموصلات.
5. فحوص الخصوصية والحالة.
6. بناء واختبار الوحدة.
7. Android instrumentation على emulator.

## سيناريوهات القبول على جهاز Android

1. **محلي + وقت:** سؤال «كم الوقت الآن؟» يجب أن يستخدم `current_time` ولا يعتمد على تخمين النموذج.
2. **سحابي + Gmail غير مربوط:** يجب أن يظهر «يجب ربط Google أولاً».
3. **بحث دون شبكة:** يجب أن يظهر «الاتصال بالإنترنت غير متاح» ولا يعاد فتح المتصفح تلقائياً بلا نهاية.
4. **ذاكرة:** سؤال استرجاع ذاكرة داخل جلسة صالحة يجب أن يصل إلى `memory_recall`، وبدون جلسة يجب أن يظهر سبب النطاق.
5. **موصل جاهز:** إجراء قراءة Google/GitHub يجب أن يمر من `ConnectorRuntimeManager`.
6. **موصل غير جاهز:** يجب أن يظهر `not_connected` أو `auth_required` للمستخدم برسالة عملية.
7. **Fallback:** عند فشل السحابي مع توفر المحلي، يجب إكمال الإجابة محلياً مع تسجيل انتقال backend.
8. **مهمة مجدولة:** يجب أن تُحفظ المهمة، تُنفذ عبر `ScheduledAgentWorker`، وتُسجل نتيجة `COMPLETED` أو `RETRYING` أو `FAILED` دون فقدان هوية المشروع والتنفيذ.

## الخلاصة

من ناحية بنية المصدر والعقود، أصبح المسار موحداً من النموذج إلى الأدوات ثم التنفيذ والنتيجة والواجهة. المتبقي هو تشغيل سيناريوهات القبول على Android CI أو جهاز فعلي للتأكد من سلوك SDK وWorkManager وRoom وموصلات Google في بيئة تشغيل حقيقية.
