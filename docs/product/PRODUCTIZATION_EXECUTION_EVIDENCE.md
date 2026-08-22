# أدلة تنفيذ برنامج Productization

## نطاق الدليل

يغطي هذا السجل تنفيذ برنامج المنتج على فرع `cp-foundation` حتى revision `b7fe2f2ef8b7f62f324c52495abcd75ad17f425c`. لا يغير هذا السجل حالة `architecture-refactor` ولا يمثل دمجاً بين الفرعين. وهو يفرق بين نتيجة المصدر والاختبارات الآلية وبين تحقق التشغيل على جهاز فعلي أو نشر إنتاجي.

## التغييرات المنفذة

| Commit | التسليم | الدليل الآلي | الحالة |
|---|---|---|---|
| `ae562415` | برنامج Productization وحارس وصول لمسار تنفيذ المهارات. | `SkillInvocationAccessPolicyTest` نجح محلياً. | `TESTED` |
| `b355baf3` | إعادة استخدام مهام الصيانة الدورية ذات المعرف الثابت، وحارس scheduler. | `airi_scheduler_health.py` و`:app:compileDebugKotlin` نجحا. | `BUILD_VERIFIED` |
| `6b0bc2b2` | تصحيح metadata حجم المرفق وإضافة اختبار marker. | `AttachmentPolicyTest` نجح محلياً. | `TESTED` |
| `59f7d12a` | إرسال النص الطويل عبر مسار المرفقات الموثوق، ومنع إعادة تحويل سياق النص المرفق. | `airi_attachment_flow_health.py` و`:app:compileDebugKotlin` نجحا. | `BUILD_VERIFIED` |
| `91b7f067` | حدود مدخلات scheduler قبل persistence وWorkManager. | `ScheduledJobInputPolicyTest` نجح محلياً. | `TESTED` |
| `b7fe2f2e` | رفض اختيار remote model فارغ أو غير مسجل مع إبقاء اختيار صالح. | `RemoteModelSelectionPolicyTest` نجح محلياً. | `TESTED` |

## الأدلة المحلية

| الفحص | النتيجة | ملاحظة |
|---|---|---|
| `SkillInvocationAccessPolicyTest` | ناجح | يثبت رفض المهارة المعطلة أو غير المصرح بها أو التي تتطلب memory غير متاح. |
| `AttachmentPolicyTest` | ناجح | يثبت تطبيع marker وإدراج حجم المرفق مرة واحدة. |
| `ScheduledJobInputPolicyTest` | ناجح | يثبت رفض agent/label/payload غير المقيدة وحجم UTF-8 الزائد. |
| `RemoteModelSelectionPolicyTest` | ناجح | يثبت رفض ID البعيد الفارغ أو غير المعروف. |
| `:app:compileDebugKotlin` | ناجح | شغّل بعد إصلاحات scheduler ومسار النص الطويل. |
| `airi_release_health.py` | ناجح | يبقي توقيع release محصوراً في `main` عند وجود الأسرار الأربعة. |
| حراس Remote Control وFirestore | ناجحة | تثبت الحدود المصدرية للتفويض والملكية والـreplay والـrevocation. |
| `airi_scheduler_health.py` | ناجح | يثبت عدم إنشاء نسخ دورية جديدة من مهام الصيانة عند الاستئناف. |
| `airi_attachment_flow_health.py` | ناجح | يثبت أن تحويل النص الطويل لا يعتمد على ترتيب UI غير متزامن. |
| `airi_localization_health.py` | ناجح مع مراجعات | يسجل 252 نصاً مرشحاً للمراجعة في الصينية؛ ليست دليلاً على اكتمال مراجعة ترجمة بشرية. |

## أدلة CI على revision النهائي

اجتازت بوابات CI التالية على `b7fe2f2e`:

| البوابة | النتيجة | الرابط |
|---|---|---|
| Architecture Audit | نجاح | [تشغيل 32560931076](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32560931076) |
| Deep Audit | نجاح | [تشغيل 32560930968](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32560930968) |
| Android CI | نجاح، بما فيه المسار instrumentation | [تشغيل 32560931105](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32560931105) |

لم تعد بوابة Windows تعمل تلقائياً لهذه الدفعة لأن التغييرات في Android والمستندات والحراس. آخر دليل Windows MSI للمرشح الهندسي محفوظ في وثيقة Release Candidate السابقة؛ لا ينقل هذا الدليل إلى تشغيل Windows runtime ولا إلى هذا revision بصورة غير مستحقة.

## مصفوفة القبول الحالية

| المجال | الحالة | حد الدليل |
|---|---|---|
| مهارات AgentLoop | `IMPLEMENTED` و`TESTED` | الحارس الجديد يطبق enabled/runtime permission/memory context في مسار `SkillToolBridge`. يتطلب حوار أذونات OS تحققاً على جهاز فعلي. |
| Scheduler | `IMPLEMENTED` و`TESTED` | يوجد UI وWorkManager وحفظ ونتيجة وإلغاء؛ ثبت منع duplication وحدود إدخال. يلزم اختبار وقت فعلي وإعادة تشغيل جهاز للتحقق التشغيلي. |
| المرفقات النصية | `IMPLEMENTED` و`BUILD_VERIFIED` | يتم حفظ النص الطويل وإرساله كملحق داخلي؛ يلزم تحقق جهاز لتجربة الاختيار والعرض والحذف. |
| اختيار remote model | `IMPLEMENTED` و`TESTED` | تمنع registry model IDs غير الصالحة؛ لا تثبت اتصال provider أو صحة credentials. |
| Android | `TESTED` | CI مرّ، لكن الجودة والأداء والصوت والرؤية والـoffline على أجهزة Android حقيقية تبقى `EXTERNAL_VERIFICATION_REQUIRED`. |
| Desktop Windows/Linux | `BUILD_VERIFIED` تاريخياً | تشغيل الحزم وتوقيعها وترقيتها وتحققها على أجهزة مستهدفة يبقى `EXTERNAL_VERIFICATION_REQUIRED`. |
| Remote Control | `TESTED` في policies وFirestore rules | Firebase/OAuth production، حسابات مستخدمين حقيقية، وأجهزة مقترنة تبقى `EXTERNAL_VERIFICATION_REQUIRED`. |
| توقيع وإصدار Android/Windows | `EXTERNAL_VERIFICATION_REQUIRED` | مفاتيح التوقيع وPlay App Signing وWindows signing/Store خارج المستودع. |
| التجارة والترخيص | `EXTERNAL_VERIFICATION_REQUIRED` | مصفوفة التراخيص تتطلب مراجعة قانونية قبل التوزيع التجاري. |

## الخطوة التالية للنشر

يبقى مسار الإصدار الخارجي مرتباً كما في [برنامج Productization](PRODUCTIZATION_AND_PUBLISHING_PROGRAM.md): إعداد مفاتيح Android وPlay App Signing، اختبار internal track، توقيع Windows واختبار تثبيت MSI، smoke tests لـFirebase/OAuth، مراجعة الخصوصية والترخيص، ثم rollout محدود قابل للتراجع. لا يرفع أي من هذه الخطوات إلى حالة مكتملة قبل توافر الحسابات والأسرار والأجهزة والنتائج المقابلة.
