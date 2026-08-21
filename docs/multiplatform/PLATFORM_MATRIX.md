# مصفوفة منصات AIRI

## طريقة قراءة المصفوفة

تشير حالة الخلية إلى **المنتج على المنصة المحددة**، لا إلى وجود كود مشابه في Android. الحالة `ARCHITECTED` تعني وجود عقد وحدود موثقة فقط. الحالة `IMPLEMENTED` تعني كوداً في target المعني ولم تثبت عملية البناء بعد. الحالة `BUILDS` تتطلب أمراً ناجحاً وartifact. الحالة `RUNTIME_VERIFIED` تتطلب سيناريو استخدام فعلي موثق. أي تحقق يحتاج جهازاً أو حساباً خارج البيئة يسجل `EXTERNAL_VERIFICATION_REQUIRED` لا نجاحاً افتراضياً.

| القدرة | Android | Windows | Linux | Web | الدليل المطلوب للترقية |
| --- | --- | --- | --- | --- | --- |
| بدء التطبيق وواجهة الدردشة | `RUNTIME_VERIFIED` | `BUILDS` | `RUNTIME_VERIFIED` لأساس محلي محدود | `PLANNED` | Windows: MSI واختبارات adapter نجحت في CI لكن launch/input/response/restart تتطلب host خارجي؛ Linux: نافذة وkeyboard/mouse وإرسال/عرض رد محلي واستعادة سجل. |
| chat streaming | `RUNTIME_VERIFIED` في مسار Android المرجعي | `PLANNED` | `PLANNED` | `PLANNED` | provider test، ظهور chunks، recovery. |
| إلغاء التنفيذ | `RUNTIME_VERIFIED` لبوابة generation في اختبارات Android/JVM | `PLANNED` | `PLANNED` | `PLANNED` | إلغاء أثناء stream ومنع callback قديم. |
| agent planning/execution | `IMPLEMENTED` في Android؛ عقود الخطة المشتركة تبني في `core-domain` | `BUILDS` لحالة Desktop محكومة بالقدرات | `RUNTIME_VERIFIED` لدورة نافذة محلية محدودة | `ARCHITECTED` | Desktop يعرض حالة إعداد نموذج صريحة ولا يدّعي تنفيذ agent أو inference قبل adapter متحقق. |
| memory admission | `RUNTIME_VERIFIED` بمنطق Android واختبارات policy | `ARCHITECTED` | `ARCHITECTED` | `PLANNED` | policy تبني في target JVM عام، لكن لا يوجد تطبيق أو artifact أو تحقق Windows/Linux؛ لذلك لا تُرفع حالة المنصتين. |
| RAG retrieval/ranking | `IMPLEMENTED` في Android | `PLANNED` | `PLANNED` | `PLANNED` | dataset fixture والاسترجاع على target. |
| cloud models | `IMPLEMENTED`؛ الاتصال بحسابات حقيقية `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `PLANNED` | provider integration آمن ومثبت لكل target. |
| local models | `IMPLEMENTED` عبر Android JNI؛ فعالية الأجهزة `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `BLOCKED` حتى تثبت دراسة runtime browser | native build + load + inference small model؛ Web يحتاج feasibility منفصلة. |
| model routing/fallback | `IMPLEMENTED` في Android | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | core policy tests ثم provider adapters. |
| skills registry/validation | `IMPLEMENTED` في Android | `BUILDS` لعقد registry وواجهة gating | `BUILDS` لعقد registry وواجهة gating | `ARCHITECTED` | selection policy والـplatform gate يرفضان skill غير جاهز؛ التنفيذ Desktop ما زال `PLANNED`. |
| tools/connectors | `IMPLEMENTED` في Android | `PLANNED` | `PLANNED` | `PLANNED` | صلاحيات منصة واختبارات allowed/denied. |
| attachments validation | `RUNTIME_VERIFIED` لسياسة المرفقات واختباراتها | `BUILDS` | `BUILDS` | `ARCHITECTED` | Desktop يختبر حدود الحجم والنسخ إلى تخزين AIRI الخاص والتنظيف؛ تفاعل picker OS ما زال خارجيًا. |
| attachments acquisition | `IMPLEMENTED` Android picker/content resolver | `EXTERNAL_VERIFICATION_REQUIRED` | `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | Desktop يضم adapter للـnative picker، لكن اختيار ملف تفاعلي وفحص العرض يحتاج قبول OS. |
| artifacts | `IMPLEMENTED` Android | `PLANNED` | `PLANNED` | `PLANNED` | save/open/delete مع permission audit. |
| persistence | `RUNTIME_VERIFIED` لRoom migrations المحددة | `BUILDS` لسجل جلسة محلي محدود | `RUNTIME_VERIFIED` لسجل جلسة محلي محدود | `ARCHITECTED` | Windows يبني ويختبر adapter السجل؛ Linux يكتب ويعيد تحميل سجل رسائل محلي بعد إعادة التشغيل؛ لا يمثل ذلك repository أو migration أو encryption نهائياً. |
| scheduling | `IMPLEMENTED` Android عبر WorkManager؛ التشغيل OS الفعلي `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `PLANNED` | wake/retry/cancel semantics على OS/browser. |
| voice STT/TTS | `IMPLEMENTED` Android؛ mic/devices `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `PLANNED` | permission، capture، transcription/synthesis، cancellation. |
| notifications | `IMPLEMENTED` Android | `PLANNED` | `PLANNED` | `PLANNED` | opt-in، delivery، interaction. |
| authentication OAuth/PKCE | `IMPLEMENTED` Android؛ real IdP `EXTERNAL_VERIFICATION_REQUIRED` | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | state/PKCE/callback/logout with approved IdP. |
| secure token storage | `IMPLEMENTED` Android adapter | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | platform vault proof وno-secret-in-log test. |
| design system | `IMPLEMENTED` Android assets/UI | `BUILDS` | `RUNTIME_VERIFIED` لدورة النافذة فقط | `PLANNED` | Desktop tokens والاختصارات والحد الأدنى للنافذة تبني؛ تحقق visual/accessibility تفاعلي مطلوب لكل OS. |
| keyboard/window behavior | غير منطبق كهدف رئيسي | `EXTERNAL_VERIFICATION_REQUIRED` | `BUILD_VERIFIED` مع lifecycle runtime | `PLANNED` | اختصارات Ctrl+N وCtrl+K وEsc مختبرة كوحدة؛ قبول focus/resize/keyboard التفاعلي مطلوب على Linux وWindows. |

## دلالة الحالة العامة

| نطاق | الحالة الحالية | سبب التسمية |
| --- | --- | --- |
| AIRI Android | `RUNTIME_VERIFIED` بصورة جزئية ومحددة بالاختبارات الموجودة | لا يتحول هذا الوصف إلى ضمان شامل للمزودين أو hardware أو الحسابات الخارجية. |
| AIRI Core | `BUILDS` لنطاق `core-domain` المحدود | سياسات الذاكرة والمرفقات وعقود التخطيط المشتركة واختباراتها تبني على JVM Desktop وAndroid؛ بقية النواة ما زالت `ARCHITECTED` أو `PLANNED`. |
| AIRI Desktop Linux foundation | `RUNTIME_VERIFIED` لنطاق نافذة محلية محدود | نجح في revision `111db507` كل من `:app-desktop:test` و`:app-desktop:packageDeb` وأنتج `airi_1.0.0-1_amd64.deb` بحجم 85,129,104 bytes. الحالة لا تدعي نموذج Desktop جاهزاً أو رداً مولداً. |
| AIRI Desktop Windows | `BUILDS` مع `PROCESS_VERIFIED` | أنشأ runner Windows MSI وشغّل smoke install/process بنجاح في [run #32503760476](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32503760476). لا يُنتج Linux ملف MSI حتى إن نجحت مهمة Gradle الاسمية؛ launch/render/input/response/persistence على جهاز Windows تبقى `EXTERNAL_VERIFICATION_REQUIRED`. راجع `GATE_DESKTOP_WINDOWS.md`. |
| AIRI Web | `PLANNED` | لا يوجد target أو artifact أو قبول متصفح؛ مسار Wasm/Compose والحدود الأمنية موثقان في `WEB_FEASIBILITY_DECISION.md`. |

## ضوابط منع الدعم الوهمي

لا يُعرض skill لمنصة ما إلا إذا تطابقت `platforms` في manifest مع target فعلي، وكانت جميع permissions وnetwork/filesystem/model requirements قابلة للتحقق. ولا يعرض picker أو local-model button أو scheduler على Windows/Linux/Web قبل وجود adapter قابل للبناء واختبار قبول لهذه القدرة. تتبع تجربة الاستخدام هذه المصفوفة ولا تتقدم عليها.

لا يمكن تحويل `PLANNED` أو `ARCHITECTED` إلى `IMPLEMENTED` بمراجعة يدوية فقط. يجب ربط التغيير بـcommit، ومسار source-set، وأمر الاختبار، وartifact أو log تشغيل مناسب في جدول أدلة Gate التالي.
