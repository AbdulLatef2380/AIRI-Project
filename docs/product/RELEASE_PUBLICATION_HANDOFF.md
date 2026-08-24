# تسليم بوابات نشر AIRI

> **الحالة:** `FEATURE_FREEZE / SIGNING_SECRETS_BLOCKED / EXTERNAL_GATE_HANDOFF`.
>
> هذا المستند هو قائمة التنفيذ الوحيدة لما لا يستطيع فرع `cp-foundation` أو محاكي CI إثباته. لا يجوز تحويل أي صف إلى `PASS` من دون evidence منقح محفوظ تحت سجل إصدار، ولا يجوز اعتبار أي APK/AAB غير موقّع صالحاً للتثبيت أو النشر.

## 1. نقطة البداية المثبتة

المرشح الداخلي الحالي هو الالتزام `ec359054` على `cp-foundation`. بنيت GitHub Actions run [`32761300619`](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32761300619) المسار غير الموقّع كاملاً: الحارسات 80/80 والتوطين الصارم، shared-core/debug/unit/lint، `assembleRelease` و`bundleRelease` مع R8، APK badging، فحص ZIP للـAAB، `mapping.txt`، instrumentation API 29، وفحص native. لم يرقَّ هذا الالتزام إلى `main` ولم تُشغل عليه بوابة signing.

| عنصر الدليل البنيوي | القيمة المتحققة في artifact غير الموقّع | الحد الصريح |
|---|---|---|
| APK | `app-release-unsigned.apk`، SHA-256: `2496de134f9d7de4248ded074e5831a697e82ed0f0ad450b5e7f41e8114806e6` | لا توقيع ولا تثبيت ولا توزيع. |
| AAB | `app-release.aab`، SHA-256: `1e0c56fdc930b2cf612c1ec8014749f85bf1f76ee9dd416bd1c1ff3a83ec3b50` | لا Play upload ولا هوية ناشر. |
| R8 mapping | `mapping.txt`، 815,949 سطراً، SHA-256: `c988f131c9f44200a0b7be7419696d1ebadccce667ec019a7b72b8384b987b1c` | لا mapping نهائي قبل تشغيل signed `main`. |
| Manifest المرصود | `com.airi.assistant`، `versionCode=1`، `minSdk=26`، `targetSdk=36`، `arm64-v8a`، وإذن Billing غير موجود في APK المفحوص. | لا يغني عن manifest الحزمة الموقعة أو device install. |

## 1.1 نتيجة بوابة التوقيع على main

رُقّي commit المرشح `fe3fb68b` إلى `main` بتفويض المالك، وشُغلت GitHub Actions run [`32742046966`](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32742046966). اجتاز التشغيل الحارسات وdebug/unit/lint وrelease-source وinstrumentation API 29 وفحص native، لكنه لم ينشئ artifact موقعاً: الخطوة المنشورة سجلت `RELEASE_SIGNING_READY=false` وتخطت packaging و`apksigner` كما يجب. التحقيق لم يعثر على هوية إصدار قابلة للاستعادة في شجرة العمل أو تاريخ Git أو أصول الإصدار؛ الأصل السابق الوحيد APK debug بشهادة `Android Debug`. لذلك ليس من الآمن افتراض وجود keystore قديم. السبب التشغيلي المثبت هو أن واحداً أو أكثر من المدخلات الآمنة التالية غير مهيأ: `KEYSTORE_BASE64` و`STORE_PASSWORD` و`KEY_ALIAS` و`KEY_PASSWORD`.

| بوابة | النتيجة | الإجراء التالي المسموح |
|---|---|---|
| ترقية main | `PASS` تاريخياً للالتزام `fe3fb68b` فقط؛ تمت fast-forward بلا merge commit. | يتطلب المرشح `ec359054` تفويض release-owner جديد قبل أي ترقية؛ لا تدمج أو تجبر الفرع. |
| CI الداخلي على main | `PASS` تاريخياً للمرشح القديم؛ كل البوابات غير الموقعة/المحاكي/native نجحت في run `32742046966`. | لا يمثل هذا دليل الشفرة الأحدث على `cp-foundation`. |
| توقيع الحزمة | `BLOCKED`؛ لا APK/AAB موقعة ولا apksigner/mapping/hash نهائية، ولا هوية إصدار قابلة للاستعادة ظهرت في التحقيق. | يحدد مالك الإصدار backup خاصاً خارج GitHub/Manus، ثم ينشئ أو يستورد هوية ثابتة ويهيئ الأسرار الأربعة داخل GitHub قبل تشغيل workflow على `main` المرشح المفوض. |

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
| 1 | Release owner + release engineer | بعد تفويض fast-forward للمرشح `ec359054` فقط، شغّل workflow المحمي على `main` مع أسرار signing المعتمدة فقط. | SHA/قرار الترقيـة، APK/AAB موقعان، `mapping.txt`، `SHA256SUMS`، مخرجات `apksigner verify --verbose` و`--print-certs`، `versionCode` نهائي، ورابط CI. | لا يثبت تجربة المستخدم أو Play acceptance. |
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
