# تصنيف مهارات تنفيذ التحول

يعتمد برنامج AIRI متعدد المنصات تصنيفاً صريحاً قبل كل مهمة كبيرة أو متكررة. يمنع ذلك استعمال إجراء عام في ترقية toolchain أو استخراج نواة أو تحقق إصدار عالي المخاطر.

| التصنيف | المهارة المتخصصة | تستخدم عند | بوابة الخروج |
| --- | --- | --- | --- |
| `TOOLCHAIN` | `airi-toolchain-migration` | Kotlin، Gradle، AGP، Compose، KSP، أو تبعية حرجة. | طبقة واحدة، بناء محلي، CI، وrollback موثق. |
| `ARCHITECTURE` | `airi-kmp-architecture` | نقل models/policies/contracts أو تعريف حد منصة. | لا leakage في `commonMain` واختبارات مشتركة. |
| `ANDROID` | `airi-android-regression` | أي تغيير قد يمس Android أو build/native/CI. | unit/lint/debug/release/AndroidTest/static/security. |
| `DESKTOP` | `airi-desktop-foundation` | نافذة Desktop، UI، persistence، adapters، package أو runtime. | artifact وتشغيل وتفاعل مثبتان لكل OS. |
| `TESTING` و`RELEASE` | `airi-release-validation` | تغيير حالة منصة، promotion، merge gate أو ادعاء جاهزية. | evidence matrix وCI وstatus دقيق. |

التصنيفات الفرعية `MEMORY` و`AGENT` و`NATIVE` و`DATABASE` و`AUTH` و`VOICE` و`ATTACHMENTS` و`SECURITY` و`UI/UX` ترث مهارة KMP أو Desktop المناسبة حتى يثبت تكرار كافٍ لإنشاء مهارة مستقلة. لا تُنشأ مهارة شكلية؛ يُنشأ الدليل المتخصص عند وجود workflow متكرر أو حد منصة عالي المخاطر.

## تحقق المهارات

تحققت المهارات الخمس عبر `quick_validate.py` بعد توفير اعتماد `PyYAML` المطلوب في بيئة التحقق. تبقى المهارات محلية في مساحة أدوات الوكيل؛ توثق هذه الصفحة كيفية استخدامها داخل مستودع AIRI دون إدخال محتوى مهارات التشغيل ضمن كود المنتج.
