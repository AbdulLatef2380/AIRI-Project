# استراتيجية المصادقة

## المبدأ

تحتفظ AIRI بمنطق OAuth الأمني المشترك، مثل توليد PKCE S256 والتحقق من `state` وتصنيف الأخطاء وسياسة الجلسة، في طبقة مستقلة عن Android. أما فتح authorization endpoint، استقبال callback، تخزين الرموز، وlogout فتظل implementations منصية. لا يكتب core deep link Android أو localhost listener Desktop أو browser redirect، ولا يضمّن client secret في أي target عام.

## العقد المستهدف

| العقد | مسؤولية مشتركة | مسؤولية platform adapter |
| --- | --- | --- |
| `OAuthTransaction` | provider id، scopes المطلوبة، state، PKCE verifier/challenge، expiry، correlation id آمن. | إنشاء redirect URI المناسب وتقديمه للـprovider. |
| `PlatformWebAuth` | بدء session، انتظار callback، إرجاع code/error ضمن transaction نفسها. | browser tab/custom tab/system browser/redirect. |
| `SecureTokenStore` | contract لحفظ/قراءة/مسح tokens من دون تسجيل values. | Android Keystore/Encrypted storage، Desktop OS vault، Web strategy مقيدة. |
| `AuthSessionPolicy` | refresh/logout/expiry/failure semantics. | تنفيذ network وخدمة identity المعتمدة. |
| `AuthEvent` | حالة UI خالية من secret. | أي interaction OS-specific مطلوب. |

## التدفقات بحسب المنصة

| منصة | بدء authorization | callback | تخزين الرمز | الحالة الحالية |
| --- | --- | --- | --- | --- |
| Android | system/custom tab عبر adapter Android | App Link أو custom scheme مصرح به | Android secure storage | `IMPLEMENTED`؛ IdP حقيقي `EXTERNAL_VERIFICATION_REQUIRED`. |
| Windows | browser النظام | localhost loopback أو custom protocol مسجل | Windows vault أو secure adapter مثبت | `ARCHITECTED`. |
| Linux | browser النظام | loopback أو custom protocol حسب distro/package | keyring أو secure adapter مثبت | `ARCHITECTED`. |
| Web | browser navigation/popup مقيد | redirect URI مسجل بدقة | لا تخزين طويل الأجل للأسرار في local storage | `ARCHITECTED`. |

## ضوابط PKCE وstate

كل authorization code flow علني يستخدم PKCE S256. ينشأ `state` عالي العشوائية لكل محاولة، ويرتبط transaction بـprovider والـredirect والـnonce ووقت الانتهاء، ويتحقق منه قبل قبول code. لا يسجل verifier أو authorization code أو access/refresh token. ترفض callbacks المتأخرة أو التي لا تطابق المعاملة النشطة. تعطى retries حدوداً ولا تعيد فتح browser تلقائياً بلا تفاعل مستخدم.

| خطر | الضابط |
| --- | --- |
| callback injection أو mix-up | state مقيد بالـprovider والـredirect ومعاملة واحدة قصيرة العمر. |
| token leakage في log/crash report | value objects مخفية وredaction قبل telemetry. |
| desktop port hijacking | تحقق state/PKCE، binding محلي محدود، وإغلاق listener فوراً بعد completion. |
| Web client secret exposure | لا secret في JavaScript؛ يستعمل public client + PKCE أو backend confidential client. |
| refresh token theft | secure store منصي، أقل scopes، logout يمسح session ويفك الارتباط المحلي. |
| external IdP misconfiguration | redirect allowlist واختبار حقيقي مسجل كـ`EXTERNAL_VERIFICATION_REQUIRED`. |

## Web: حدود لا تقبل النقل الحرفي

Web بيئة غير موثوقة بالنسبة لأسرار client. لا توضع مفاتيح provider أو refresh tokens طويلة الأجل في bundle أو `localStorage` باعتبارها secure vault. تستخدم AIRI، بعد تصميم المنتج الفعلي، واحداً من مسارين: public OAuth client مع PKCE وsession محدود/محمي، أو backend موثوق يحتفظ بالأسرار ويصدر session محدوداً. يختار المسار قبل تنفيذ Web ويخضع لاختبار redirect وCORS وlogout وsession revocation.

## قبول كل منصة

| اختبار | Android | Desktop | Web |
| --- | --- | --- | --- |
| PKCE S256 وstate صحيحان | اختبار وحدة وintegration | اختبار common + browser callback | اختبار browser redirect. |
| callback خاطئ يرفض | اختبار adapter | loopback/custom protocol test | redirect/state mismatch test. |
| logout يمحو credentials | secure-store test | vault/keyring test | session/server revocation test. |
| لا secrets في log | scanner/fixture | scanner/fixture | bundle/network review. |
| IdP فعلي | `EXTERNAL_VERIFICATION_REQUIRED` | `EXTERNAL_VERIFICATION_REQUIRED` | `EXTERNAL_VERIFICATION_REQUIRED`. |

## شرط الترقية

لا ترتقي مصادقة Desktop أو Web فوق `ARCHITECTED` حتى يحتوي target implementation على adapter وsecure store مناسبين وbuild قابل للتكرار. ولا تصبح `RUNTIME_VERIFIED` حتى يثبت flow مع client/redirect مسجلين وحساب اختبار معتمد؛ فهذا يتطلب credentials وبنية خارج نطاق CI المحلي.
