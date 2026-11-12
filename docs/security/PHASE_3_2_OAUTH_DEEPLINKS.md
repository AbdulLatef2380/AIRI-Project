# المرحلة 3.2 — OAuth وDeep Links

## ما تم إغلاقه

تم تضييق callback الخاص بـ OAuth إلى `airi://oauth/callback` بالمسار الدقيق `/callback`. لم يعد `/callback/extra` أو `/callback/` يطابق manifest أو policy، كما تُرفض callbacks التي تحتوي fragment. هذا يقلل مساحة intent-filter ويمنع تمرير مسارات غير مقصودة إلى مسار المصادقة.

أضيفت `OAuthCallbackUriPolicy` كسياسة pure قابلة لاختبار JVM. وتغطي اختبارات regression المسار الصحيح، scheme/host الخاطئين، المسارات الإضافية، slash النهائي، والـfragment.

تمت إزالة `code` و`state` من event bus بالكامل. بعد استهلاك state وتبادل code، ينشر `MainActivity` فقط `OAuthCallbackCompleted(provider)`. وبذلك لا يدخل authorization code أو CSRF state إلى EventBus أو trace أو analytics أو logs. وتُحدّث `IntegrationsViewModel` من إشارة completion غير الحساسة فقط.

بقيت حماية registry الحالية فعالة: state عشوائي عالي الإنتروبيا، TTL لخمس دقائق، consume ذري لمرة واحدة، ربط connector/scopes/owner، وPKCE باستخدام S256. حالات state المجهول والمنتهي والمعاد تشغيله مرفوضة قبل تبادل code. كما يتحقق مسار GitHub من owner binding الخاص بالمشروع.

## التحقق

- `python3 tools/verify_core_changes.py`: **101/101**.
- `python3 scripts/static_chat_runtime_audit.py`: **35/35**.
- `git diff --check`: ناجح.
- secret-sink scan للبحث عن event القديم أو تسجيل code/state: ناجح.
- Android/JVM Gradle execution غير متاح في Sandbox الحالية لعدم توفر Android SDK؛ يلزم اعتماد CI لتأكيد compile والاختبارات على Android.

## قرار custom scheme وApp Links

ما زال `airi://` مستخدماً للتوافق مع callback الحالي، ولذلك لا يمكن ادعاء أنه محمي من اعتراض تطبيق منافس على مستوى نظام Android وحده. يخفف PKCE خطر اعتراض authorization code لأن token endpoint يتطلب verifier السري الخاص بالطلب، بينما يمنع state CSRF والـowner/connector binding خلط الجلسات.

الانتقال الإنتاجي المفضل هو HTTPS App Link على نطاق تملكه AIRI، مع `android:autoVerify="true"` وملف Digital Asset Links يطابق شهادة توقيع التطبيق. لا يمكن تفعيل ذلك بأمان من هذا المستودع دون نطاق إنتاج وشهادة release و`assetlinks.json` منشور فعلياً؛ لذلك بقيت هذه الخطوة `EXTERNAL_PENDING` وليست `VERIFIED`.

## مصفوفة الحالات العدائية

- state mismatch: PASS عبر رفض registry.
- replay: PASS عبر `consumeRequest` لمرة واحدة.
- expiry: PASS عبر TTL policy واختبارات JVM.
- provider/connector mismatch: PASS عبر connector bound داخل state ومسارات completion المحددة.
- owner mismatch: PASS لمسار GitHub عبر owner binding للمشروع.
- code بلا state: PASS؛ لا يبدأ exchange.
- callback بمسار غير مسموح أو fragment: PASS عبر URI policy وmanifest exact path.
- تسريب code/state إلى event/log: PASS؛ أزيلت الحمولة الحساسة والإخراج اللوجي.
- تطبيق منافس على custom scheme: MITIGATED وليس ELIMINATED؛ PKCE يحد أثر interception، وHTTPS App Links هو الإجراء المتبقي المطلوب للإغلاق الكامل.

## المراجع الرسمية

[1]: https://developer.android.com/training/app-links/about "About App Links — Android Developers"
[2]: https://www.rfc-editor.org/rfc/rfc6749 "RFC 6749 — The OAuth 2.0 Authorization Framework"
[3]: https://www.rfc-editor.org/rfc/rfc7636 "RFC 7636 — Proof Key for Code Exchange by OAuth Public Clients"
