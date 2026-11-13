# ADR: تنفيذ P2 للصوت realtime duplex

## القرار

تم اعتماد `RealtimeAudioTransport` كمالك وحيد لمسار الصوت السحابي ثنائي الاتجاه:

```text
AudioRecord (16 kHz PCM-16 mono)
    -> Pcm16Resampler عند الحاجة
    -> RealtimeVoiceProvider (Gemini Live / OpenAI Realtime)
    -> audioResponseFlow (24 kHz PCM-16 mono)
    -> AudioTrack
```

لا يجوز أن يعمل `VoiceManager` المحلي بالتوازي مع النقل السحابي؛ يختار `LiveVoiceService` المسار من `VoicePreferencesStore`، ويستخدم `SecureApiKeyStore` لقراءة المفتاح دون وضعه في SharedPreferences العادية أو السجل.

## ما نُفذ

- `RealtimeProviderFactory` يحول معرفات `LOCAL` و`GEMINI[_LIVE]` و`OPENAI[_REALTIME]`، ويغلق المسار محليًا عند القيمة المجهولة.
- `LiveVoiceService` لم يعد يجبر كل اختيار سحابي على `LocalVoicePipeline`.
- `requestListen` يمرر الجلسة السحابية إلى `startConfiguredRealtimeDuplex` ويمنع امتلاك `AudioRecord` مزدوجًا.
- مفاتيح Gemini وOpenAI تُقرأ من `SecureApiKeyStore`، مع رفض واضح عند غياب المفتاح وتحرير audio focus.
- `Pcm16Resampler` يدعم 16 kHz إلى معدل المزود، مع بقاء Gemini عند 16 kHz وOpenAI عند 24 kHz.
- `voiceCapability` لا يعلن النقل جاهزًا إلا عند اختيار مزود سحابي ووجود مفتاحه المشفر.
- أضيفت اختبارات عقد للمحول والمزودين واختبار يثبت أن realtime لا يتطلب نموذج Vosk المحلي.

## حدود الدليل

- فحص `git diff --check` ناجح.
- محاولة `:app:testDebugUnitTest` توقفت قبل الترجمة بسبب غياب Android SDK (`ANDROID_HOME`/`sdk.dir`) في بيئة التنفيذ؛ لم يُعلن نجاح اختبار Gradle.
- لم يُثبت بعد تشغيل WebSocket حقيقي بمفاتيح المستخدم، ولا اختبار جهاز Bluetooth أو Doze/background أو استهلاك الطاقة. هذه بوابات `RUNTIME_VERIFICATION_PENDING` وليست نجاحًا ضمنيًا من وجود الكود.
- تخصيصات P2 المتقدمة (wake-word sensitivity، أصوات التنبيه، تقارير latency والطاقة، وشاشة Voice Health) تبقى P3 كما في خطة الصوت.
