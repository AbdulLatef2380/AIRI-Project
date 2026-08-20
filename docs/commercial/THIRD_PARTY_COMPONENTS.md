# AIRI Core Third-Party Components

هذا الفهرس هو نقطة البداية لمراجعة التبعيات والتراخيص. الإصدار المباشر القابل لإعادة الإنتاج موجود في [Dependency Inventory](DEPENDENCY_INVENTORY.md)، ومصفوفة قرار المراجعة في [License Matrix](LICENSE_MATRIX.md).

| المجال | أمثلة من المصدر | الاستخدام داخل AIRI | مراجعة مطلوبة |
|---|---|---|---|
| Android foundation | AndroidX، Compose، Room، WorkManager | UI، حالة دورة الحياة، persistence، أعمال خلفية. | نصوص ترخيص وإشعارات الرسم المتعدي. |
| network/data | OkHttp، Gson | مزودون سحابيون وتحويل بيانات. | SCA وlicense notices للإصدار المقفل. |
| Firebase/Google Play | Auth، Firestore، Analytics، Crashlytics، Integrity، Billing | حساب، telemetry اختياري، سلامة التطبيق، وفوترة مستقبلية. | شروط الخدمة، consent، Data Safety، وحسابات المشتري. |
| local inference | llama.cpp/JNI، GGUF خارجية | تشغيل نموذج محلي. | revision وlicense runtime وشروط كل model file. |
| voice | Vosk، Porcupine، TensorFlow Lite/OpenWakeWord | STT وwake word محليان. | license runtime + model/keyword/key terms. |
| UI/media | Coil، Material، Accompanist | صور المرفقات وواجهة Compose. | attribution وlicense notices. |

## مكونات لا تُنقل من Git وحده

| العنصر | سبب عدم كفاية Git |
|---|---|
| مفاتيح cloud/OAuth/CI | يجب تدويرها وإعادة إنشائها باسم الجهة المالكة الجديدة. |
| نموذج محلي أو asset صوت | قد يُنزّل لاحقاً أو تكون له رخصة مستقلة عن source. |
| حسابات Firebase/Play/مزود | ملكية حساب وتشغيل وإعدادات خصوصية منفصلة. |
| توقيع release | يجب أن يملكه طرف النشر الجديد أو أن ينقل عبر إجراء قانوني وآمن. |

## إجراء تحديث هذا الفهرس

1. حدّث كتالوج الإصدارات أو Gradle.
2. شغّل `python3 scripts/supply_chain_inventory.py`.
3. احفظ الرسم المتعدي للإصدار باستخدام `:app:dependencies`.
4. راجع notice/license/CVE وشروط model/asset قبل النشر.
5. حدّث هذا المستند و`IP_INVENTORY.md` عندما يتغير مصدر أو نوع أصل.
