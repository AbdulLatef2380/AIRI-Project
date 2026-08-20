# AIRI Core

**AIRI Core** هو مساعد ووكيل ذكاء اصطناعي لنظام Android يعمل وفق نهج **محلي أولاً مع ذكاء سحابي اختياري**. يجمع بين المحادثة، والذاكرة المقصودة، والمهارات، والمرفقات، والصوت، والنماذج المحلية عبر `llama.cpp`، ومزودي النماذج السحابية ضمن حدود صريحة للخصوصية والصلاحيات.

> AIRI ليس واجهة دردشة عامة. المهارة تصف workflow قابلاً لإعادة الاستخدام، والموصل يصرّح بالوصول إلى بيانات أو أداة خارجية، ولا يُنفّذ الوكيل أثراً خارجياً حساساً دون المسار المخصص للموافقة.

## ما يقدمه المنتج

| القدرة | السلوك الحالي | حالة الدليل |
|---|---|---|
| المحادثة والوكيل | تنفيذ محلي/سحابي، بث، إلغاء مملوك لجيل التنفيذ، ومنع الاستجابات المتأخرة. | **BUILD_VERIFIED** |
| التوجيه بين النماذج | اختيار مستند إلى قدرات ومتطلبات التنفيذ مع مسارات محلية وسحابية. | **SOURCE_VERIFIED** |
| الذاكرة وRAG | ذاكرة طويلة المدى مقصودة، رفض بيانات حساسة، استرجاع معزول بالجلسة، وسياق تاريخي غير موثوق. | **SOURCE_VERIFIED** |
| المهارات والموصلات | اختصارات `/` للمهارات و`@` للمعرفة، مع فصل سجل المهارات عن الوصول إلى الأدوات أو البيانات الخارجية. | **SOURCE_VERIFIED** |
| المرفقات | صور وكاميرا وفيديو ونصوص وملفات؛ حدود حجم، تخزين خاص، كشف URI المكرر، وتنظيف الملفات عند حذف المحادثة. | **BUILD_VERIFIED** |
| الصوت | Vosk محلي للنص المنطوق، TTS، وإظهار النص الجزئي أثناء الاستماع دون إرساله قبل النتيجة النهائية. | **BUILD_VERIFIED** |
| الجدولة | وظائف WorkManager ذات معرّفات فريدة وسجل نتائج وقيود تشغيل محفوظة. | **SOURCE_VERIFIED** |
| الخصوصية | حذف الحساب المنسق، تخزين مفاتيح آمن، RAG وملفات مرفقات بحدود بيانات صريحة. | **SOURCE_VERIFIED** |

## التحقق الحالي

آخر التزام تحقق بالكامل هو [`9a019ea5`](https://github.com/AbdulLatef2380/AIRI-Project/commit/9a019ea5). نفّذت بوابات CI في ذلك الالتزام Debug وlint واختبارات JVM وRelease وAAB وAndroid instrumentation والتحقق من مكتبة JNI.

| بوابة | النتيجة |
|---|---|
| [Architecture Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32410098505) | **BUILD_VERIFIED** |
| [Deep Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32410098561) | **BUILD_VERIFIED** |
| [Android CI](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32410098342) | **RUNTIME_VERIFIED** على محاكي Android CI |

يبقى اختبار الصوت الحقيقي ومزودي السحابة وOAuth على جهاز ومفاتيح إنتاج حقيقية **NOT_RUNTIME_VERIFIED**، ولا يُستبدل ذلك بادعاء نجاح اصطناعي.

## البنية

```text
Compose UI / ViewModels
        │
        ├── Agent loop, planning and execution state
        ├── Model routing: local llama.cpp or cloud provider
        ├── Skills, tools and connector permissions
        ├── Memory admission, Room, embeddings and RAG
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

أنشئ `local.properties` يتضمن `sdk.dir=<Android SDK path>`، ثم شغّل:

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk \
  ./gradlew --no-daemon --max-workers=1 :app:assembleRelease :app:bundleRelease :app:assembleDebugAndroidTest

python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_core_health.py
```

## الوثائق

| المسار | الغرض |
|---|---|
| [الهندسة](docs/architecture/OVERVIEW.md) | حدود المنصة، تدفقات التشغيل، ملكية الحالة، وقاعدة البيانات. |
| [الأمان](docs/security/THREAT_MODEL.md) | تهديدات Android والوكيل وحدود البيانات ومسارات الاستجابة. |
| [التجاري](docs/commercial/OVERVIEW.md) | تموضع المنتج، الترخيص، white-label، والعناية الواجبة. |
| [التشغيل](docs/deployment/BUILD_AND_RELEASE.md) | إعادة إنتاج البنية والإصدارات وCI. |
| [البحث التصميمي](docs/research/AIRI_AGENT_PRODUCT_PRINCIPLES.md) | مبادئ عامة مستخلصة من مصادر رسمية دون نسخ منتجات منافسة. |

## التموضع

التموضع الأساسي لـ AIRI هو **وكيل ذكاء اصطناعي محلي أولاً للهواتف**: ذاكرة مقصودة، نموذج محلي قابل للتشغيل دون اتصال، وسحابة اختيارية، مع مهارات وموصلات قابلة للتوسعة. هذه البنية تدعم تطبيقاً مباشراً، وتكاملاً تقنياً، وترخيصاً، ومساراً مستقبلياً للـwhite-label من دون فرض paywall أو ادعاءات حول بيع بيانات المستخدم.

## المساهمة والمسؤولية

لا تُرسل مفاتيح مزودين أو ملفات مفاتيح التوقيع إلى المستودع. راجع [حدود الأمان](docs/security/SECURITY_BOUNDARIES.md) قبل إضافة موصل أو مهارة أو مسار مرفق جديد، وشغّل البوابات المحلية المذكورة أعلاه قبل فتح طلب دمج.
