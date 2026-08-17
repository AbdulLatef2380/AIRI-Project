# مصفوفة قبول التشغيل للنماذج ومساحة العمل

**الأساس:** فرع `architecture-refactor` فوق `73828b26473fa68caeddbe3541c26e948492abfe` مع إصلاحات محلية قيد الحفظ.  
**قاعدة القبول:** لا يُسجل نجاح تشغيلي على Android أو مع موفر حقيقي إلا بدليل قابل للتكرار على جهاز أو بيئة تكامل. اختبارات JVM أدلة قوية على قواعد التوجيه، لكنها لا تعادل اختبار مزود فعلي.

| المعرف | الرحلة | الدليل الآلي أو المصدر الحالي | مستوى الإثبات | حالة القبول الحالية | معيار الإغلاق النهائي |
|---|---|---|---|---|---|
| MR-01 | `LOCAL_ONLY` مع نموذج محلي محمّل | `RoutingPolicyTest.localOnlyAlwaysSelectsLocal` | JVM | PASS_WITH_LIMITATION | استجابة محلية على جهاز من دون طلب HTTP. |
| MR-02 | `LOCAL_ONLY` بلا نموذج محلي | `LocalLlamaBackend.isAvailable` | SOURCE | NOT_RUNTIME_VERIFIED | خطأ `not_loaded` مرئي، بلا spinner أو رد زائف. |
| MR-03 | `CLOUD_ONLY` متصل ومزود مهيأ | `cloudOnlyOnlineSelectsCloudThenLocalFallback` و`CloudBackend` | JVM + SOURCE | PASS_WITH_LIMITATION | بث حقيقي من الموفر الصحيح مع accounting واكتمال واحد. |
| MR-04 | `CLOUD_ONLY` غير متصل وfallback مفعّل | `cloudOnlyOfflineWithFallbackSelectsLocalWithoutAttemptingCloud` | JVM | PASS_WITH_LIMITATION | انتقال واحد إلى محلي مع سبب ظاهر للمستخدم. |
| MR-05 | `CLOUD_ONLY` غير متصل وfallback معطّل | `cloudOnlyOfflineWithoutFallbackKeepsCloudSelectionForExplicitErrorHandling` و`HybridOrchestrator` | JVM + SOURCE | PASS_WITH_LIMITATION | رسالة عدم اتصال محددة، بلا `Unknown error` أو محاولة cloud. |
| MR-06 | `HYBRID` متصل وتبديل المزود | `hybridAnalyticalRequestPrefersCloudWhenOnline`، و`CloudAdapterFactory`، و`SecureApiKeyStore` | JVM + SOURCE | PASS_WITH_LIMITATION | الطلب التالي يستخدم المزود/النموذج الجديد فقط ولا يرث اعتماداً أو endpoint سابقاً. |
| MR-07 | انتقال الشبكة أثناء البث | حواجز generation/callback في `HybridOrchestrator` | SOURCE | NOT_RUNTIME_VERIFIED | توقف آمن أو fallback قبل أول token فقط؛ لا استجابتان مدمجتان ولا رد متأخر بعد الإلغاء. |
| MR-08 | إلغاء أثناء الاستجابة أو الأداة | المتحقق الثابت 25/25 وحواجز `ChatViewModel`/`HybridOrchestrator` | STATIC + SOURCE | PASS_WITH_LIMITATION | لا token أو final response أو كتابة حالة بعد الإلغاء على جهاز. |
| MR-09 | حفظ التفضيلات والتبديل بعد restart | `ExecModePreferences` و`SecureApiKeyStore` | SOURCE | NOT_RUNTIME_VERIFIED | يبقى الوضع والمزود عند توفر المخزن المشفر؛ تحذير صريح عند fallback الذاكرة. |
| MR-10 | مساحة العمل/المشاريع عبر restart | `WorkspaceRuntime` يحفظ الجلسات ويعيد artifacts | SOURCE | NOT_RUNTIME_VERIFIED | تبقى العناصر والنتائج أو تظهر حالة تعافٍ صريحة؛ لا مشروع فارغ يوحي بالنجاح. |
| MR-11 | اختبار endpoint مخصص | `RemoteModelExecutorTest`: HTTP محلي 200 و401 | JVM | PASS_WITH_LIMITATION | اختبار endpoint حقيقي لـ 200 و401 و429 وtimeout وTLS من Android. |

## سجل نتائج التحقق المحلي

| البوابة | النتيجة |
|---|---|
| `:app:testDebugUnitTest` | 15 tests، 0 failures، 0 errors، 0 skipped. |
| `:app:assembleDebug` | PASS؛ APK Debug ومكتبة `libairi_native.so` موجودان. |
| `:app:lintDebug` | PASS بلا أخطاء. |
| `tools/verify_core_changes.py` | PASS، 25/25. |

## حدود التنفيذ الحالية

لا توجد في بيئة التحقق مفاتيح موفر مفعّلة أو جهاز Android أو محاكي قادر على اختبار الصوت والشبكة ودورة حياة العملية. لذلك لا يمكن اعتماد MR-01 إلى MR-11 كتجارب إنتاجية كاملة رغم اكتمال أدلة JVM والمصدر في الصفوف المبينة. يجب تنفيذ معايير الإغلاق على جهاز فعلي أو محاكي مع حسابات اختبار قبل تغيير حالات `PASS_WITH_LIMITATION` أو `NOT_RUNTIME_VERIFIED` إلى نجاح تشغيلي كامل.
