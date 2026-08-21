# مصفوفة منصات AIRI

## طريقة قراءة المصفوفة

تشير حالة الخلية إلى **المنتج على المنصة المحددة**، لا إلى وجود كود مشابه في Android. الحالة `ARCHITECTED` تعني وجود عقد وحدود موثقة فقط. الحالة `IMPLEMENTED` تعني كوداً في target المعني ولم تثبت عملية البناء بعد. الحالة `BUILDS` تتطلب أمراً ناجحاً وartifact. الحالة `RUNTIME_VERIFIED` تتطلب سيناريو استخدام فعلي موثق. أي تحقق يحتاج جهازاً أو حساباً خارج البيئة يسجل `EXTERNAL_VERIFICATION_REQUIRED` لا نجاحاً افتراضياً.

| القدرة | Android | Windows | Linux | Web | الدليل المطلوب للترقية |
| --- | --- | --- | --- | --- | --- |
| بدء التطبيق وواجهة الدردشة | `RUNTIME_VERIFIED` | `PLANNED` | `RUNTIME_VERIFIED` لأساس محلي محدود | `PLANNED` | Linux: نافذة وkeyboard/mouse وإرسال/عرض رد محلي واستعادة سجل؛ Windows/Web ما زالا يحتاجان artifact وتشغيل مستقلين. |
| chat streaming | `RUNTIME_VERIFIED` في مسار Android المرجعي | `PLANNED` | `PLANNED` | `PLANNED` | provider test، ظهور chunks، recovery. |
| إلغاء التنفيذ | `RUNTIME_VERIFIED` لبوابة generation في اختبارات Android/JVM | `PLANNED` | `PLANNED` | `PLANNED` | إلغاء أثناء stream ومنع callback قديم. |
| agent planning/execution | `IMPLEMENTED` في Android؛ عقود الخطة المشتركة تبني في `core-domain` | `ARCHITECTED` | `IMPLEMENTED` لمسار تخطيط محلي حتمي محدود | `ARCHITECTED` | Linux يستهلك `ActionPlan` و`AgentGoal` و`PlanStep` للاستجابة المحلية؛ execution على نظام التشغيل وproviders ما زالا مطلوبين. |
| memory admission | `RUNTIME_VERIFIED` بمنطق Android واختبارات policy | `ARCHITECTED` | `ARCHITECTED` | `PLANNED` | policy تبني في target JVM عام، لكن لا يوجد تطبيق أو artifact أو تحقق Windows/Linux؛ لذلك لا تُرفع حالة المنصتين. |
| RAG retrieval/ranking | `IMPLEMENTED` في Android | `PLANNED` | `PLANNED` | `PLANNED` | dataset fixture والاسترجاع على target. |
| cloud models | `IMPLEMENTED`؛ الاتصال بحسابات حقيقية `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `PLANNED` | provider integration آمن ومثبت لكل target. |
| local models | `IMPLEMENTED` عبر Android JNI؛ فعالية الأجهزة `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `BLOCKED` حتى تثبت دراسة runtime browser | native build + load + inference small model؛ Web يحتاج feasibility منفصلة. |
| model routing/fallback | `IMPLEMENTED` في Android | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | core policy tests ثم provider adapters. |
| skills registry/validation | `IMPLEMENTED` في Android | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | capability manifest ورفض skill غير المدعوم. |
| tools/connectors | `IMPLEMENTED` في Android | `PLANNED` | `PLANNED` | `PLANNED` | صلاحيات منصة واختبارات allowed/denied. |
| attachments validation | `RUNTIME_VERIFIED` لسياسة المرفقات واختباراتها | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | policy في `core-domain` تبني وتختبر على JVM عام وAndroid؛ لا يوجد target product خارجي أو Web target بعد. |
| attachments acquisition | `IMPLEMENTED` Android picker/content resolver | `PLANNED` | `PLANNED` | `PLANNED` | اختيار ملف وdrag/drop ورفض الحجم/MIME. |
| artifacts | `IMPLEMENTED` Android | `PLANNED` | `PLANNED` | `PLANNED` | save/open/delete مع permission audit. |
| persistence | `RUNTIME_VERIFIED` لRoom migrations المحددة | `ARCHITECTED` | `RUNTIME_VERIFIED` لسجل جلسة محلي محدود | `ARCHITECTED` | Linux يكتب ويعيد تحميل سجل رسائل محلي بعد إعادة التشغيل؛ لا يمثل ذلك repository أو migration أو encryption نهائياً. |
| scheduling | `IMPLEMENTED` Android عبر WorkManager؛ التشغيل OS الفعلي `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `PLANNED` | wake/retry/cancel semantics على OS/browser. |
| voice STT/TTS | `IMPLEMENTED` Android؛ mic/devices `EXTERNAL_VERIFICATION_REQUIRED` | `PLANNED` | `PLANNED` | `PLANNED` | permission، capture، transcription/synthesis، cancellation. |
| notifications | `IMPLEMENTED` Android | `PLANNED` | `PLANNED` | `PLANNED` | opt-in، delivery، interaction. |
| authentication OAuth/PKCE | `IMPLEMENTED` Android؛ real IdP `EXTERNAL_VERIFICATION_REQUIRED` | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | state/PKCE/callback/logout with approved IdP. |
| secure token storage | `IMPLEMENTED` Android adapter | `ARCHITECTED` | `ARCHITECTED` | `ARCHITECTED` | platform vault proof وno-secret-in-log test. |
| design system | `IMPLEMENTED` Android assets/UI | `PLANNED` | `PLANNED` | `PLANNED` | token package + visual/accessibility check per platform. |
| keyboard/window behavior | غير منطبق كهدف رئيسي | `PLANNED` | `PLANNED` | `PLANNED` | focus/shortcuts/resizing acceptance tests. |

## دلالة الحالة العامة

| نطاق | الحالة الحالية | سبب التسمية |
| --- | --- | --- |
| AIRI Android | `RUNTIME_VERIFIED` بصورة جزئية ومحددة بالاختبارات الموجودة | لا يتحول هذا الوصف إلى ضمان شامل للمزودين أو hardware أو الحسابات الخارجية. |
| AIRI Core | `BUILDS` لنطاق `core-domain` المحدود | سياسات الذاكرة والمرفقات وعقود التخطيط المشتركة واختباراتها تبني على JVM Desktop وAndroid؛ بقية النواة ما زالت `ARCHITECTED` أو `PLANNED`. |
| AIRI Desktop Linux foundation | `RUNTIME_VERIFIED` لنطاق نافذة محلية محدود | حزمة DEB تبني، والنافذة تقبل keyboard/mouse وتعرض رداً حتمياً وتعيد تحميل سجل جلسة؛ راجع `GATE_DESKTOP_LINUX.md`. |
| AIRI Desktop Windows | `PLANNED` | لا يوجد package أو runtime artifact أو دليل تفاعل على Windows. |
| AIRI Web | `PLANNED` | لا يوجد target أو security architecture منفذة. |

## ضوابط منع الدعم الوهمي

لا يُعرض skill لمنصة ما إلا إذا تطابقت `platforms` في manifest مع target فعلي، وكانت جميع permissions وnetwork/filesystem/model requirements قابلة للتحقق. ولا يعرض picker أو local-model button أو scheduler على Windows/Linux/Web قبل وجود adapter قابل للبناء واختبار قبول لهذه القدرة. تتبع تجربة الاستخدام هذه المصفوفة ولا تتقدم عليها.

لا يمكن تحويل `PLANNED` أو `ARCHITECTED` إلى `IMPLEMENTED` بمراجعة يدوية فقط. يجب ربط التغيير بـcommit، ومسار source-set، وأمر الاختبار، وartifact أو log تشغيل مناسب في جدول أدلة Gate التالي.
