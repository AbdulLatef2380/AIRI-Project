# تقرير القبول النهائي المحلي — AIRI Android

## حالة التقرير وحدوده

يسجل هذا التقرير لقطة قبول محلية لفرع **`cp-foundation`** حتى revision `077a60cc` بتاريخ 2026-08-23. لا يغير التقرير فرع `architecture-refactor`، ولا يدمج معه أي سجل. كانت آخر مقارنة آمنة مع `origin/cp-foundation` عند `60f98388`، بينما تضم الشجرة المحلية في هذه اللقطة **ثمانية التزامات أمام البعيد** بسبب رفض المصادقة البعيدة لعملية Git push؛ لذلك لا ينسب التقرير أي CI منشور إلى هذه المراجعات المحلية.

> **القرار:** `LOCAL_ACCEPTANCE_COMPLETE` لمسارات المصدر وGradle/JVM المحددة أدناه. الحالة ليست دليلاً على توقيع release أو اختبار جهاز Android حقيقي أو مزود سحابي أو نشر إنتاجي. هذه العناصر مصنفة صراحةً `RUNTIME_VERIFICATION_PENDING` أو `EXTERNAL_VERIFICATION_REQUIRED`.

يعتمد التقرير مبدأ أن **القدرة لا تتجاوز دليلها**. فنجاح اختبار وحدة أو lint يثبت عقداً محلياً، لكنه لا يثبت صوتاً أو شبكة أو credential أو واجهة فعلية على هاتف.

## بوابات القبول المنفذة

| البوابة | الحالة | الدليل المحلي | الحد الصريح |
|---|---|---|---|
| قبول JVM لمسارات المنتج | `LOCAL_VERIFIED` | `ComposerDirectivePolicyTest` و`PrivacyGuardTest` و`CloudErrorMapperTest` و`RoutingPolicyTest` و`AgentTeamPolicyTest` واختبارات scheduler/device/search نجحت | لا تثبت مزوداً أو جهازاً أو WorkManager/Doze فعلياً |
| تجميع Android | `LOCAL_VERIFIED` | `:app:compileDebugKotlin` و`:app:assembleDebug` نجحا | لا يثبت التثبيت أو حرارية/ABI أو توقيع release |
| Android lint | `LOCAL_VERIFIED` | `:app:lintDebug` نجح | لا يثبت UX بصرياً أو TalkBack/IME |
| حدود الأمن | `LOCAL_VERIFIED` | `tools/security_scan.py` نجح: لا cleartext override، FileProvider غير مُصدّر، attachments خاصة ومحدودة | لا يحل محل اختبار اختراق أو أسرار production |
| تدقيق النواة | `LOCAL_VERIFIED` | `tools/verify_core_changes.py` نجح بنتيجة **45/45** | حارس مصدر، وليس E2E على مزود أو جهاز |
| صحة الترجمة | `LOCAL_VERIFIED` | `scripts/airi_localization_health.py --strict` أعاد `likely_untranslated_values=0` | مراجعة بشرية وسياق RTL/LTR وTalkBack ما زالت مطلوبة |

تفاصيل الأوامر والسيناريوهات موجودة في [`LOCAL_ACCEPTANCE_EVIDENCE_2026-08-23.md`](product/LOCAL_ACCEPTANCE_EVIDENCE_2026-08-23.md).

## مصفوفة القدرات الحالية

| المجال | الحالة | الدليل | قيود الإغلاق |
|---|---|---|---|
| الملحن `/skill` و`@knowledge` | `IMPLEMENTATION_COMPLETE` | يحافظ `ComposerDirectivePolicy` على النص بعد اختيار directive؛ اختبارات عربية وإنجليزية وحالة فارغة | TalkBack/focus/IME ولقطات Android حقيقية |
| الإسبانية والصينية | `IMPLEMENTATION_COMPLETE` للفحص الآلي | أزيلت 126 قيمة مطابقة للإنجليزية لكل لغة؛ strict health يساوي صفر | مراجعة لغوية بشرية والسياق الثقافي |
| الخصوصية السحابية | `IMPLEMENTATION_COMPLETE` للمسار المحلي | `PrivacyGuard` ينقح prompt/system/history ومعرفات الجهاز، ويحرس كل cloud fallback | التقاط شبكة جهاز ومراجعة providers حقيقية |
| diagnostics | `IMPLEMENTATION_COMPLETE` للتنقيح المحلي | response body ورسائل retry الخام لا تدخل state/sجل diagnostics؛ 45/45 يدقق الحدود | export تشخيصي للمستخدم وtrace كامل لا يزالان جزئيين |
| offline routing | `IMPLEMENTATION_COMPLETE` لحارس المصدر | لا يعد `ConnectivityMonitor` cloud online قبل `NET_CAPABILITY_VALIDATED` | captive portal وhandoff على جهاز حقيقي |
| scheduled agent jobs | `IMPLEMENTATION_COMPLETE` للعقد المحلي | وظائف agent ترتبط بـDurableTask/Run/Step ويغطيها JVM | Doze/OEM والتنبيهات والتشغيل الفعلي |
| device/search safeguards | `IMPLEMENTATION_COMPLETE` للسياسات | device discovery فقط وtakeover/block، والبحث public HTTP(S) read-only | تطبيقات/متصفحات/device targets حقيقية |
| model router وفرق الوكلاء | `IMPLEMENTATION_COMPLETE` للسياسة | capability/privacy/budget وteam isolation مغطاة باختبارات | provider usage accounting وlocal/cloud runtime |

## سجل الالتزامات المحلية بعد آخر remote sync

| الالتزام | المضمون |
|---|---|
| `31d06383` | حفظ نص الملحن وترجمة موارد الإسبانية والصينية |
| `3e7f6e38` | semantics لاقتراحات الملحن لقارئ الشاشة |
| `0139a379` | تنقيح جميع الحقول النصية المتجهة إلى cloud ومعرفات الجهاز |
| `5aded955` | إنفاذ الحارس ذاته على cloud fallbacks |
| `78847f4b` | اشتراط شبكة Android المتحققة لتوجيه cloud |
| `0359d94e` | منع response body من diagnostics |
| `00b73e88` | منع نص الخطأ الخام من سجل retry |
| `077a60cc` | دليل قبول محلي قابل لإعادة التشغيل |

## قيود التحقق الخارجي ومالك الإغلاق

| العمل المتبقي | الحالة | المالك المقترح | دليل الإغلاق |
|---|---|---|---|
| مزامنة GitHub وتشغيل CI للمراجعات المحلية | `EXTERNAL_VERIFICATION_REQUIRED` | مالك المستودع | استعادة مصادقة GitHub، push آمن لـ`cp-foundation`، ثم CI للـSHA المنشور |
| تثبيت APK وفحص UX/RTL/TalkBack/IME | `RUNTIME_VERIFICATION_PENDING` | QA Android | تثبيت debug/release، تسجيل سيناريوهات عربية/إنجليزية، rotation وkeyboard/focus |
| local model وcloud fallback الحقيقيان | `RUNTIME_VERIFICATION_PENDING` | QA Android/Runtime | نموذج محلي ومفتاح test، انقطاع شبكة/captive portal/fallback مع سجل منقح |
| الصوت والكاميرا والملفات | `RUNTIME_VERIFICATION_PENDING` | QA Android | microphone/audio-focus/STT/TTS وpicker وcamera على أجهزة ABI مختلفة |
| Firestore/OAuth/الموصلات الإنتاجية | `EXTERNAL_VERIFICATION_REQUIRED` | مالك البنية السحابية | accounts اختبار، scopes، rules منشورة، وsmoke tests |
| توقيع AAB/APK وسياسة النشر | `EXTERNAL_VERIFICATION_REQUIRED` | مالك الإصدار/القانوني | signing material، artifacts، hashes، license review، store rollout |

## الخلاصة

أغلقت هذه اللقطة **القبول المحلي القابل لإعادة التشغيل** لطبقات الملحن والترجمة والخصوصية والتشخيص والتوجيه والأتمتة والسياسات المقيدة. لا توجد نتيجة محلية تُستخدم كبديل عن جهاز أو credential أو مزود أو CI منشور. بعد إعادة مزامنة `cp-foundation` وتشغيل CI، تبقى قائمة التحقق الخارجية أعلاه معيار الانتقال من قبول محلي إلى مرشح إصدار موزع.
