# طبقة الصوت

تحتوي هذه الحزمة مسار الصوت المحلي في AIRI Android: Vosk للنص المنطوق، Android TTS، حالة جلسة الصوت، audio focus، وخدمات wake word الاختيارية. يعمل هذا الوصف على `cp-foundation` ضمن Feature Freeze ولا يثبت سلوك ميكروفون أو Bluetooth أو مزود حي على جهاز فعلي.

## المسار المملوك

| القدرة | السلوك والحد |
|---|---|
| STT/TTS | مسار chat المدعوم هو Vosk محلي عند وجود model صالح مع Android TTS. النص الجزئي يبقى feedback أثناء الاستماع ولا يتحول إلى message قبل النتيجة النهائية. |
| إيقاف واستعادة الجلسة | `LiveVoiceService` يتذكر أن المستخدم طلب الاستماع صراحة، ويلغي delayed recovery بعد stop، ولا يستأنف بعد audio-focus gain إلا إن بقي الطلب صالحاً. |
| wake word | `HotwordService` يطبق cooldown لمنع wake events المكررة. لا يبدأ capture أو أداة تلقائية لمجرد wake. |
| الأصول والمفاتيح | OpenWakeWord يعمل فقط مع asset صحيح. Picovoice يحتاج asset/AccessKey صحيحين؛ عند غيابهما يفشل المسار بوضوح ولا يدّعي حالة listening جاهزة. |

## realtime cloud غير نشط

`RealtimeVoiceProvider` يعرّف عقود Gemini/OpenAI realtime، لكن مسار PCM microphone وAudioTrack ليس موصولاً end-to-end في `LiveVoiceService`. لذلك realtime cloud ليس مسار chat نشطاً ولا يدخل ادعاء الإصدار أو التحقق الداخلي.

## الدليل والحواجز

CI والحراس يثبتان بعض ownership/cooldown/stop boundaries والتجميع. أما microphone permission وhardware interruptions وBluetooth وforeground/background وmodel download واستهلاك البطارية وجودة STT/TTS فهي `RUNTIME_VERIFICATION_PENDING` على أجهزة حقيقية. لا يثبت وجود dependency أو واجهة إعداد نجاح مزود أو سياسة store.
