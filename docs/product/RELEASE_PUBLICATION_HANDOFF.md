# تسليم بوابات نشر AIRI

> **الحالة:** `FEATURE_FREEZE / EXTERNAL_GATE_HANDOFF`.
>
> هذا المستند هو قائمة التنفيذ الوحيدة لما لا يستطيع فرع `cp-foundation` أو محاكي CI إثباته. لا يجوز تحويل أي صف إلى `PASS` من دون evidence منقح محفوظ تحت سجل إصدار، ولا يجوز اعتبار أي APK/AAB غير موقّع صالحاً للتثبيت أو النشر.

## 1. نقطة البداية المثبتة

المرشح الداخلي الحالي هو الالتزام `099e503f` على `cp-foundation`. بنيت GitHub Actions run [`32720458806`](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32720458806) المسار غير الموقّع كاملاً: الحارسات والتوطين، debug/unit/lint، `assembleRelease` و`bundleRelease` مع R8، APK badging، فحص ZIP للـAAB، `mapping.txt`، instrumentation API 29، وفحص native.

| عنصر الدليل البنيوي | القيمة المتحققة في artifact غير الموقّع | الحد الصريح |
|---|---|---|
| APK | `app-release-unsigned.apk`، SHA-256: `20c6fffb578feee017d4ef25b0eda9944863f365bc8613f6747969e0bf77a236` | لا توقيع ولا تثبيت ولا توزيع. |
| AAB | `app-release.aab`، SHA-256: `647166abd6c955c7f68ab2f528b0a85b998776f79117c9b79c1ea81227569dcf` | لا Play upload ولا هوية ناشر. |
| R8 mapping | `mapping.txt`، 815,811 سطراً، SHA-256: `d82ae1096fcbdaaf493952997b2a32bd3cf8ab7acfabc085c5c4ca9a0aced5a7` | لا mapping نهائي قبل تشغيل signed `main`. |
| Manifest المرصود | `com.airi.assistant`، `versionCode=1`، `minSdk=26`، `targetSdk=36`، `arm64-v8a`، وإذن Billing غير موجود في APK المفحوص. | لا يغني عن manifest الحزمة الموقعة أو device install. |

## 2. نطاق الإصدار المجمّد

يركز هذا الإصدار على الرحلة المحلية المحددة وموافقاتها وخصوصيتها. أسطح الدفع وسجل الفوترة وStripe والمتجر ومهارات المجتمع معطلة في Feature Freeze؛ routes القديمة أو المستعادة تعود إلى شاشة محلية آمنة، وmanifest يستخدم `tools:node="remove"` لإزالة إذن Billing المدمج. لا يعيد المالك هذه الأسطح قبل برنامج تجاري مستقل يملك مزوداً فعلياً وبيانات قانونية وPlay evidence.

| السطح | قرار هذا الإصدار | الدليل الحالي | شرط إعادة الإدراج لاحقاً |
|---|---|---|---|
| Paywall / Google Play Billing | مستبعد ومحجوب | حارس 77/77، اختبار JVM، CI `32716171238`، وbadging الحزمة غير الموقعة بلا Billing. | حساب ناشر، منتجات فعلية، مسار purchase/cancel/restore، سياسة وData safety، signed artifact وPlay review. |
| Stripe / credits | مستبعد ومحجوب | لا تهيئة Stripe من routes المحجوبة. | مفاتيح production منفصلة، web checkout/cancel/error/revoke حي، سياسة مالية وقانون ومراجعة متجر. |
| Marketplace / Community skills | مستبعد ومحجوب | لا نقطة دخول مرئية، وroute مستعاد fail-closed. | هوية ناشر للمحتوى، moderation/revocation، attribution/licensing، device/store evidence. |
| Firebase / OAuth / connectors | لا يعتبر أي مزود "متحققاً" بعد. | حواجز consent وملكية الأسرار ومسارات failure موجودة في المصدر/CI. | يعلن owner المزودات المشمولة بالاسم ثم ينفذ consent/cancel/revoke/error مع حسابات اختبار منقحة؛ كل مزود غير معلن يبقى خارج وصف الإصدار. |

## 3. تسلسل التنفيذ الإلزامي

| الترتيب | المالك | الإجراء | evidence المطلوب للقبول | ما لا يثبته الإجراء |
|---:|---|---|---|---|
| 1 | Release engineer | شغّل workflow المحمي على `main` من commit مرشح مع أسرار signing المعتمدة فقط. | APK/AAB موقعان، `mapping.txt`، `SHA256SUMS`، مخرجات `apksigner verify --verbose` و`--print-certs`، `versionCode` نهائي، ورابط CI. | لا يثبت تجربة المستخدم أو Play acceptance. |
| 2 | Release engineer + QA | ثبت APK الموقع على API 26 وAPI 35/36 حقيقيين، ABI `arm64-v8a`. | اسم الجهاز/API/ABI/build، نتيجة تثبيت وstartup، logcat منقح، ونتيجة JNI/native. | لا يثبت المزودات أو Data safety. |
| 3 | QA | نفذ صفوف P0 في `RELEASE_DEVICE_AND_STORE_MATRIX.md`: مشروع→ملف→رفض/موافقة/reopen، عزل A/B، local erase، WorkManager، Browser handoff، Calendar حيث يعلن، والصلاحيات. | فيديو/لقطات منقحة، hash/room/artifact منقح، خطوات ونتيجة لكل صف، وشواهد cancel/fail-safe. | لا يثبت deletion بعيداً أو provider configuration. |
| 4 | Connector owner | قبل أي وصف لمزود، يحدد قائمة المزودات المشمولة ثم يختبر opt-in/opt-out/cancel/error/revoke وحماية السر لكل مزود. | حساب اختبار، وقت/خطوات/نتيجة منقحة، redirect URI عند OAuth، ودليل عدم ظهور secret في UI/log/evidence. | لا يثبت المراجعة القانونية أو Play. |
| 5 | Product/legal owner | يطابق سياسة الخصوصية وData safety والإفصاحات مع behavior الحزمة الموقعة والصلاحيات والمزودات المعلنة. | URL سياسة صالح، نسخة إجابات Data safety، سجل retention/deletion، قائمة processors، وموافقة قانونية. | لا يثبت pre-launch أو جودة الجهاز. |
| 6 | Publisher | ارفع AAB الموقع إلى المسار الصحيح وشغّل Play pre-launch ثم عالج كل blocker. | تقرير pre-launch، versionCode، track، القرار/الإصلاح لكل finding، وسجل rollout. | لا يثبت تبني السوق أو اقتصاد المنتج. |

## 4. قاعدة الدليل والتسمية

لكل بوابة خارجية، يحفظ المالك مجلداً أو تذكرة مرتبطة بـ`commit SHA` و`versionCode` ووقت UTC واسم المالك، ويزيل الأسرار وبيانات المستخدم من اللقطات والسجلات. تُستخدم حالة واحدة فقط لكل بند: `PASS` بدليل، أو `FAIL` مع سبب وخطة تصحيح، أو `BLOCKED` مع اسم المالك والمدخل الناقص. لا تستخدم `READY` أو `DONE` عند غياب أحد عناصر الدليل.

> لا يمكن للالتزامات أو محاكي CI أن تحل محل توقيع محفوظ أو جهاز حقيقي أو حساب مزود أو حساب Play أو مراجعة قانونية. هذه حواجز نشر حقيقية، وليست أعمالاً يمكن تجاوزها بتوسيع شفرة `cp-foundation`.

## 5. قرار النشر

| القرار | الشرط |
|---|---|
| `INTERNAL_CANDIDATE_EVIDENCED` | الأدلة الداخلية وCI والحارسات والـartifact غير الموقّع مكتملة، كما في الحالة الحالية. |
| `SIGNED_RELEASE_CANDIDATE` | بوابة 1 ناجحة مع artifact موقّع وapksigner وmapping وhashes. |
| `PUBLISHER_SUBMISSION_ALLOWED` | بوابات 1–5 ناجحة، ولا يوجد blocker في الجهاز أو المزود أو القانون. |
| `PUBLIC_RELEASE_APPROVED` | قبول الناشر ونتيجة pre-launch/rollout المطلوبة متوفرة. |

## 6. المراجع الداخلية

- `AIRI_RELEASE_CLOSURE.md` هو مصدر ترتيب الإغلاق.
- `RELEASE_AUDIT_REGISTER.md` هو سجل الأدلة والحدود.
- `AIRI_FINAL_CLOSURE_STATUS.md` هو خريطة الحالة المختصرة.
- `RELEASE_DEVICE_AND_STORE_MATRIX.md` هو بروتوكول QA الخارجي المفصل.
- `docs/deployment/BUILD_AND_RELEASE.md` يصف مخرجات CI الموقعة وغير الموقعة.
