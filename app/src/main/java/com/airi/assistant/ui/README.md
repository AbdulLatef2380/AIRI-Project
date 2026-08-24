# واجهة AIRI Compose

تملك هذه الحزمة شاشات Compose والتنقل والثيم والموارد المحلية وعرض الحالة. لا تكون Compose state مصدر الحقيقة للمهام أو الموافقات أو الملفات أو artifacts؛ تعرض الواجهة projections مملوكة من ViewModel وmanagers الدائمة في AIRI Android على `cp-foundation`.

## مسارات الواجهة الحالية

| السطح | العقد المرئي |
|---|---|
| Composer والدردشة | `/` يختار skill و`@` يختار معرفة صالحة؛ تتحول الاختيارات إلى directives يعيد ViewModel التحقق منها ولا تُعرض كأنها نص مستخدم. Stop يلغي generation owner وليس مجرد أيقونة. |
| Project وLibrary | تعرض الموارد المملوكة للمشروع فقط. proposal لتعديل ملف خاص يطلب اختيار task عند الغموض ثم review/Trust Center؛ evidence الناتج مربوط بـproject/task/run/step عند نجاح المسار المملوك. |
| Trust Center وExecution Center | approval وrun/step معروضان كحالة durable، لا كنجاح واجهة. الرفض لا يطبق التعديل، والنجاح لا يعاد تلقائياً بعد claim. |
| Agent Tasks | jobs المجدولة تعرض outcomes محفوظة وlink إلى durable task. `runNow` يحتاج تأكيداً ولا يظهر لمعرف صيانة النظام المحجوز. |
| الإعدادات والصلاحيات | الإذن يطلب من فعل مستخدم ظاهر. أسطح الدفع والفوترة والمتجر وCommunity Skills محجوبة fail-closed خلال Feature Freeze. |

## التوطين والمظهر

المسارات الخاضعة للحارس تستخدم موارد en/ar/es/zh مع parity صارم، وinput يستخدم `TextAlign.Start` المنطقي. لا يستبدل نجاح resource parity تدقيق TalkBack أو RTL/LTR أو dark mode أو font scale أو touch targets على جهاز حقيقي.

## حدود التحقق

CI تغطي compile والاختبارات وlint والترجمة وinstrumentation المتاح. visual state وpermission Settings return وprocess recreation وسلوك accessibility/large fonts على API/ABI واقعي تبقى `RUNTIME_VERIFICATION_PENDING`. لا تُحوّل الشاشة أو route وحدها إلى دليل مزود خارجي أو دفع أو نشر.
