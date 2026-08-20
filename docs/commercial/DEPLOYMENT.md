# AIRI Core Deployment for Commercial Review

AIRI يبني APK/AAB بواسطة Gradle ويشغل CI الذي يتحقق من Debug وlint وJVM وRelease وAAB وAndroid instrumentation وتغليف JNI. لا تُحفظ مادة signing في Git.

## ما يسلَّم مع إصدار تقني

| المخرج | سبب الاحتفاظ |
|---|---|
| AAB/APK موقعان حسب سياسة الناشر | توزيع أو فحص داخلي. |
| R8 mapping | تحليل الأعطال وفك غموض crash reports. |
| CI run URL وlogs | سلسلة دليل البناء. |
| dependency inventory + graph متعدٍ | مراجعة supply-chain. |
| Room schemas/migrations | إمكانية تحديث بيانات المستخدمين بأمان. |

## ما يجب أن يملكه الطرف الناشر

- حسابات Google Play/Firebase/مزودي النماذج/OAuth باسم الجهة الناشرة.
- keystore وسياسة وصول ودوران مفاتيح مستقلة.
- سياسة خصوصية وإفصاحات Data Safety متوافقة مع السوق.
- مراجعة نموذج محلي وأصول صوت وتبعيات قبل التوزيع.

التعليمات الكاملة: [Build and Release](../deployment/BUILD_AND_RELEASE.md).
