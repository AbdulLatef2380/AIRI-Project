# AIRI Core License Matrix

> **ملاحظة قانونية:** هذه مصفوفة فرز هندسية وليست رأياً قانونياً. تعتمد على التبعيات المعلنة في المصدر، ولا تثبت امتثالاً أو ملكية أو صلاحية إعادة توزيع. يجب أن يراجع محامٍ مؤهل النسخ الدقيقة للتراخيص والإشعارات والنماذج والأصول قبل صفقة أو توزيع تجاري.

## منهجية الجرد

شغّل [`scripts/supply_chain_inventory.py`](../../scripts/supply_chain_inventory.py) لإنشاء [Dependency Inventory](DEPENDENCY_INVENTORY.md) من كتالوج الإصدارات و`app/build.gradle.kts`. ثم أنشئ الرسم المتعدي المقفل للإصدار المستهدف:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## مجموعات المراجعة

| المجموعة | أمثلة معلنة | سبب المراجعة | الحالة المطلوبة قبل التوزيع |
|---|---|---|---|
| Android/Jetpack/Kotlin | AndroidX، Compose، Room، WorkManager، Kotlin coroutines | إشعارات ونصوص ترخيص التبعيات المباشرة والانتقالية. | جمع notices من الرسم المتعدي. |
| Google/Firebase/Play | Firebase، Play Integrity، Google Sign-In، Billing | شروط خدمة وخصوصية وSDK وإفصاح متجر Play بجانب النصوص البرمجية. | مراجعة شروط Google وإفصاحات البيانات. |
| شبكة وتحويل بيانات | OkHttp، Gson | ترخيص التبعية وسجل CVE متصل بالإصدار المقفل. | فحص SCA عند كل إصدار. |
| صوت محلي | Vosk، Porcupine، TensorFlow Lite | قد ترافق runtime نماذج أو مفاتيح وصول أو شروط أصول مستقلة. | مراجعة runtime + model + access-key كل على حدة. |
| Native local AI | llama.cpp عبر JNI/CMake وملفات GGUF خارجية | مصدر native وترخيص النموذج ليسا بالضرورة ترخيص التطبيق. | توثيق revision والموديل وشروط redistrib. |
| صور وواجهة | Coil وMaterial/Compose | إشعارات التبعية، وخلو الأصول التي تضيفها الشركة من قيود منفصلة. | inventory للأصول والرموز والخطوط. |

## مكونات ذات مراجعة خاصة

| العنصر | ما يفعله في AIRI | سؤال العناية الواجبة |
|---|---|---|
| `llama.cpp` | تنفيذ نموذج محلي عبر JNI. | ما revision المدمج؟ وما notices المطلوبة؟ |
| نماذج GGUF | تُنزّل أو تضاف خارج مصدر التطبيق. | ما رخصة كل نموذج وقيود استخدامه وتوزيعه؟ |
| Vosk model assets | STT محلي عند التثبيت. | ما مصدر النموذج ولغاته وحقوق توزيعه؟ |
| Porcupine keyword/access key | wake-word اختياري. | ما شروط SDK والمفتاح وملف keyword؟ |
| Firebase/Crashlytics | مصادقة وبيانات تطبيق/telemetry وفق الإعداد. | ما consent وسياسة البيانات وإفصاحات المتجر؟ |

## قرار الإصدار

لا يطلق إصدار تجاري على أنه **LICENSE_VERIFIED** إلا بعد حفظ الرسم المتعدي المقفل، ونسخ التراخيص/الإشعارات المطلوبة، ونتيجة مراجعة قانونية موثقة. إلى ذلك الحين يبقى هذا المحور **EXTERNAL** رغم نجاح جرد المصدر.
