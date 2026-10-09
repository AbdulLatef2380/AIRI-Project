# PR-9 — تقرير الإغلاق الجذري

**التاريخ:** 2026-10-09  
**الفرع:** `pr-0-pr-3`  
**النطاق:** ذاكرة AIRI / RAG / Firebase memory sync

## ما تم إصلاحه

1. **Firebase memory sync أصبح مساراً فعلياً opt-in**
   - `CloudSyncWorker` ينفذ `pullMemories` ثم `pushMemories` بعد تحقق `cloudSyncEnabled` و`enableLongTermMemory`.
   - لا تتم مزامنة سجل المحادثة العادي (`isMemory = false`).
2. **Outbox محلي durable**
   - إضافة Room `memory_sync_mutations` عبر migration `10 → 11`.
   - الذاكرة الجديدة/المعدلة تُسجل بعد commit، والحذف يسجل `DELETE_TOMBSTONE`.
   - لا يحذف worker عناصر outbox إلا بعد نجاح Firestore batch؛ الفشل/offline يعيد المحاولة بأمان.
3. **Idempotency وrestart safety**
   - الكتابة تستخدم document ID ثابتاً مبنياً على `memoryId`.
   - pull يحافظ على `memoryId` وscope/privacy/provenance/importance/expiry و`updatedAtMs` بدلاً من إنشاء صف جديد.
   - tombstone الوارد يحذف محلياً دون إنشاء echo mutation.
   - cursor `cursorUpdatedAtMs` محفوظ في `users/{uid}/sync_meta/memory`، والدفعات bounded إلى 100 mutation.
4. **Deletion coverage**
   - `forgetMemory` و`editMemory` و`clearSessionMemories` و`clearAll` و`deleteMessage` تسجل mutations المناسبة.
5. **Firestore owner-only rules**
   - إضافة قواعد صريحة لـ`profile/preferences` و`memory` و`sync_meta` و`task_continuity`.
   - memory documents محدودة المفاتيح والحجم، والقراءة/الكتابة مربوطة بـUID الموجود في المسار.
   - الحذف المباشر للوثيقة ممنوع؛ الحذف يتم كتومبستون قابل للتدقيق.
6. **إغلاق فشلين عامين كانا يحجبان بوابة CI**
   - إزالة `SCHEDULE_EXACT_ALARM` غير المملوك من manifest وواجهة الأذونات.
   - إزالة `FOREGROUND_SERVICE_MICROPHONE` غير القابل للمنح من manifest.
   - جعل كل orchestration plan child للجذر وتجديد الجذر بعد `cancelAll` مع بقاء sibling plans مستقلة.
   - تحديث عقود الفحص لتوثيق Room v11 و`DEVICE_UNAVAILABLE` الحالي.

## دليل التحقق المحلي

| البوابة | النتيجة |
|---|---|
| `:app:compileDebugKotlin` | **PASS** |
| اختبارات `memory.*` و`sync.*` | **PASS** |
| اختبارات `agent.orchestrator.*` و`domain.permission.*` | **PASS** |
| `tools/verify_core_changes.py` | **96/96 PASS** |
| `scripts/airi_localization_health.py --strict` | **PASS** |
| `scripts/airi_release_health.py` | **PASS** |
| `scripts/airi_firestore_rules_test.py` | **PASS** — فحص المصدر والـowner scoping |

## حد مهم في الدليل

تم التحقق محلياً من قواعد Firestore عبر فاحص المصدر الموجود في المستودع، لكن لم يتم تشغيل Firebase Emulator في بيئة التنفيذ الحالية لعدم توفر binary المحاكي. لذلك سيبقى **CI/Emulator هو بوابة الإثبات النهائي للقواعد المنشورة**، وليس سبباً لتعطيل مسار التطبيق: المسار الآن متصل فعلياً ومغلق بالـconsent وoutbox وowner rules.
