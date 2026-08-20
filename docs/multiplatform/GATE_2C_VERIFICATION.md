# أدلة تحقق Gate 2C: `AttachmentPolicy`

ينقل Gate 2C سياسة تصنيف المرفقات والتحقق من الحجم وتطبيع metadata إلى `core-domain/commonMain`. تحول `ChatAttachment` و`ChatScreen` و`ChatViewModel` في Android إلى استيراد policy المشتركة، وحذفت النسخة Android-only. اختبارات MIME والحجم ومنع تكرار المصدر انتقلت إلى `commonTest`؛ أما اختبار `ChatAttachment.toTextMarker()` بقي في Android لأنه يختبر نموذج الرسالة المربوط بـ`Uri` و`Bitmap`.

| عنصر | الحالة | الدليل |
| --- | --- | --- |
| سياسة المرفقات المشتركة | `IMPLEMENTED` | content type، size limits، metadata normalization، duplicate-source comparison من دون Android/JVM API. |
| اختبارات policy المشتركة | `IMPLEMENTED` | نص/فيديو/مستند، حدود الحجم العامة والنصية، وعدم تكرار reference فارغ أو مختلف. |
| target JVM باسم `desktop` | `BUILDS` | `:core-domain:desktopTest` نجحت. |
| target Android للنواة | `BUILDS` | `:core-domain:compileDebugKotlinAndroid` نجحت. |
| تكامل Android | `BUILDS` | التطبيق يستورد policy المشتركة من طبقات domain وUI/ViewModel، و`:app:testDebugUnitTest :app:assembleDebug` نجحت. |
| acquisition Android | `IMPLEMENTED` خارج core | `Uri` و`Bitmap` وpicker وContentResolver تبقى في Android. |
| Desktop/Web acquisition | `PLANNED` | لا يوجد file picker أو drag/drop أو Browser File API مثبت بعد. |

## أوامر ونتائج التحقق

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid
```

**النتيجة:** `BUILD SUCCESSFUL`، 10 مهام منفذة.

```bash
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  :app:testDebugUnitTest :app:assembleDebug
```

**النتيجة:** `BUILD SUCCESSFUL`، 73 مهمة (67 منفذة و6 up-to-date). بقيت رسالة strip غير الحاجبة الخاصة بـ`libjnidispatch.so` فقط، وتحقق APK من وجود native library المعتاد نجح.

## حدود النقل

الـpolicy يتعامل مع metadata محايدة مثل الاسم وMIME والحجم وreference string؛ لا يقرأ ملفاً ولا يمنح permission ولا يفسر `Uri`. لذلك يصلح لـDesktop/Web في المستقبل، لكن acquisition والتنفيذ يملكان adapters مستقلة: Android picker/ContentResolver، Desktop picker/drag-drop، وWeb file input/drag-drop. لا يعني بناء policy أن المرفقات تعمل على أي هدف خارجي بعد.

## بوابة الخروج

حقق Gate 2C مشاركة منطق المرفقات الحرج من دون جعل UI أو filesystem عاماً. يبقى المرشح التالي خاضعاً لفحص تبعياته وقراره الخاص؛ لا تنقل `ChatAttachment` أو picker أو attachment persistence إلى `commonMain` بهذه الخطوة.
