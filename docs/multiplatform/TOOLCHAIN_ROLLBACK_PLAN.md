# خطة Rollback لترقية Toolchain

## نقاط الرجوع

| النقطة | المرجع | الغرض |
| --- | --- | --- |
| protected Android baseline | `architecture-refactor` عند `1027dee2` | مرجع Android محمي؛ لا يستقبل الترقية أو rollback. |
| migration checkpoint | `cp-toolchain-baseline` عند `eb4714d2` | نقطة رجوع منشورة قبل أي تعديل toolchain. |
| working branch | `cp-foundation` | موضع commits المتدرجة فقط. |

## قبل كل خطوة

1. تأكد من `git status --porcelain` فارغ.
2. سجل SHA الحالي، قيم كتالوج الإصدارات، نتيجة الحارس، وأوامر الفشل/النجاح في `reports/multiplatform/toolchain-baseline/` أو دليل gate المقابل.
3. أنشئ commit أو branch checkpoint فقط بعد نجاح البوابة السابقة.
4. لا تبدأ تغييراً ثانياً قبل تشخيص نتيجة التغيير الأول.

## عند فشل خطوة

| الحالة | الإجراء |
| --- | --- |
| compiler أو dependency resolution | احتفظ بسجل الفشل، راجع التوافق الرسمي، وأصلح النسخة أو configuration المرتبطين فقط. |
| Android product regression | ارجع إلى آخر commit gate ناجح على `cp-foundation`، ولا تعدّل `architecture-refactor`. |
| R8 daemon/resource failure | احتفظ بالسجل، أوقف عمليات Gradle، واستخدم CI/بيئة ذات مورد كافٍ؛ لا تعطّل minification. |
| native/JNI failure | أعد NDK/CMake/AGP إلى آخر gate ناجح، ثم شغّل native verification قبل أي ترقية أخرى. |
| CI-only failure | اجلب logs، أصلح سبباً محدداً أو أعد branch إلى آخر checkpoint؛ لا تعلن milestone ناجحاً. |

## أوامر الاستعادة

لا تنفذ هذه الأوامر ما دام يمكن إصلاح السبب موضعياً. عند قرار rollback مثبت:

```bash
git switch cp-foundation
git reset --hard <last-passing-gate-sha>
git push --force-with-lease origin cp-foundation
```

قبل أي reset، ينسخ سجل الفشل و`git diff` إلى دليل evidence ويكتب سبب الرجوع في وثيقة gate. لا يستخدم `--force` بلا lease، ولا يغير فرع `architecture-refactor`.

## معيار الاستئناف

يستأنف المسار من آخر checkpoint نجح محلياً وبعيداً. لا يعاد تطبيق نسخة أو plugin إلا عندما يمتلك سبباً رسمياً أو proof-of-concept معزولاً يعالج root cause السابق.
