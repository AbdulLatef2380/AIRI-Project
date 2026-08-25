# جرد مصدرّي لتحضير Google Play وData Safety

> **الحالة:** `SOURCE_DERIVED / OWNER_AND_LEGAL_REVIEW_REQUIRED`.
>
> هذه المسودة تحضّر مدخلات قابلة للتدقيق من artifact والمصدر. لا تمثل إجابات Data Safety مُعتمدة، ولا سياسة خصوصية، ولا تفويضاً قانونياً، ولا دليلاً على نقل بيانات وقت التشغيل.

## 1. هوية الحزمة المرجعية

| الحقل | القيمة المثبتة |
|---|---|
| الحزمة | `com.airi.assistant` |
| versionCode / versionName | `1` / `1.0` |
| minSdk / targetSdk | `26` / `36` |
| artifact المرجعي | Android CI run `32805967708` عند `8868656e` |
| APK SHA-256 | `cec8f92801d897619c110eef14434a747fa8190df8c326e62da9ac337d575f6e` |
| AAB SHA-256 | `d8ad5ee0f12282e245d4f0004b1f606f3e7dd04a0d340940235b5e98ec2b10a7` |
| شهادة APK SHA-256 | `EE:B5:1E:58:A3:71:85:F8:EC:1A:48:77:64:8F:9A:59:69:61:49:E7:0D:39:56:64:DF:F5:91:9C:82:C2:1A:F8` |

## 2. أسطح manifest التي تتطلب قراراً قبل إرسال Data Safety

| السطح المصدرّي | الملاحظة الثابتة | قرار المالك/القانون المطلوب |
|---|---|---|
| `INTERNET` | التطبيق يضم مسارات نماذج وموصلات اختيارية. | أعلن بالاسم كل endpoint أو processor مشمول في first release، أو أبقه خارج النطاق والحالة fail-closed. |
| `CAMERA` | يستخدم لإرفاق الصور من زر الدردشة. | قرر هل يمكن حفظ الصورة محلياً فقط أو إرسالها إلى أي مزود، ثم طابق Data Safety والغرض والاحتفاظ. |
| `RECORD_AUDIO` وforeground microphone | أسطح STT/voice/hotword موجودة بعد فعل المستخدم. | حدد معالجة الصوت، وجهة النقل، مدة الاحتفاظ، وسياسة الإلغاء/الحذف. |
| `READ_CALENDAR` / `WRITE_CALENDAR` | مسار calendar محكوم بموافقة Trust Center. | لا تعلن أو تفعّل مزوداً إلا بعد live consent/revoke/device evidence؛ وإلا أزله من first release أو اذكره بدقة بعد مراجعة قانونية. |
| `READ_CONTACTS` | Contacts connector اختياري في المصدر. | قرر إدراجه أو إزالته من first-release journey، مع justification واضح واختبار denial. |
| `POST_NOTIFICATIONS` | طلب اختياري بعد فعل مستخدم. | جهز وصف القنوات وإلغاء الاشتراك، وتحقق منه على جهاز. |
| `BIND_ACCESSIBILITY_SERVICE` | surface معلن يحتاج disclosure وتشغيل يدوي. | راجع ملاءمته لسياسات Play وإفصاح accessibility قبل أي إرسال. |
| storage legacy permissions | `READ_EXTERNAL_STORAGE` و`WRITE_EXTERNAL_STORAGE` ظاهرتان في manifest. | راجع ضرورتها الفعلية ونطاق SDK/Play قبل submission؛ لا تدّع قبول Play. |

## 3. الخصوصية والاحتفاظ من المصدر

| الموضوع | الدليل المصدرّي | حد الدليل |
|---|---|---|
| النسخ الاحتياطي | `android:allowBackup="false"`. | لا يثبت كل مسار نقل أو استرجاع على جهاز. |
| Analytics وCrash reporting | حالات consent الافتراضية للـanalytics وcrash وagent telemetry تساوي `false`؛ التمكين/الإلغاء يمر عبر `TelemetryConsentStore`. | لا يثبت runtime network أو إعداد Firebase Console أو صحة disclosure القانوني. |
| Firebase collection | المصدر يستدعي تعطيل/تمكين Analytics وCrashlytics خلف consent. | يلزم owner مراجعة كل SDK مضمّن وكل processor فعلي، واختبار حي إذا أصبح ضمن الإصدار. |
| المحو المحلي | `DataDeletionCoordinator.eraseLocalData()` موجود لمسح البيانات المحلية، ومسار حذف الحساب منفصل. | لا يثبت المحو على جهاز ولا حذف بيانات مزود خارجي. |
| الأسرار والمرفقات | security scan يثبت تخزين المرفقات داخل app-private `filesDir` وعدم حفظ picker URIs في metadata. | لا يثبت التشفير في الراحة أو عدم نقل المحتوى عند اختيار مزود حي. |

## 4. حالة Play Console ومواد النشر

فحص Play Console read-only وصل إلى صفحة تسجيل الدخول؛ لا توجد جلسة ناشر مصادق عليها أو قائمة تطبيقات أو إمكانية upload متاحة. كما لم يُعثر في `public/` أو `docs/` على سياسة خصوصية منشورة، أو إجابات Data Safety مُعتمدة، أو مواد store listing جاهزة. لذلك لا يجوز البدء في upload أو submission أو الادعاء بأن listing مكتمل.

| بند Play | الحالة | ما يلزم |
|---|---|---|
| حساب ناشر وملكية الحزمة | `EXTERNAL_OWNER_AUTH_REQUIRED` | تسجيل دخول مالك Play Console وتأكيد أن الحزمة/versionCode مقبولان. |
| سياسة خصوصية عامة | `MISSING / LEGAL_REQUIRED` | URL عام صالح وسياسة معتمدة تطابق الممارسات الفعلية والمزودات. |
| إجابات Data Safety | `DRAFT_INPUT_ONLY / LEGAL_REQUIRED` | إجابات owner/legal بعد تثبيت قائمة البيانات والمزودات والاحتفاظ والنقل. |
| Store listing | `MISSING / PRODUCT_OWNER_REQUIRED` | اسم، وصف، فئة، أيقونة، screenshots، contact email، ومحتوى audience/rating عند الاقتضاء. |
| Pre-launch وrollout | `EXTERNAL_OWNER_AUTH_REQUIRED` | رفع AAB بعد اكتمال device/provider/privacy gates، ثم معالجة findings. |

## 5. ترتيب الإغلاق قبل Play submission

1. أكمل صفوف P0 على هاتف ARM64 API 26 وهاتف ARM64 API 35 أو 36، واحتفظ بدليل منقح لكل نتيجة.
2. يثبت connector owner قائمة المزودات التي ستكون مفعلة في الإصدار الأول، ثم ينفذ consent/cancel/revoke/error live لكل مزود.
3. يراجع product/legal owner الجداول أعلاه ويصدر سياسة خصوصية ونسخة إجابات Data Safety وقائمة processors والاحتفاظ والحذف.
4. يسجل publisher في Play Console، يربط listing بالـAAB ذي hash الموثق، ويشغل pre-launch قبل أي rollout.

> لا يستبدل نجاح CI أو فحص المصدر أي عنصر من هذه الأدلة الخارجية.
