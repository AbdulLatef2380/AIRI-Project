# المرحلة الثالثة: توحيد دفعات الموصلات المتبقية

## الهدف

منع التعامل مع كتالوج الموصلات على أنه مجموعة أزرار اتصال. كل تعريف يجب أن ينتمي إلى دفعة تنفيذ واضحة، ويملك استراتيجية مصادقة، ومحول runtime حقيقياً، وفحص صحة قبل أن يصبح قابلاً للاستخدام.

## ما تم تطبيقه

- إضافة `ConnectorRolloutRegistry` كسجل مركزي لكل تعريفات الكتالوج.
- تقسيم الموصلات إلى دفعات:
  - `LIVE_ADAPTERS`: Google وGitHub وTelegram وNotion وZapier والموصلات المحلية الحالية.
  - `MICROSOFT`: Outlook وCalendar وOneDrive وTeams وSharePoint وTo Do.
  - `COMMUNICATION`: Slack وDiscord.
  - `FILES`: Dropbox وBox.
  - `PRODUCTIVITY`: Trello وAsana وClickUp وMonday وTodoist.
  - `DEVELOPMENT`: GitLab وBitbucket وJira وLinear.
  - `AUTOMATION`: Airtable.
  - `DESIGN_AND_MEETINGS`: Figma وCanva وZoom وخدمات Google المتبقية.
- لكل تعريف الآن:
  - الدفعة.
  - استراتيجية المصادقة.
  - اسم المحول المطلوب.
  - حالة adapter حقيقية.
  - رابط التوثيق الرسمي.
  - سبب الحجب إذا لم يكن المحول جاهزاً.
- إضافة اختبارات تثبت عدم وجود معرف خارج خطة التنفيذ، وعدم بدء authorization لموصل بلا adapter.

## الدفعة التنفيذية التالية

### B1 — Microsoft

ينبغي تنفيذ `MicrosoftIdentityAuthorizationAdapter` واحد يعيد استخدام Microsoft identity platform مع:

- Authorization Code + PKCE.
- redirect URI مسجل للتطبيق.
- scopes منفصلة للبريد والتقويم والملفات والمهام.
- تخزين refresh token في المخزن المشفر.
- health checks قراءة فقط لكل سطح خدمة.
- revoke/disconnect مركزي.

لا يتم وضع `client_secret` داخل APK. يجب توفير `client_id` العام وredirect URI من إعدادات البناء أو Remote Config آمن.

### B2 — Slack وDiscord

يستخدم كل مزود adapter مستقلاً لأن scopes وhealth endpoints وسياسة bot/user token مختلفة. لا يجوز استخدام adapter OAuth عام يخلط بينهما.

### B3 — Dropbox وBox

تنفيذ OAuth PKCE، ثم health check للـaccount/profile قبل كشف أدوات الملفات.

## معيار نقل موصل من CATALOG_ONLY إلى LIVE

1. Adapter runtime مسجل في `ConnectorBootstrap`.
2. `ConnectorAuthorizationManager` يملك مسار start/complete/revoke.
3. لا توجد secrets داخل المصدر أو APK.
4. callback state وPKCE مغطاة باختبارات replay/mismatch.
5. credential storage failure يعيد الموصل إلى disconnected.
6. health check ناجح وفاشل مغطى باختبار.
7. لا تظهر الأدوات قبل `connected && healthy`.
8. فحص Android CI ناجح على جهاز أو emulator.

## ما لم يتم تفعيله عمداً

الموصلات الـ28 المتبقية ما زالت `CATALOG_ONLY` حتى تسجيل تطبيقات المزودين وتوفير redirect/scopes واعتماد health checks رسمية. هذا حجب مقصود، وليس نقصاً في الواجهة، لمنع اتصال شكلي أو كشف أسرار المستخدم.
