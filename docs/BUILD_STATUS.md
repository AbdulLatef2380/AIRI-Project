# أحدث تحقق — PR-1 (2026-10-08)

- **الفرع:** `pr-0-pr-1` مبني من `origin/main`، HEAD الأساسي: `168128bd` (`docs(audit): establish runtime capability baseline`).
- **PR-0:** بوابة الجرد وADR موجودة على خط الأساس؛ الجرد يميز `defined/registered/advertised/authorized/invoked/device-proven`، ولا توجد تغييرات تنفيذية أو إزالة legacy surface في هذه المرحلة.
- **Toolchain:** Gradle `8.11.1`، AGP `8.10.1`، Kotlin `2.2.21`، KSP `2.2.21-2.0.5`، JDK `17.0.20`، Android SDK platform `36`، Build Tools `36.0.0`.
- **Dependency verification:** بقي مفعلاً؛ `gradle/verification-metadata.xml` صالح XML ويحتوي artifacts KSP المطابقة للإصدار المطلوب. لم تُستخدم `--dependency-verification=off` ولم تُعدّل checksums تخميناً.

| البوابة | النتيجة | الدليل |
|---|---|---|
| `:app:compileDebugKotlin` | **PASS** | وصل إلى ترجمة Kotlin؛ 31 مهمة |
| `:app:testDebugUnitTest` | **PASS** | 43 مهمة، 0 فشل |
| `:app:lintDebug` | **PASS** | 69 مهمة، 0 فشل؛ تقرير `app/build/reports/lint-results-debug.html` |

**إصلاحات PR-1 المرفقة:** أزيل اعتماد `QueryClassifier` على `android.util.Log` ليبقى قابلاً لاختبار JVM، وصُحح اختبار سياسة `terminal_execute` ليتطابق مع عقد `ALLOW_TYPED_TERMINAL` عند وجود durable context.

**حدود الدليل:** لم تُشغّل اختبارات instrumentation أو emulator/device أو Firebase Emulator في هذه الجولة؛ لذلك لا تُرفع أي حالة إلى `device-proven`.

---

# حالة البناء والتحقق

**تاريخ المحاولة:** 13 أغسطس 2026
**الأمر المستهدف:** `:app:compileDebugKotlin`
**Gradle المستخدم:** 8.5 من توزيع محلي صالح.
**Android SDK:** `/home/ubuntu/android-sdk`.

## النتيجة

لم يبدأ تجميع Kotlin. توقف Gradle أثناء تهيئة المشروع لأن ملحق Android Gradle Plugin المطلوب غير موجود في الذاكرة المحلية ولا يمكن تنزيله في البيئة الحالية.

```text
Plugin [id: 'com.android.application', version: '8.2.2', apply: false] was not found
```

يظهر الخطأ في `build.gradle.kts` الجذري عند طلب الإضافة. فحص الذاكرة المحلية لم يجد artifact خاصاً بـ `com.android.tools.build` أو ملحق `8.2.2`.

## ما تم التحقق منه رغم القيد

| فحص | النتيجة |
|---|---|
| توزيع Gradle 8.5 المحلي | صالح ويعمل (`gradle --version`) |
| مسار Android SDK | ممرر إلى أمر Gradle |
| تحليل مسارات المصدر المعدلة | ناجح عبر `tools/verify_core_changes.py` (23/23) |
| تماثل مفاتيح الموارد الإنجليزية والعربية والإسبانية والصينية | ناجح ضمن الفحص الساكن |
| تجميع Kotlin / JNI / APK | **غير متحقق** بسبب الإضافة المفقودة |
| اختبارات الوحدة والجهاز | **غير متحققة** بسبب الإضافة المفقودة |

## خطوات إعادة التحقق

في بيئة لها وصول إلى Google Maven وGradle Plugin Portal، شغّل:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

بعد نجاح البناء، يجب اختبار مسارات الإلغاء، الإدخال `/` و`@`، الصوت، الجدولة، حذف الحساب، والـ RTL على جهاز فعلي قبل اعتماد إصدار نشر.

## ملفات الأدلة

- سجل محاولة البناء: `/home/ubuntu/airi-compile-after-core.log`
- فاحص التعديلات الساكن: `tools/verify_core_changes.py`
- وثيقة حالة التنفيذ: `docs/IMPLEMENTATION_STATUS.md`
