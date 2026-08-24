# تسليم بوابات نشر AIRI

> **الحالة:** `FEATURE_FREEZE / SIGNED_RELEASE_CANDIDATE_EVIDENCED / EXTERNAL_GATE_HANDOFF`.
>
> هذا المستند هو قائمة التنفيذ الوحيدة لما لا يستطيع فرع `cp-foundation` أو CI إثباته. لا يجوز تحويل أي صف إلى `PASS` دون evidence منقح محفوظ تحت سجل إصدار. التوقيع المكتمل لا يساوي قبول Play أو تحقق جهاز أو موافقة قانونية.

## 1. نقطة البداية الموقعة

المرشح الموقع الحالي هو الالتزام `ca881a1b` على `cp-foundation` و`main`. بعد تحقيق هوية التوقيع الذي ثبت عدم وجود هوية إصدار سابقة قابلة للاستعادة، أنشئت هوية production جديدة مع نسخة recovery مشفرة خارج GitHub ومملوكة للمالك فقط. لم تدخل مادة خاصة إلى Git أو CI logs أو هذا المستند.

GitHub Actions run [`32783660291`](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32783660291) اجتازت contracts والتوطين وshared-core/debug/unit/lint وrelease compile وsigned packaging و`apksigner` وAPI 29 instrumentation وفحص native. أصلح commit `ca881a1b` عائق runner السابق باكتشاف `apksigner` من Android build-tools بدلاً من افتراضه في PATH.

| عنصر الدليل | القيمة المتحققة | الحد الصريح |
|---|---|---|
| APK موقّع | `app-release.apk`، SHA-256: `2faf1ec9d269d58eb93c98379bf93a2fc25b4a449c6f1a6bd2c36275e32b4b98` | لا يثبت تثبيت جهاز أو قبول Play. |
| AAB موقّع | `app-release.aab`، SHA-256: `f8816b28d7751ab974fe9a0a064baf18e9558a6a94415098e784f720b40eb30a` | لا يثبت رفعاً إلى Play أو rollout. |
| R8 mapping | `mapping.txt`، 815,949 سطراً، SHA-256: `c988f131c9f44200a0b7be7419696d1ebadccce667ec019a7b72b8384b987b1c` | يجب حفظه محفوظاً مع كل build لاحق، ولا يوضع في مستودع عام إن تضمن رموزاً حساسة. |
| شهادة APK | V2 signer: `CN=AIRI Android Release, OU=Release Engineering, O=AIRI, L=Khartoum, ST=Khartoum, C=SD`؛ SHA-256: `EE:B5:1E:58:A3:71:85:F8:EC:1A:48:77:64:8F:9A:59:69:61:49:E7:0D:39:56:64:DF:F5:91:9C:82:C2:1A:F8`. | fingerprint يثبت artifact المفحوص، ولا يكشف المفتاح الخاص. |
| سلامة AAB | `unzip -t` بلا أخطاء في البيانات المضغوطة. | لا يتحقق من Play validation أو runtime. |

## 2. نطاق الإصدار المجمّد

يركز هذا الإصدار على الرحلة المحلية المحددة وموافقاتها وخصوصيتها. أسطح الدفع وسجل الفوترة وStripe والمتجر ومهارات المجتمع معطلة في Feature Freeze؛ routes القديمة أو المستعادة تعود إلى شاشة محلية آمنة، وmanifest يستخدم `tools:node="remove"` لإزالة إذن Billing المدمج. لا يعيد المالك هذه الأسطح قبل برنامج تجاري مستقل يملك مزوداً فعلياً وبيانات قانونية وPlay evidence.

| السطح | قرار هذا الإصدار | شرط إعادة الإدراج لاحقاً |
|---|---|---|
| Paywall / Google Play Billing | مستبعد ومحجوب. | حساب ناشر ومنتجات فعلية ومسار purchase/cancel/restore وسياسة وData Safety وPlay review. |
| Stripe / credits | مستبعد ومحجوب. | مفاتيح production منفصلة وweb checkout/cancel/error/revoke حي وسياسة قانونية ومراجعة متجر. |
| Marketplace / Community skills | مستبعد ومحجوب. | هوية ناشر للمحتوى وmoderation/revocation وattribution/licensing ودليل device/store. |
| Firebase / OAuth / connectors | لا مزود يعتبر متحققاً تلقائياً. | يعلن المالك المزودات المشمولة بالاسم ثم يثبت consent/cancel/revoke/error لكل منها. |

## 3. تسلسل التنفيذ الإلزامي من هذه النقطة

| الترتيب | المالك | الإجراء | evidence المطلوب للقبول | ما لا يثبته الإجراء |
|---:|---|---|---|---|
| 1 | Release engineer + QA | ثبت APK الموقع على API 26 وAPI 35/36 حقيقيين، ABI `arm64-v8a`. | اسم الجهاز/API/ABI/build، نتيجة تثبيت وstartup، logcat منقح، ونتيجة JNI/native. | لا يثبت المزودات أو Data Safety. |
| 2 | QA | نفذ صفوف P0 في `RELEASE_DEVICE_AND_STORE_MATRIX.md`: مشروع→ملف→رفض/موافقة/reopen، عزل A/B، local erase، WorkManager، Browser handoff، Calendar حيث يعلن، والصلاحيات. | فيديو/لقطات منقحة، خطوات ونتيجة لكل صف، وشواهد cancel/fail-safe. | لا يثبت deletion بعيداً أو provider configuration. |
| 3 | Connector owner | قبل أي وصف لمزود، يحدد قائمة المزودات المشمولة ثم يختبر opt-in/opt-out/cancel/error/revoke وحماية السر لكل مزود. | حساب اختبار، وقت/خطوات/نتيجة منقحة، redirect URI عند OAuth، ودليل عدم ظهور secret في UI/log/evidence. | لا يثبت المراجعة القانونية أو Play. |
| 4 | Product/legal owner | يطابق سياسة الخصوصية وData Safety والإفصاحات مع behavior الحزمة الموقعة والصلاحيات والمزودات المعلنة. | URL سياسة صالح، نسخة إجابات Data Safety، سجل retention/deletion، قائمة processors، وموافقة قانونية. | لا يثبت pre-launch أو جودة الجهاز. |
| 5 | Publisher | ارفع AAB الموقع إلى المسار الصحيح وشغّل Play pre-launch ثم عالج كل blocker. | تقرير pre-launch، versionCode، track، القرار/الإصلاح لكل finding، وسجل rollout. | لا يثبت تبني السوق أو اقتصاد المنتج. |

## 4. قاعدة الدليل والتسمية

لكل بوابة خارجية، يحفظ المالك مجلداً أو تذكرة مرتبطة بـ`commit SHA` و`versionCode` ووقت UTC واسم المالك، ويزيل الأسرار وبيانات المستخدم من اللقطات والسجلات. تستخدم حالة واحدة فقط لكل بند: `PASS` بدليل، أو `FAIL` مع سبب وخطة تصحيح، أو `BLOCKED` مع اسم المالك والمدخل الناقص. لا تستخدم `READY` أو `DONE` عند غياب أحد عناصر الدليل.

> لا يمكن للالتزامات أو محاكي CI أن تحل محل جهاز حقيقي أو حساب مزود أو حساب Play أو مراجعة قانونية. هذه حواجز نشر حقيقية، وليست أعمالاً يمكن تجاوزها بتوسيع شفرة `cp-foundation`.

## 5. قرار النشر

| القرار | الشرط |
|---|---|
| `SIGNED_RELEASE_CANDIDATE_EVIDENCED` | artifact موقّع و`apksigner` وmapping وhashes وCI داخلي ناجح؛ هذه هي الحالة الحالية. |
| `PUBLISHER_SUBMISSION_ALLOWED` | بوابات الجهاز والمزود المعلن والقانون/الخصوصية ناجحة ولا يوجد blocker. |
| `PUBLIC_RELEASE_APPROVED` | قبول الناشر ونتيجة pre-launch/rollout المطلوبة متوفرة. |

## 6. المراجع الداخلية

- `AIRI_RELEASE_CLOSURE.md` هو مصدر ترتيب الإغلاق.
- `RELEASE_AUDIT_REGISTER.md` هو سجل الأدلة والحدود.
- `AIRI_FINAL_CLOSURE_STATUS.md` هو خريطة الحالة المختصرة.
- `RELEASE_DEVICE_AND_STORE_MATRIX.md` هو بروتوكول QA الخارجي المفصل.
- `docs/deployment/BUILD_AND_RELEASE.md` يصف مخرجات CI الموقعة وغير الموقعة.
