# Gate Desktop Linux: أساس AIRI Desktop

## النطاق المثبت

ينشئ هذا gate تطبيق `app-desktop` مستقلاً مبنياً بـCompose Multiplatform 1.8.2. يستهلك التطبيق `core-domain` مباشرةً، ويستخدم `MemoryTextNormalizer` وعقود `ActionPlan` و`AgentGoal` و`PlanStep` عند استقبال الطلب. لا ينسخ Android ViewModels أو `Context` أو Room أو JNI أو provider شبكي.

| القدرة في Linux | الحالة | الدليل |
| --- | --- | --- |
| تطبيق Compose Desktop | `RUNTIME_VERIFIED` | نافذة `AIRI Desktop` ظهرت ضمن Xvfb على Linux. |
| إدخال keyboard | `RUNTIME_VERIFIED` | كُتب طلب ثانٍ في الحقل وأُرسل بمفتاح Enter؛ أضيف زوج رسائل جديد إلى السجل. |
| إرسال بالماوس | `RUNTIME_VERIFIED` | كُتب طلب في الحقل ثم نُقر زر Send؛ أضيفت رسالة المستخدم ورسالة AIRI إلى السجل. |
| الاستجابة | `RUNTIME_VERIFIED` | عرض التطبيق استجابة عربية حتمية تشير إلى AIRI Core وخطوة التخطيط المحلية. |
| تخزين واستعادة سجل محدود | `RUNTIME_VERIFIED` | كتب التطبيق أربعة records في `~/.airi-desktop/foundation-session.log`، ثم أعاد عرض الرسالتين الأوليين بعد إغلاق النافذة وتشغيل جديد. |
| حزمة Linux | `BUILDS` | `app-desktop/build/compose/binaries/main/deb/airi_1.0.0-1_amd64.deb`، بحجم 84,920,590 bytes عند التحقق. |
| اختبارات adapter Desktop | `TESTED` | `:app-desktop:test` نجح؛ يغطي الاستجابة الحتمية والتخزين والاستعادة في `DesktopAgentTest`. |
| [AIRI Android CI](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32440326111) | `TESTED` | نجح بناء/اختبار النواة وAndroid debug وunit/lint وrelease وinstrumentation والتحقق native بعد إضافة وحدة Desktop. |
| [AIRI Deep Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32440326311) | `TESTED` | نجح lint وتحقق النواة بعد إضافة الوحدة. |
| [AIRI Architecture Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32440326146) | `TESTED` | نجح تدقيق اتجاه التبعيات وبنية المشروع بعد إضافة `app-desktop`. |

## أوامر التحقق

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  :app-desktop:test :app-desktop:jar

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  :app-desktop:packageDeb
```

ينشئ Compose Multiplatform مهام تشغيل وحزم native مستقلة؛ بناء `.deb` يتم على Linux لأن cross-compilation لتلك الحزمة غير مدعوم في plugin. يتطلب التغليف JDK 17 أو أحدث. [1] [2]

## دليل التشغيل

نفّذ القبول على Linux مع Xvfb بدقة 1280×800 وOpenJDK 17 headful. احتاجت البيئة إلى `binutils` لتوفير `objcopy` لـ`jlink`، و`fakeroot` لـ`jpackage`، ومكوّن `openjdk-17-jre` الرسومي لتوفير `libawt_xawt.so`. هذه متطلبات للبيئة المحلية وليست dependencies مصدرية لتطبيق AIRI.

| عنصر الدليل | المسار خارج شجرة Git |
| --- | --- |
| النافذة قبل التفاعل | `/home/ubuntu/AIRI-Project-evidence/desktop-linux/airi-desktop-empty.png` |
| الإدخال في الحقل | `/home/ubuntu/AIRI-Project-evidence/desktop-linux/airi-desktop-input-attempt.png` |
| الاستجابة بعد إرسال بالماوس | `/home/ubuntu/AIRI-Project-evidence/desktop-linux/airi-desktop-response.png` |
| الاستعادة بعد تشغيل جديد | `/home/ubuntu/AIRI-Project-evidence/desktop-linux/airi-desktop-relaunch.png` |
| الاستجابة بعد إرسال Enter | `/home/ubuntu/AIRI-Project-evidence/desktop-linux/airi-desktop-keyboard-submit.png` |
| سجل التفاعل | `/home/ubuntu/AIRI-Project-evidence/desktop-linux/foundation-session-after-keyboard.log` |

## حدود الحالة

> **`RUNTIME_VERIFIED` هنا يخص أساس Linux Desktop فقط:** نافذة، إدخال، استجابة محلية حتمية، وسجل جلسة محدود. لا يعني اتصالاً بمزود نموذج، inference محلياً، agent execution على نظام التشغيل، اختيار ملفات، جدولة، OAuth، أو تخزيناً آمناً كاملاً.

تبقى Windows `PLANNED` حتى بناء package على Windows وتشغيل نافذة وتفاعل واستعادة على ذلك النظام. تبقى cloud models وlocal models وattachments وscheduler وauthentication وvoice على Desktop `PLANNED`. لا تُستمد أي حالة Windows أو Web من دليل Linux هذا.

## المراجع

[1]: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html "Compatibility and versions | Kotlin Multiplatform Documentation"
[2]: https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-native-distribution.html "Native distributions | Kotlin Multiplatform Help"
