# أدلة القبول المحلية — 2026-08-23

**النطاق:** أدلة مصدر وGradle/JVM محلية لفرع `cp-foundation`. لا تمثل هذه الصفحة جهاز Android فعلياً أو تسجيل دخول مزود أو Firebase/OAuth إنتاجياً أو نشر متجر. لذلك تستخدم الحالات **`LOCAL_VERIFIED`** و**`RUNTIME_VERIFICATION_PENDING`** بدلاً من ادعاء قبول إنتاجي.

## نتيجة البوابة المحلية

| بوابة | النتيجة | ما يثبته الدليل | ما لا يثبته |
|---|---|---|---|
| `:app:testDebugUnitTest` لمسارات القبول | `LOCAL_VERIFIED` | سياسة الملحن، الخصوصية، أخطاء cloud، routing، فرق الوكلاء، الأتمتة، device intents والبحث اجتازت JVM | تكامل مزود، WorkManager/Doze فعلي، أو UI على جهاز |
| `:app:assembleDebug` | `LOCAL_VERIFIED` | يمكن بناء APK debug من المصدر الحالي | تثبيت/تشغيل APK على جهاز أو توقيع release |
| `:app:lintDebug` | `LOCAL_VERIFIED` | Android lint أنجز بنجاح | فهم بصري أو اختبار TalkBack/IME |
| `tools/security_scan.py` | `LOCAL_VERIFIED` | لا cleartext override، FileProvider غير مُصدَّر، المرفقات ضمن filesDir والحدود المصدرية سليمة | اختراق فعلي أو مراجعة مفاتيح/بيئة إنتاج |
| `tools/verify_core_changes.py` | `LOCAL_VERIFIED` | 45/45 حدود نواة، خصوصية، fallback، اتصال، diagnostics وترجمة سليمة | رحلة مستخدم كاملة على جهاز أو provider خارجي |
| `scripts/airi_localization_health.py --strict` | `LOCAL_VERIFIED` | `likely_untranslated_values=0` في الإسبانية والصينية وفق قواعد الفحص | مراجعة بشرية للأسلوب والسياق وRTL/TalkBack |

## سيناريوهات القبول المنفذة

| الرحلة | الاختبار أو الحارس | النتيجة | مستوى الدليل |
|---|---|---|---|
| اختيار `/skill` أو `@knowledge` يحافظ على نص المهمة | `ComposerDirectivePolicyTest` | النص الإنجليزي والعربي والحالة الفارغة مغطاة | `LOCAL_VERIFIED` |
| الطلب السحابي المتوازن لا يكشف prompt/system/history أو معرف الجهاز | `PrivacyGuardTest` و`Cloud fallback privacy boundary` | تنقيح كل النصوص المسلسلة وفحص fallback | `LOCAL_VERIFIED` |
| response body للمزود لا يصل diagnostics أو واجهة الخطأ | `CloudErrorMapperTest` و`Cloud error response redaction` | التصنيف يبقى، النص الخام لا يخرج | `LOCAL_VERIFIED` |
| retry لا يسجل رسالة مزود خامة | `Retry diagnostics redaction` | السجل يحمل `errorType` ومدة backoff فقط | `LOCAL_VERIFIED` |
| انقطاع أو captive portal لا يعلن cloud online قبل validation | `Validated connectivity for cloud routing` | `ConnectivityMonitor` يتطلب `NET_CAPABILITY_VALIDATED` | `SOURCE_VERIFIED`؛ اختبار شبكة جهاز مطلوب |
| router/team policy تختار ضمن capability/privacy/budget | `RoutingPolicyTest` و`AgentTeamPolicyTest` | سياسة JVM اجتازت | `LOCAL_VERIFIED`؛ usage/provider حقيقيان مطلوبان |
| وظيفة مجدولة تتصل بمهمة دائمة وتضبط input | `ScheduledJobDurableLinkTest` و`ScheduledJobInputPolicyTest` | عقود scheduling اجتازت | `LOCAL_VERIFIED`؛ Doze/OEM/device مطلوب |
| device URL/settings والأبحاث الخارجية محكومة | `DeviceActionPolicyTest` و`SearchSourcePolicyTest` | takeover/block وpublic HTTP(S) مغطاة | `LOCAL_VERIFIED`؛ تطبيقات/متصفحات حقيقية مطلوبة |

## الأمر القابل لإعادة التشغيل

```bash
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Pkotlin.compiler.execution.strategy=in-process' \
  :app:testDebugUnitTest \
  --tests 'com.airi.assistant.ui.composer.ComposerDirectivePolicyTest' \
  --tests 'com.airi.assistant.execution.privacy.PrivacyGuardTest' \
  --tests 'com.airi.assistant.execution.cloud.CloudErrorMapperTest' \
  --tests 'com.airi.assistant.execution.router.RoutingPolicyTest' \
  --tests 'com.airi.assistant.agent.orchestrator.AgentTeamPolicyTest' \
  --tests 'com.airi.assistant.agent.scheduler.ScheduledJobDurableLinkTest' \
  --tests 'com.airi.assistant.agent.scheduler.ScheduledJobInputPolicyTest' \
  --tests 'com.airi.assistant.connector.local.DeviceActionPolicyTest' \
  --tests 'com.airi.assistant.tools.execution.SearchSourcePolicyTest'

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Pkotlin.compiler.execution.strategy=in-process' \
  :app:assembleDebug :app:lintDebug

python3 tools/security_scan.py
python3 tools/verify_core_changes.py
python3 scripts/airi_localization_health.py --strict
```

## التحقق الخارجي المعلّق

| بند | سبب عدم ادعاء الإغلاق | دليل الإغلاق المطلوب |
|---|---|---|
| تثبيت Android وTalkBack/RTL/IME | لا يتوفر جهاز Android فعلي في هذه الجلسة | APK مثبت، لقطات/تسجيل QA، ملفات TalkBack وIME وrotation |
| local model وcloud handoff | لا يوجد نموذج محلي وcredentials مزود موثوقة للاختبار | سجلات تشغيل من جهاز مع قطع الشبكة واستعادتها وموافقة المستخدم |
| الصوت والكاميرا والملفات | يتطلب microhone/audio-focus/picker وعتاد فعلي | سيناريوهات QA على ABI وأجهزة مختلفة |
| Firestore/OAuth/connectors | لا يوجد backend إنتاج أو حساب اختبار مخول | smoke test ومراجعة scopes وقواعد نشر |
| CI الحالي وpush | Git remote قبل النشر رفض المصادقة؛ آخر remote معروف عند `60f98388` | استعادة مصادقة GitHub ثم push ومراجعة CI للـSHA المنشور |

> هذه النتائج تثبت سلامة محلية قابلة لإعادة التشغيل، ولا تغير حقيقة أن قبول الإصدار النهائي يحتاج أدلة Android وprovider وCI المنشور بعد مزامنة الفرع.
