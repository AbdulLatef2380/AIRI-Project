# أحدث تحقق — PR-9 (2026-10-09)

- **الفرع:** `pr-0-pr-3`; تم توصيل Firebase memory sync فعلياً عبر Room outbox وtombstones وcursor owner-scoped.
- **Room:** تمت الترقية إلى v11 مع migration `10→11` وجدول `memory_sync_mutations`.
- **النتيجة:** بوابات compile، الاختبارات المستهدفة، الفحص العام، localization، release health، وفحص Firestore المصدرية كلها ناجحة.

| البوابة | النتيجة |
|---|---|
| `:app:compileDebugKotlin` | **PASS** |
| `:app:testDebugUnitTest` (memory/sync/orchestrator/permission) | **PASS** |
| `tools/verify_core_changes.py` | **96/96 PASS** |
| `scripts/airi_localization_health.py --strict` | **PASS** |
| `scripts/airi_release_health.py` | **PASS** |
| `scripts/airi_firestore_rules_test.py` | **PASS** |

التفاصيل الكاملة في `docs/PR9_CLOSEOUT_REPORT.md`. اختبار Firebase Emulator الفعلي متروك لبوابة CI لأن binary المحاكي غير موجود في هذه البيئة.

---

# تحقق المرحلة الثانية — PR-2 وPR-3 (2026-10-08)

- **الفرع:** `pr-0-pr-3`، مشتق من `pr-0-pr-1` والالتزام `766dd5e7`؛ لم يتم تعديل `main`.
- **PR-2 — العقود canonical:** أضيفت عقود pure JVM للمدخل normalized، `ChatIntent`، `ToolSpec`/`ArgumentSpec`، `AuthorizationDecision`/`ApprovalRequest`، و`ExecutionOutcome` مع `RequestId` و`generationId` وحارس terminal exactly-once. التصنيف وصفي فقط ولا يمنح صلاحية تنفيذ.
- **PR-3 — مسار Chat الواحد:** أصبح `QueryClassifier` adapter متوافقاً للـ canonical classifier، وأصبح `ChatViewModel` يستهلك نتيجة canonical واحدة قبل بناء `ExecutionRequest`/AgentLoop؛ لا يوجد مسار تصنيف ثانٍ. الأفعال القصيرة العربية والإنجليزية لا تسقط إلى SIMPLE، والطلبات الإبداعية لا تُعامل كأفعال.

| البوابة | النتيجة | الدليل |
|---|---|---|
| `:app:testDebugUnitTest` | **PASS** | 609 اختباراً، 0 فشل |
| `:app:lintDebug` | **PASS** | 69 مهمة، 0 فشل؛ `app/build/reports/lint-results-debug.html` |

**اختبارات القبول المضافة:** greetings، short actions، Arabic normalization، creative-vs-action، clarification، typed tool arguments، approval decision، request/generation identity، وexactly-once terminal outcome.

**حدود المرحلة:** لم تُربط بعد أدوات runtime الفعلية مباشرة بعقد `ToolSpec`، ولم تُشغّل اختبارات instrumentation أو emulator/device؛ لذلك لا تُرفع أي حالة إلى `device-proven`.

---

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
