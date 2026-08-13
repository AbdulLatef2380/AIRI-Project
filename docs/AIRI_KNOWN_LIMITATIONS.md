# القيود المعروفة قبل إطلاق AIRI

**آخر تحديث:** 13 أغسطس 2026
**حكم القبول الحالي:** **🔴 NOT READY**

> هذه القيود ليست وعوداً أو افتراضات. كل بند أدناه يصف دليلاً ناقصاً أو اعتماداً خارج بيئة التدقيق ويمنع التصنيف كنسخة جاهزة للنشر العام.

| المعرف | الأولوية | القيد | الحالة | الأثر | الإجراء المطلوب قبل النشر |
|---|---|---|---|---|---|
| RL-01 | P0 | AGP 8.2.2 غير موجود محلياً وDNS يمنع تنزيله. | BLOCKED_BY_ENVIRONMENT | لم يبدأ Kotlin أو JNI أو Lint أو الاختبارات. | تشغيل CI/Android Studio متصل ثم حفظ نتائج Debug وRelease وLint. |
| RL-02 | P0 | لا توقيع Release في بيئة التدقيق. | NOT_VERIFIED | لا يمكن توزيع APK/AAB موثوق. | حقن keystore في CI عبر secrets والتحقق من التوقيع. |
| RL-03 | P0 | لا APK/AAB ناتج للفحص. | BLOCKED_BY_ENVIRONMENT | لا فحص حجم أو ABI أو `.so` أو R8 أو symbols. | بناء `assembleRelease` و`bundleRelease` وفحص الناتج. |
| RL-04 | P0 | Room في الإصدار 6 لكن لا اختبار ترقية كامل أو schemas مصدرة. | NOT_RUNTIME_VERIFIED | خطر فقد أو تلف البيانات أثناء الترقية غير مقاس. | إضافة fixtures وترحيل 1→6 وتثبيت جديد واسترداد. |
| RL-05 | P0 | SQLCipher مؤجل افتراضياً. | PASS_WITH_LIMITATION | قاعدة البيانات ليست مشفرة عبر SQLCipher في هذا الإصدار. | لا تفعّل التشفير حتى يمر اختبار plaintext→encrypted واستعادة فشل. |
| RL-06 | P0 | لا رحلة جهاز للحوار والبث والإلغاء والمرفقات. | NOT_RUNTIME_VERIFIED | لا يمكن إثبات قابلية الاستخدام الأساسية. | تشغيل سيناريوهات chat/Stop/retry/attachment/restart على جهاز. |
| RL-07 | P1 | موفرات السحابة وFirebase وOAuth لا تملك credentials أو backend متاحاً هنا. | NOT_RUNTIME_VERIFIED | لا دليل اتصال أو callback أو فشل آمن أو حماية token. | اختبار تكامل مع حسابات اختبار ومراقبة logs. |
| RL-08 | P1 | الصوت المحلي والحَي لم يختبرا على جهاز. | NOT_RUNTIME_VERIFIED | لا دليل Vosk أو TTS أو wake word أو audio-focus. | اختبار permission ورفضه وBluetooth والمقاطعة والعربية والإنجليزية. |
| RL-09 | P1 | WorkManager لم يختبر في Doze أو reboot أو timezone. | NOT_RUNTIME_VERIFIED | لا دليل جدولة دائمة أو notification. | اختبار one-time/periodic/cancel/retry/Doze/reboot. |
| RL-10 | P1 | لا قياس للأداء أو التخزين أو جهاز ضعيف. | NOT_RUNTIME_VERIFIED | لا حدود RAM أو startup أو jank أو cache growth. | قياس cold/warm startup وRAM وCPU والتخزين على أجهزة ممثلة. |
| RL-11 | P1 | TalkBack وfont scale وfocus order وRTL بصرياً غير مختبرة. | NOT_RUNTIME_VERIFIED | لا يمكن اعتماد الإتاحة أو تجربة العربية من المصدر فقط. | مراجعة يدوية ولقطات API 26–34 وسمة فاتحة/داكنة. |
| RL-12 | P2 | الإسبانية والصينية تتضمنان احتياطيات إنجليزية. | PASS_WITH_LIMITATION | التماثل موجود، لكن الترجمة ليست مكتملة لغوياً. | ترجمة بشرية/مراجعة أصلية لكل الاحتياطيات. |
| RL-13 | P2 | تثبيت شهادات LLM مؤجل. | PASS_WITH_LIMITATION | TLS النظامي نشط؛ لا تثبيت SPKI إضافي. | لا تفعل pinning حتى التحقق المباشر ودورة تغيير الشهادة. |
| RL-14 | P2 | API السوق وMarketplace backend غير متحققين. | NOT_RUNTIME_VERIFIED | لا يمكن تسويق اكتشاف/نشر مهارات كسيناريو جاهز. | تشغيل backend موثق واختبار browse/install/publish/failure. |

## ملاحظات الإصلاحات الوقائية في التدقيق

أُزيل موصل MCP التجريبي الذي كان ينجح من دون خادم، وحُذف منفذ shell غير المستخدم، وحُذف تقرير مصدر قديم يدعي اكتمالاً وبناءً لا دليل عليه. كما أزيلت certificate pins الموصوفة سابقاً كـ placeholders من Network Security Config، وأصبح العميل يعتمد تحقق TLS القياسي إلى أن تتوفر عملية pinning موثقة ومختبرة. هذه الإصلاحات لا تلغي القيود أعلاه.

## إعادة فتح قرار الإطلاق

يمكن إعادة بدء قبول الإنتاج فقط بعد إرفاق نتائج قابلة لإعادة التشغيل للأوامر التالية وحفظ APK/AAB الناتجين:

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:lintDebug
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
./gradlew :app:compileReleaseKotlin :app:assembleRelease :app:bundleRelease
```

بعد ذلك يلزم تنفيذ إجراءات RL-04 وRL-06 وRL-07 وRL-08 وRL-09 وRL-10 وRL-11 قبل أي تصنيف أخضر.
