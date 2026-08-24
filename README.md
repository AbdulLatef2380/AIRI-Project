# AIRI Core

**AIRI Core** هو مساعد ووكيل ذكاء اصطناعي لنظام **Android** يعمل وفق نهج **محلي أولاً مع ذكاء سحابي اختياري**. يجمع المشروع بين المحادثة، والذاكرة المقصودة، والمشروعات وملفاتها المُدارة، والمهارات، والمرفقات، والصوت، والنماذج المحلية عبر `llama.cpp`، ومزودي النماذج السحابية ضمن حدود صريحة للخصوصية والصلاحيات.

> AIRI ليس واجهة دردشة عامة. المهارة تصف workflow قابلاً لإعادة الاستخدام، والموصل يصرّح بالوصول إلى بيانات أو أداة خارجية، ولا يُنفّذ الوكيل أثراً خارجياً حساساً دون مسار الموافقة المخصص.

## نطاق الإصدار وحالته

هذا المستودع في **Feature Freeze** لإصدار Android محدد. الحالة الدقيقة هي **`FEATURE_FREEZE / INTERNAL_CANDIDATE_EVIDENCED / SIGNING_SECRETS_BLOCKED`**. أي أن بوابات المصدر والبناء والاختبار الداخلية موثقة، لكن لا توجد حتى الآن APK أو AAB موقّعة قابلة للتثبيت أو النشر، ولا دليل جهاز حقيقي أو مزود حي أو متجر أو مراجعة قانونية.

| المجال | السلوك الحالي | مستوى الدليل |
|---|---|---|
| حدود المنتج | أسطح الدفع وStripe وسجل الفوترة والمتجر ومهارات المجتمع محجوبة fail-closed في هذا الإصدار. | **SOURCE_AND_CI_VERIFIED** |
| سياق المشروع والعزل | `ProjectContextResolver` يربط التنفيذ بالمشروع، ويمنع اختلاط الملفات أو نتائج المهام بين المشروعات. | **SOURCE_VERIFIED** |
| التعديل والموافقة والاستعادة | التعديل الخاص يمر بخطوة موافقة دقيقة، وترتبط النتيجة بـproject/task/run/step/artifact؛ اختبار Android يغطي قبول الاستعادة مرة واحدة والرفض بلا تطبيق. | **INSTRUMENTATION_VERIFIED** |
| الذاكرة وRAG | ذاكرة طويلة المدى مقصودة، رفض بيانات حساسة، واسترجاع معزول بالسياق؛ المحتوى التاريخي غير موثوق. | **SOURCE_VERIFIED** |
| المهارات والموصلات | اختصارات `/` للمهارات و`@` للمعرفة، مع فصل سجل المهارات عن الوصول إلى الأدوات أو البيانات الخارجية. | **SOURCE_VERIFIED** |
| المرفقات | صور وكاميرا وفيديو ونصوص وملفات؛ حدود حجم، تخزين خاص، كشف URI المكرر، وتنظيف الملفات عند حذف المحادثة. | **BUILD_VERIFIED** |
| الصوت | Vosk محلي للنص المنطوق وTTS وإظهار النص الجزئي أثناء الاستماع؛ إعداد wake-word الخارجي لا يدّعي التوفر دون أصل/مفتاح صحيحين. | **BUILD_VERIFIED** |
| الجدولة | وظائف WorkManager ذات معرّفات فريدة وسجل نتائج وقيود تشغيل محفوظة. | **SOURCE_VERIFIED** |
| الخصوصية وحذف البيانات | erase local data يمسح بيانات AIRI المحلية فقط ولا يدّعي حذف حساب أو بيانات مزود عن بعد. | **SOURCE_VERIFIED** |

## التحقق الحالي

| بوابة أو دليل | النتيجة المثبتة | الحد الصريح |
|---|---|---|
| [Android CI — R8 unsigned package](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32720458806) | اكتملت عقود المصدر والترجمة وshared core وdebug وJVM/lint و`assembleRelease` و`bundleRelease` وinstrumentation API 29 وفحص JNI. | APK/AAB وmapping الناتجة **غير موقعة**؛ لا تصلح للتثبيت أو النشر. |
| [Android CI على main](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32742046966) | اكتملت البوابات الداخلية نفسها بعد ترقية المرشح `fe3fb68b` إلى `main`. | التوقيع تخطّي بأمان لأن `RELEASE_SIGNING_READY=false`؛ لم ينشأ artifact موقع أو دليل `apksigner`. |
| تحقيق هوية التوقيع | لم يظهر keystore أو مسار مفتاح متتبع/غير متتبع أو تاريخ Git قابل للاستعادة؛ الأصل العام السابق الوحيد `airi-debug.apk` موقّع بشهادة Android Debug. | لا تُستبدل هوية الإصدار قبل تحديد backup خاص يحتفظ به مالك الإصدار خارج GitHub وManus. |
| الجهاز والمزود والمتجر والقانون | مصفوفة التحقق وخطوات الحوكمة موجودة. | لا يوجد دليل API 26 وAPI 35/36 على جهاز arm64 حقيقي، أو Firebase/OAuth/Calendar/GitHub حي، أو سياسة خصوصية/Data Safety/Play. |

يحتوي [handoff النشر](docs/product/RELEASE_PUBLICATION_HANDOFF.md) على الأدلة غير الموقعة، وخطوة التوقيع الآمنة التالية، وقائمة الحواجز الخارجية. يحتفظ [سجل التدقيق](docs/product/RELEASE_AUDIT_REGISTER.md) بالنتائج والحدود دون تخزين أسرار أو مفاتيح.

## البنية

```text
Compose UI / ViewModels
        │
        ├── Agent loop, planning and execution state
        ├── Project context, managed files, approvals and recovery
        ├── Model routing: local llama.cpp or cloud provider
        ├── Skills, tools and connector permissions
        ├── Memory admission, Room, embeddings and scoped RAG
        └── Voice, attachments, scheduled work and Android services
```

توجد خريطة أكثر تفصيلاً للحدود وملكية الحالة وتدفقات البيانات في [حزمة الهندسة](docs/architecture/OVERVIEW.md).

## البناء محلياً

| المتطلب | القيمة |
|---|---|
| JDK | 17 |
| Android SDK | API 36 |
| Gradle | 8.11.1 عبر الـwrapper |
| Android Gradle Plugin | 8.10.1 |
| Android NDK | 25.2.9519653 |
| CMake | 3.22.1 |

أنشئ `local.properties` محلياً فقط ويتضمن `sdk.dir=<Android SDK path>`؛ لا تضعه في Git. ثم شغّل:

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew --no-daemon --max-workers=1 :app:assembleRelease :app:bundleRelease :app:assembleDebugAndroidTest

python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_core_health.py
```

عند غياب أسرار التوقيع، ينتج `release` مخرجات **غير موقعة فقط**. لا تستخدمها للتوزيع. يُنفذ التوقيع النهائي في CI المحمي على `main` بعد إعداد `KEYSTORE_BASE64` و`STORE_PASSWORD` و`KEY_ALIAS` و`KEY_PASSWORD` عبر قناة آمنة؛ لا تضع قيمة من هذه القيم أو keystore في المستودع أو issue أو سجل بناء.

## الوثائق

| المسار | الغرض |
|---|---|
| [الهندسة](docs/architecture/OVERVIEW.md) | حدود المنصة، تدفقات التشغيل، ملكية الحالة، وقاعدة البيانات. |
| [الأمان](docs/security/THREAT_MODEL.md) | تهديدات Android والوكيل وحدود البيانات ومسارات الاستجابة. |
| [إغلاق الإصدار](docs/product/AIRI_RELEASE_CLOSURE.md) | مصدر الحقيقة لـFeature Freeze وblocker ledger. |
| [حالة الإغلاق](docs/product/AIRI_FINAL_CLOSURE_STATUS.md) | خريطة الحالة المختصرة والدليل الداخلي والحواجز الخارجية. |
| [handoff النشر](docs/product/RELEASE_PUBLICATION_HANDOFF.md) | مسار التوقيع والجهاز والمزود والقانون والمتجر، دون ادعاء أنها مكتملة. |
| [سجل التدقيق](docs/product/RELEASE_AUDIT_REGISTER.md) | أدلة build/privacy/runtime ونتيجة تحقيق هوية التوقيع. |
| [التشغيل](docs/deployment/BUILD_AND_RELEASE.md) | إعادة إنتاج البنية والإصدارات وCI. |
| [التجاري](docs/commercial/OVERVIEW.md) | تموضع المنتج، الترخيص، white-label، والعناية الواجبة. |

## التموضع

التموضع الأساسي لـAIRI هو **وكيل ذكاء اصطناعي محلي أولاً للهواتف**: ذاكرة مقصودة، نموذج محلي قابل للتشغيل دون اتصال، وسحابة اختيارية، مع مهارات وموصلات قابلة للتوسعة. هذه البنية تدعم تطبيقاً مباشراً، وتكاملاً تقنياً، وترخيصاً، ومساراً مستقبلياً للـwhite-label من دون فرض paywall أو ادعاءات حول بيع بيانات المستخدم.

## المساهمة والمسؤولية

لا تُرسل مفاتيح مزودين أو ملفات مفاتيح التوقيع إلى المستودع. راجع [حدود الأمان](docs/security/SECURITY_BOUNDARIES.md) قبل إضافة موصل أو مهارة أو مسار مرفق جديد، وشغّل البوابات المحلية المذكورة أعلاه قبل فتح طلب دمج. لا تعِد فتح مسارات تجارية أو موصلات حية ضمن Feature Freeze إلا ضمن برنامج إصدار مستقل يملك دليلاً تقنياً وقانونياً وموافقات النشر المطلوبة.
