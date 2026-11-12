# Native وAndroid device validation

## ما يثبته المستودع

- `app/src/main/cpp/llama/` و`ggml` مضمّنان داخل المستودع، ولا توجد استراتيجية submodule في هذه المرحلة.
- `CMakeLists.txt` يبني `libairi_native.so` من المصدر، مع CMake `3.22.1` وNDK `25.2.9519653`.
- Release يعلن ABI `arm64-v8a`، وDebug/Instrumentation يعلن `x86_64` باستخدام fallback scalar في CMake.
- `LlamaNativeSmokeTest` يثبت تحميل المكتبة، تحميل GGUF حقيقي، prefill، streaming، الإلغاء، reset، وفقدان readiness للـembedding/vision دون ملفاتها.

## تشغيل smoke على جهاز أو emulator

يجب استخدام نموذج GGUF مرخّص وقابل لإعادة التوزيع ضمن بيئة الاختبار فقط. لا يُحفظ النموذج في Git ولا يُرفع إلى artifact. مثال:

```bash
export NATIVE_SMOKE_MODEL=/secure/models/test-model.gguf
export NATIVE_SMOKE_SHA256=$(sha256sum "$NATIVE_SMOKE_MODEL" | cut -d' ' -f1)
export NATIVE_SMOKE_EVIDENCE_DIR=ci-native-smoke
bash .github/scripts/run-native-smoke.sh
```

يقوم السكربت بالتحقق من SHA-256 قبل الدفع إلى الجهاز، ثم يجمع نتيجة instrumentation و`logcat` و`uiautomator XML` وhash النموذج على الجهاز ويحذفه في النهاية.

## مصفوفة القبول

| المحور | baseline | الحديث | locales |
|---|---|---|---|
| Native build | arm64-v8a Release | x86_64 Debug | غير منطبق |
| Android instrumentation | API 29 | API 34، ثم API 35/36 عندما تتوفر صورة مستقرة | en-US وar-SA |
| Runtime | init/load/generate/stream/cancel/reset | model switch، low-memory، background/foreground، process death | RTL وdynamic font وTalkBack في مصفوفة UI |

فشل emulator boot أو عدم توفر image يصنف **environment failure**، بينما فشل compile/JNI assertion/ANR بعد إقلاع الجهاز يصنف **code failure**. لا يجوز تحويل `assumeTrue` إلى نجاح: اختبار النموذج الحقيقي يُشغّل فقط عندما يمرر المستدعي مسار GGUF، ونتيجة التشغيل اليدوي يجب حفظها كدليل.

## حدود المرحلة الحالية

لا يوجد نموذج اختبار داخل المستودع، لذلك لا يمكن إثبات جودة generation أو الذاكرة/الحرارة من sandbox وحده. كما أن `LlamaNative` لا يملك unload صريحاً للنموذج؛ دورة الإيقاف الحالية تستخدم `resetSession`، وتفريغ projector/embedding/draft، ثم يعاد تحميل model عند التبديل. يجب قياس memory/thermal وprocess-death على جهاز فعلي في تشغيل منفصل قبل إغلاق المرحلة نهائياً.
