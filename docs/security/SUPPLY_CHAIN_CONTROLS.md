# ضوابط سلسلة توريد AIRI

## الضوابط المطبقة

يعتمد البناء الآن ملف `gradle/verification-metadata.xml` يحوي SHA-256 لـ755 مكون Gradle وmetadata المرتبط بها. يفعّل Gradle التحقق تلقائياً عند وجود هذا الملف في جذر `gradle/`؛ لذا يفشل البناء عند اختلاف artifact أو metadata عن بصمته المتوقعة. يثبت هذا **سلامة المحتوى** ولا يثبت هوية الناشر لأن التحقق الحالي لا يتضمن مفاتيح PGP. [1]

كما يعرّف `.github/dependabot.yml` تحديثات أسبوعية لـGradle وGitHub Actions وpackage manifests في `remote-control-tests` و`prototypes/web-ui`. يعتمد التفعيل التشغيلي على إعداد Dependabot في المستودع؛ يبين التوثيق الرسمي أن ملف `dependabot.yml` هو تهيئة التحديثات وأن حالة الخدمة تُتابع من تبويب Dependabot في dependency graph. [2]

| التحكم | الحالة | الدليل |
|---|---|---|
| Gradle dependency verification | `BUILD_VERIFIED` | `./gradlew help` نجح بعد إنشاء `gradle/verification-metadata.xml`، الذي يحتوي 755 مكوناً موثقاً بـSHA-256. |
| تحديثات Gradle وActions وNode | `SOURCE_VERIFIED` | `.github/dependabot.yml` يحدد أربعة ecosystems أسبوعية. |
| تدقيق Node للإنتاج | `TESTED` | `pnpm audit --prod` في `remote-control-tests` و`npm audit --omit=dev` في `prototypes/web-ui` سجلا صفراً من الثغرات المعروفة في وقت الفحص. |
| GitHub Dependabot alerts | `EXTERNAL_VERIFICATION_REQUIRED` | واجهة GitHub أعادت أن تنبيهات Dependabot معطلة للمستودع؛ يجب تمكينها من إعدادات Security and analysis على GitHub. |

## تشغيل البوابة

```bash
./gradlew --no-daemon help
cd remote-control-tests && pnpm audit --prod
cd ../prototypes/web-ui && npm audit --omit=dev
```

عند تحديث dependency معتمد، ينبغي توليد أو مراجعة بصمات `verification-metadata.xml` ضمن نفس pull request ثم تشغيل البوابات السابقة. لا يُضاف checksum لartifact جديد من مصدر غير موثوق أو من شبكة غير مراجعة.

## القيود

لا يشمل هذا الدليل توقيع release أو provenance للـAPK/MSI/DEB أو فحص ثغرات Gradle من خدمة خارجية. تلك ضوابط منفصلة في بوابة الإصدار وتحتاج secrets وإعدادات نشر لا تُحفظ في Git.

## المراجع

[1]: https://docs.gradle.org/current/userguide/dependency_verification.html "Gradle dependency verification"
[2]: https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/secure-your-dependencies/configure-version-updates "Configuring Dependabot version updates"
