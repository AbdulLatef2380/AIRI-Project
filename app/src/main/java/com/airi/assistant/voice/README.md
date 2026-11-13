# طبقة الصوت

تحتوي هذه الحزمة مسار الصوت المحلي في AIRI Android: Vosk للنص المنطوق، Android TTS، حالة جلسة الصوت، audio focus، وخدمات wake word الاختيارية. يعمل هذا الوصف على `cp-foundation` ضمن Feature Freeze ولا يثبت سلوك ميكروفون أو Bluetooth أو مزود حي على جهاز فعلي.

## المسار المملوك

| القدرة | السلوك والحد |
|---|---|
| STT/TTS | مسار chat المدعوم هو Vosk محلي عند وجود model صالح مع Android TTS. النص الجزئي يبقى feedback أثناء الاستماع ولا يتحول إلى message قبل النتيجة النهائية. |
| إيقاف واستعادة الجلسة | `LiveVoiceService` يتذكر أن المستخدم طلب الاستماع صراحة، ويلغي delayed recovery بعد stop، ولا يستأنف بعد audio-focus gain إلا إن بقي الطلب صالحاً. |
| wake word | `HotwordService` يطبق cooldown لمنع wake events المكررة. لا يبدأ capture أو أداة تلقائية لمجرد wake. |
| الأصول والمفاتيح | OpenWakeWord يعمل فقط مع asset صحيح. Picovoice يحتاج asset/AccessKey صحيحين؛ عند غيابهما يفشل المسار بوضوح ولا يدّعي حالة listening جاهزة. |

## realtime cloud — transport موصول، والتحقق الخارجي مستقل

`RealtimeVoiceProvider` يعرّف عقود Gemini/OpenAI realtime، و`RealtimeAudioTransport` يوصل PCM من `AudioRecord` إلى المزود ثم يعيد PCM إلى `AudioTrack` داخل `LiveVoiceService`. اختيار المزود يتم من `VoicePreferencesStore`، والمفتاح يقرأ من `SecureApiKeyStore` فقط. تبقى مصادقة المزود، Bluetooth، الخلفية، واختبار الجهاز الحقيقي بوابات تحقق مستقلة؛ لا يُعلن realtime كقدرة إصدار مكتملة قبل إرفاق دليل تلك البوابات.

## الدليل والحواجز

CI والحراس يثبتان بعض ownership/cooldown/stop boundaries والتجميع. أما microphone permission وhardware interruptions وBluetooth وforeground/background وmodel download واستهلاك البطارية وجودة STT/TTS فهي `RUNTIME_VERIFICATION_PENDING` على أجهزة حقيقية. لا يثبت وجود dependency أو واجهة إعداد نجاح مزود أو سياسة store.

## معالجة النص قبل النطق

يُطبّق `SpeechTextPreprocessor` على النص المرسل إلى Android TTS فقط. يزيل علامات Markdown وكتل الكود والعناوين التقديمية، يستبدل الروابط بعبارة قابلة للنطق، ويضغط المسافات الزائدة مع الحفاظ على النص العربي والإنجليزي المختلط. لا يتغير الرد الظاهر للمستخدم ولا تُرسل المحادثة إلى خدمة خارجية بسبب هذه المعالجة.

نماذج Vosk الموجودة هنا هي نماذج **تحويل الكلام إلى نص** وليست نماذج TTS. لذلك لا يجوز عرضها للمستخدم كـ"نموذج صوت ناطق" أو استخدامها لتوليد الكلام. أصبح النقل السحابي موصولًا، لكن Cloud TTS لا يصبح متاحًا تلقائيًا: يلزم اختيار المستخدم للمزود، وجود مفتاح مشفر، موافقة الخصوصية، ونجاح اختبار الاتصال والجهاز.
