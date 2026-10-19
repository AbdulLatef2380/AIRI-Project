# GitHub Broker Wire Contract — Contract Gate

**الحالة:** `NOT_APPROVED / NOT_IMPLEMENTED`
**التاريخ:** 2026-10-19
**النطاق:** GitHub App OAuth للمرحلة الثالثة في AIRI
**الفرع:** `main`

> هذه الوثيقة بوابة اعتماد وليست مواصفة HTTP تنفيذية. لا تحتوي endpoint paths أو JSON schemas لأن Broker فعليًا غير موجود أو منشور في المشروع حتى تاريخ المراجعة.

## قاعدة الاعتماد

لا يجوز إنشاء HTTP client أو تفعيل OAuth الحقيقي في Android إلا بعد إرفاق مواصفة منشورة من Broker staging ومراجعتها مقابل هذه البوابة. أي عقد غير موثق يعاد تصنيفه إلى `BROKER_NOT_CONFIGURED` أو `BROKER_NOT_READY` ولا يُعامل كنجاح.

## العمليات المطلوبة قبل اعتماد العقد

يجب أن يعرّف Broker، بصيغة قابلة للاختبار، العمليات التالية دون تسريب أسرار:

- بدء معاملة التفويض.
- استقبال callback لدى Broker.
- استبدال handoff أحادي الاستخدام بجلسة AIRI.
- تجديد الجلسة مع rotation.
- إبطال الجلسة.
- جلب حساب GitHub والمستودعات والمنح.
- تنفيذ عمليات القراءة بعد فحص session/project/repository grant.
- تنفيذ عمليات الكتابة فقط بعد approval continuation صالح.

## المتطلبات الأمنية الإلزامية

- HTTPS وredirect URI مطابقان حرفيًا للبيئة.
- `state` عشوائي، مرتبط بالمستخدم والمشروع، وذو استخدام واحد.
- PKCE باستخدام S256 والتحقق server-side.
- authorization code لا يظهر في logs أو telemetry أو deep link غير محمي.
- client secret وprivate key والرموز لا تدخل APK أو Git.
- handoff code أحادي الاستخدام، قصير العمر، وغير قابل لإعادة التشغيل.
- الجلسة مرتبطة بـAIRI user ID وproject ID وGitHub numeric user ID.
- منح المستودعات تعتمد numeric repository IDs لا الاسم وحده.
- refresh rotation يرفض refresh token القديم بعد استخدامه.
- أخطاء 401/403/404/409/422/429/5xx مصنفة دون إعادة body الخام.
- لا retry تلقائي لعملية كتابة ذات نتيجة شبكية غير محسومة.

## نموذج الجلسة المطلوب اعتماده

يجب أن يحدد العقد صراحةً أحد النموذجين فقط:

1. **Proxy/opaque session:** الرموز تبقى خلف Broker، وAndroid يحتفظ بمرجع جلسة opaque فقط.
2. **Short-lived user token:** Broker يعيد token قصير العمر إلى Android وفق قرار أمني موثق.

لا يجوز خلط النموذجين، ولا يجوز اعتماد `GithubAuthorizationSession` الحالي كعقد شبكة قبل حسم هذا القرار.

## حاجز التنفيذ في Android

إلى أن تُملأ بيانات العقد وتُراجع:

- يبقى `NotConfiguredGithubBrokerClient` هو التنفيذ الافتراضي.
- لا يُضاف adapter HTTP تخميني.
- لا تُفتح أدوات GitHub OAuth في `ConnectorToolBridge`.
- لا تُعتبر اختبارات FakeBroker اختبار provider.
- لا يُستخدم PAT القديم كـfallback تلقائي.

## الأدلة المطلوبة لإغلاق البوابة

يجب توفير كل الآتي قبل تحويل الحالة إلى `APPROVED`:

- رابط أو artifact لمواصفة Broker staging.
- contract fixtures منزوعة الأسرار.
- اختبار replay وproject mismatch وTTL.
- اختبار refresh rotation.
- اختبار revoke وsession expiry.
- اختبار repository خارج grant.
- اختبار redaction للسجلات.
- نتيجة تشغيل staging حقيقية مرتبطة بـSHA أو إصدار Broker.
- مراجعة بشرية مستقلة قبل تفعيل الكتابة.

## الحالة الحالية

```text
WIRE_CONTRACT = NOT_APPROVED
HTTP_ADAPTER = NOT_STARTED
BROKER_STAGING = NOT_AVAILABLE
READ_OPERATIONS = BLOCKED
WRITE_OPERATIONS = BLOCKED
```
