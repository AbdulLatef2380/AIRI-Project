# Google OAuth الرسمي: نتائج التحقق

## ما يثبته التوثيق

1. Google تفصل بين تسجيل الهوية وتفويض بيانات المستخدم. توصي باستخدام Credential Manager للهوية و`AuthorizationClient` لتفويض Google API scopes. يجب طلب النطاق عند تنفيذ الإجراء الذي يحتاجه، لا عند تسجيل الهوية فقط.
2. مسار `AuthorizationClient.authorize()` قد يعيد `PendingIntent` يحتاج إلى تشغيل واجهة موافقة النظام. لذلك يجب استخدام سياق Activity/عرض عند بدء التفويض. استخدام Application context في هذا الموضع قد يؤدي إلى فشل مزود عام على الجهاز.
3. `GetGoogleIdOption.setServerClientId()` يجب أن يستقبل Web application OAuth client ID، وليس Android client ID. قيمة `default_web_client_id` الرسمية تُولّد من `google-services.json`.
4. Google/Firebase يتطلبان تسجيل package name وبصمة SHA-1 لشهادة توقيع التطبيق. يجب تسجيل بصمة كل توزيع مستخدم فعليًا، بما في ذلك debug أو مفتاح CI أو Google Play App Signing عند اختلافها. بعد تغييرها يجب تنزيل `google-services.json` المحدث.
5. `NoCredentialException` تعني غالبًا عدم وجود حساب مصرح مسبقًا؛ المسار الصحيح هو إعادة المحاولة مع `setFilterByAuthorizedAccounts(false)`. أما أخطاء `GetCredentialCancellationException` غير المتوقعة فقد تشير أيضًا إلى misconfiguration، ولا ينبغي إعادة المحاولة تلقائيًا بلا تدخل المستخدم.
6. نطاقات Gmail وDrive الواسعة مثل `gmail.readonly` و`drive.metadata.readonly` مصنفة Restricted وفق توثيق Google، وقد تتطلب OAuth verification/security assessment بحسب طريقة التخزين أو النقل. يجب إبقاء أقل نطاق ممكن وتسجيله في OAuth consent screen.

## الروابط الرسمية

- [1] https://developer.android.com/identity/authorization "Android authorization overview"
- [2] https://developers.google.com/identity/sign-in/android/authorize-access "Authorize access to Google user data on Android"
- [3] https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation "Implement Sign in with Google with Credential Manager"
- [4] https://developer.android.com/identity/sign-in/credential-manager-troubleshooting-guide "Credential Manager troubleshooting"
- [5] https://firebase.google.com/docs/auth/android/google-signin "Firebase Google Sign-In for Android"
- [6] https://developers.google.com/workspace/guides/configure-oauth-consent "Configure OAuth consent screen and scopes"
- [7] https://developers.google.com/workspace/gmail/api/auth/scopes "Gmail API scopes"
- [8] https://developers.google.com/workspace/drive/api/guides/api-specific-auth "Drive API authorization"

## نتيجة فحص AIRI

`app/google-services.json` يحتوي على عميل Android للحزمة `com.airi.assistant` وعميل Web واحد، ومعرّف Web المستخدم في Credential Manager مطابق لعميل Web في الملف. كان التطبيق يكرر المعرّف في ملفات الترجمات، فتمت إزالة النسخ الثابتة للاعتماد على المورد الذي يولده Google Services من ملف الإعداد الرسمي.

تظل مطابقة SHA-1 مسؤولية إعداد Firebase/Google Cloud. لا يمكن استنتاج بصمة APK الموزع أو تسجيلها من داخل التطبيق، لذلك يجب التأكد من تسجيل بصمة debug/CI/Play Signing المناسبة في Google Cloud وFirebase ثم تنزيل ملف الإعداد المحدث.
