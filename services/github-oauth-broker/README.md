# AIRI GitHub App OAuth Broker

هذا الخادم هو الحد الفاصل بين تطبيق Android وGitHub. لا يوضع `GITHUB_APP_PRIVATE_KEY` أو أي client secret في APK. يحتفظ الخادم بتوكن التثبيت لمدة لا تتجاوز ساعة، ويصدر للتطبيق جلسة opaque قصيرة العمر؛ لذلك لا يتصل AgentLoop أو Sandbox أو Terminal مباشرةً بـGitHub ببيانات PAT.

## التدفق

1. يطلب التطبيق `POST /v1/github/install/start` مع `state` عشوائي و`airi://oauth/callback`.
2. يعيد Broker رابط `https://github.com/apps/<slug>/installations/new?state=...`.
3. يختار المستخدم الحساب والمستودعات والصلاحيات في واجهة GitHub الرسمية.
4. يرسل GitHub `installation_id` و`setup_action` إلى `GET /v1/github/install/callback`، ثم يعيد Broker توجيهاً deep-link يحتوي على `state` و`ticket` أحادي الاستخدام.
5. يتحقق التطبيق من `state` محلياً، ثم يستبدل التذكرة بجلسة Broker مشفرة في `EncryptedSharedPreferences`.
6. يمرر GitHubConnector العمليات المسموح بها إلى `/v1/github/execute`. يظل فحص الموافقة durable للعمليات الكتابية قبل هذا المسار.

## إعداد GitHub App

- **Setup URL:** `https://<broker-host>/v1/github/install/callback`
- فعّل تثبيت التطبيق على الحسابات المطلوبة، وحدد أقل صلاحيات مستودع ممكنة.
- استخدم HTTPS وفعّل التحقق من شهادة TLS.
- اجعل private key وApp ID أسراراً في مدير أسرار الخادم فقط.
- اجعل `GITHUB_BROKER_BASE_URL` إعداداً عاماً في build، ولا تضع فيه أي credential.

## متغيرات التشغيل

`PORT`, `GITHUB_APP_SLUG`, `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `AIRI_CALLBACK_URI`.

في الإنتاج يجب استبدال الخرائط الموجودة في الذاكرة بمخزن Redis/قاعدة بيانات مشفرة مع TTL، وإضافة rate limiting، وتدوير مفاتيح الجلسات، وسجل تدقيق منقح. لا تُسجّل التذاكر أو توكنات GitHub. يمكن تمرير HTTP/HTTPS عبر reverse proxy مع TLS؛ لا يحتاج التطبيق إلى فتح منفذ وارد أو ngrok.

## تشغيل محلي

```bash
export GITHUB_APP_SLUG=airi
export GITHUB_APP_ID=123
export GITHUB_APP_PRIVATE_KEY="$(cat private-key.pem)"
export AIRI_CALLBACK_URI=airi://oauth/callback
npm start
```

هذا الخادم لا ينشئ App ولا يخزن المفاتيح في المستودع. استخدم مدير أسرار CI/النشر.
