# طبقة الأمان والخصوصية

تحتوي هذه الحزمة ضوابط الأمان والخصوصية في AIRI Android على `cp-foundation`. تحدد الشفرة boundaries وfail-closed behavior؛ لا تثبت بمفردها قبول متجر أو مزود حي أو Android Keystore على جهاز معين.

## العقود الحالية

| المجال | الحد المطبق |
|---|---|
| الأسرار | تحفظ credentials عبر مسار encrypted storage. عند وجود مشروع وموصل، ترتبط capability بالسياق ولا تعرض raw value في UI أو log أو artifact أو prompt. |
| الموافقات | الأثر الجانبي يمر بسياسة/continuation مملوكة. approval عامة أو سياق محادثة لا يمنحان تفويضاً لأداة أو مشروع أو step آخر. |
| الصلاحيات | `PermissionGovernanceLayer` وواجهات permission الصريحة تفصل الإذن عن التنفيذ. لا يطلب onboarding الصلاحيات إلا من فعل مستخدم ظاهر. |
| الشبكة والملفات | release يستخدم TLS-only؛ استثناءات emulator محصورة في debug. المرفقات تنسخ إلى تخزين خاص وتبقى URI المصدر خارج metadata الدائمة. |
| المهارات الديناميكية | endpoint غير HTTPS أو placeholder لا يصل إلى registration صالح. |
| الحذف المحلي | `DataDeletionCoordinator.eraseLocalData()` يوقف عمل AIRI المحلي ويمسح stores/ملفات/معرفة/artifacts/أسرار/تفضيلات/cache ويسجل خروجاً محلياً. **لا** يستدعي حذف حساب Firebase أو مزود بعيد ولا يدّعي حذف البيانات السحابية. |

## ما لا تثبته الحزمة

لا يكفي وجود كود لإثبات أن provider key أو OAuth أو Play Integrity أو SQLCipher migration أو transport أو سياسة طرف ثالث آمنة في الإنتاج. يلزم real-device وcredentialed-provider وpublisher/legal evidence حسب مسار الإصدار المعلن.

## التحقق

الحراس static وCI يثبتان حدود cleartext وFileProvider والمرفقات وURI وسياق Firebase وبعض مسارات الإلغاء/الأسرار. تبقى Keystore على جهاز فعلي، permission denial/Settings return، Firebase DebugView، Data Safety، وسياسة الخصوصية ومتطلبات Play بوابات خارجية مستقلة.
