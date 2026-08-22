# حد مصدر البحث

**الحالة:** `IMPLEMENTATION_COMPLETE` لحراسة fetch والـbrowser hand-off في `SearchTool`. **`RUNTIME_VERIFICATION_PENDING`** لتشغيل Brave/Jina/DuckDuckGo على شبكة وجهاز حقيقيين. لا يدعي العقد تصفحاً تفاعلياً أو فتح متصفح من agent تلقائياً.

## السلوك المنفذ

`SearchSourcePolicy` يستعمل `BrowserNavigationPolicy` في المسارين اللذين كانا يستقبلان URL:

| المسار | القرار |
|---|---|
| `fetchViaJina` | يقبل عنوان HTTP(S) عاماً فقط ثم يبني Jina Reader URL من العنوان المطبّع |
| `fetchPageContent` | يقبل عنوان HTTP(S) عاماً فقط قبل OkHttp direct fetch |
| عنوان localhost/private/local/internal أو `file:` | يرجع `FetchResult(success=false)` قبل الشبكة |
| `searchViaIntent` | لا يفتح intent؛ يرجع أن user takeover مطلوب |
| `openInBrowser` | لا ينفذ `startActivity` من tool؛ يتطلب سطح UI يتحكم به المستخدم لاحقاً |

لا تسجل السياسة محتوى صفحة أو URL في سجل مستقل. وما يدخل `ResearchAgent` لاحقاً يبقى مقيداً بـ`ResearchEvidencePolicy` كبيانات خارجية غير موثوقة مع citation محدود.

## الفجوات الصريحة

لا توجد بعد واجهة hand-off تعرض رابط البحث للمستخدم وتنفذ اختياره، ولا transport لصفحات authenticated أو uploads أو downloads. لا تتغير حدود redirect/live browser في `CloudBrowserAgent`; يظل ذلك العقد مغطى بسياسة Browser المنفصلة. يجب ألا يعاد تفعيل `startActivity` في SearchTool حتى يربطها UI واضح بموافقة المستخدم.

## الأدلة

| الدليل | التغطية |
|---|---|
| `SearchSourcePolicyTest` | public read، private/file block، external takeover |
| `:app:compileDebugKotlin` | دمج policy في Jina/direct fetch/SearchTool |
