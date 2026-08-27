# AIRI — مصفوفة البوابات الخارجية

> **الحالة عند الرأس `b4ea8397`:** لا توجد جلسة جهاز Android ARM64 أو Android SDK/adb/emulator أو device farm مصرح بها، ولا credentials اختبارية لمزودي AIRI، ولا جلسة Play Console ناشر، ولا اعتماد قانوني. لا تُحوّل أي خانة إلى نجاح بالاستنتاج من CI.

| Gate | ما يمكن تنفيذه داخلياً | ما يحتاج مالكاً/بيئة خارجية | الحالة الحالية | الدليل المطلوب للإغلاق |
|---|---|---|---|---|
| Android ARM64 API 26 | build، unit، source contracts، CI instrumentation المتاح | هاتف/محاكي ARM64 API 26 مع تثبيت artifact | `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` | logs/screen evidence sanitized لـfresh install، permissions، lifecycle، UI، model/local path |
| Android ARM64 API 35/36 | compile/release/native verification في CI | جهاز ARM64 API 35 أو 36 | `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` | نفس P0 matrix مع runtime permission/foreground service/background/process recreation |
| Google Sign-In | policy/outcome tests، Firebase config/SHA evidence، CI | حساب Google اختباري مصرح، جهاز، Firebase project access عند الحاجة | `EXTERNAL_PENDING` | success/cancel/missing identity/network/provider error، consent، session restore، sanitized logs |
| GitHub OAuth | source callback/state review وsecret boundary | GitHub App/OAuth app مصرح وcallback صحيح، ويفضل backend exchange آمن | `EXTERNAL_PENDING` | consent/cancel/revoke/expiry، عدم وجود client secret في APK، callback evidence |
| Google APIs/Drive/Calendar | connector/source contracts وpermission policy | OAuth scopes، حساب اختبار، API project وبيانات مصرح بها | `EXTERNAL_PENDING` | least-scope consent، success/error/revoke، no raw token/log leakage |
| Zapier | architecture/capability contract، fail-closed release | Zapier developer app، OAuth credentials، redirect approval، test account | `DISABLED / EXTERNAL_PENDING` | لا يُفتح قبل provider review؛ بعده consent/token/revoke/webhook evidence |
| IFTTT | architecture/capability contract، fail-closed release | Maker Webhooks key/test app وحساب مصرح | `DISABLED / EXTERNAL_PENDING` | لا يُفتح قبل owner approval؛ بعده key storage/rotation/webhook/error evidence |
| Local model runtime | source/JNI build/native verification | جهاز ARM64، model file مرخص، memory/performance measurement | `RUNTIME_PENDING` | first/second/10th request، long context، OOM/cancel/restart، sanitized metrics |
| Cloud model runtime | router/adapter/source tests | مفاتيح provider أو test proxy مصرح بها | `EXTERNAL_PENDING` | request/stream/error/retry/cancel/cost evidence لكل provider |
| Voice/STT/wake/TTS | policy/unit، explicit Vosk download، source lifecycle audit | جهاز mic، نموذج Vosk/خدمة wake مرخصة، permission access | `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` | deny/revoke/background/false-positive/cooldown/release-resource/process-kill evidence |
| WorkManager/OEM | worker policy/unit/CI | أجهزة API 26/35/36 أو farm، Doze/reboot/OEM | `EXTERNAL_PENDING` | schedule/run/retry/cancel/reboot/Doze evidence |
| Terminal/Sandbox | source contracts/security scanner، internal release gate | بيئة تنفيذ مصرح بها أو device، process/filesystem inspection | `EXTERNAL_PENDING` | isolation، approval، cancellation، cleanup، path/shell negative tests |
| Keystore/Secrets | secure-store code، redaction tests، security scan | Android Keystore على جهاز، telemetry/crash consent setup | `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` | create/replace/rotate/delete/logout/switch + no-leak network/log evidence |
| Firebase telemetry/crash | consent code/source/CI | Firebase project DebugView/network access مع test device | `EXTERNAL_PENDING` | opt-in/opt-out/revoke/restart، no event/crash before consent |
| Updates | accurate Not Configured state وpolicy test | release metadata service/store channel وdownload/install handoff | `EXTERNAL_PENDING` | signed metadata، verify/download/install/result/error evidence؛ لا simulated progress |
| Play Console | artifact/signing/Data Safety inventory | owner Play Console authentication، listing/upload/pre-launch | `OWNER_AUTH_REQUIRED` | uploaded AAB، Play checks، pre-launch report، versionCode/package match |
| Privacy/Data Safety | source inventory، permissions/consent paths، security scan | owner/legal review وسياسة خصوصية منشورة وruntime network proof | `OWNER_AND_LEGAL_REVIEW_REQUIRED` | approved disclosure، policy URL، Data Safety answers، network/SDK evidence |
| Accessibility/UX | source strings/policies وCI | أجهزة فعلية، TalkBack، font scale، RTL/LTR، contrast | `BLOCKED / REAL_DEVICE_ACCESS_REQUIRED` | visual/assistive test evidence للغات والأوضاع والشاشات الأساسية |

## قاعدة handoff

يجب على المالك توفير البيئة أو الاعتماد الخاص بالبوابة فقط، لا private keys أو passwords في المحادثة. عند توفرها تُنفذ صفوفها منفصلة، وتُحفظ الأدلة المنقحة داخل سجل الإصدار دون secrets أو tokens أو raw personal data. إلى ذلك الحين تبقى الميزات غير المهيأة مغلقة أو معروضة بصراحة كـ`Not Configured`.
