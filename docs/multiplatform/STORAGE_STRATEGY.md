# استراتيجية التخزين والذاكرة

## القرار المرحلي

لا تدخل Room الحالية أو DAOs أو migrations إلى `commonMain`. تبدأ AIRI بعزل **عقد repository** ونقل سياسات الذاكرة الخالصة، مع إبقاء `AiriDatabase` وRoom adapter في Android. لا يختار البرنامج SQLDelight أو Room KMP أو persistence موحداً نهائياً قبل spike يقيس الترحيلات والتشفير والأداء والتوزيع على Windows/Linux/Web.

هذا قرار مقصود لتجنب تحويل migration database ناضجة على Android إلى إعادة كتابة تخزين كاملة بلا فائدة مثبتة. يمكن لـKMP مشاركة منطق الأعمال تدريجياً مع إبقاء تطبيقات المنصة في source sets مخصصة [1]. كما تشير وثائق Android إلى أن دعم المكتبات متعدد المنصات يختلف حسب المنصة؛ لا تكفي قابلية library للاستيراد لتأكيد دعم المنتج على Windows أو Web [2].

| نطاق | المالك | الحالة | القرار الحالي |
| --- | --- | --- | --- |
| memory admission/normalization/decay policy | `core-memory` | `ARCHITECTED` | ينقل أولاً مع اختبارات مشتركة. |
| entities/domain memory model | `core-memory` | `ARCHITECTED` | نماذج immutable ومحايدة، بلا annotations Room. |
| repository contract | `core/contracts` | `ARCHITECTED` | يصف العمليات والمعاملات والنتائج دون SQL أو Android. |
| Android persistence | `data/persistence-android` | `IMPLEMENTED` | Room v7 وترحيلات موجودة تستمر بلا كسر. |
| Desktop persistence | `data/persistence-desktop` | `PLANNED` | يختار بعد spike وتحقق migration/encryption. |
| Web persistence | `data/persistence-web` | `PLANNED` | IndexedDB/remote sync بحسب security model؛ لا تخزن أسراراً طويلة الأجل بلا حماية. |

## الحدود المقترحة

```text
core-memory
  ├── MemoryRecord / MemoryCandidate / RetrievalQuery
  ├── MemoryAdmissionPolicy / MemoryTextNormalizer / ranking policy
  └── MemoryRepository (contract)

platform adapter
  ├── AndroidRoomMemoryRepository
  ├── DesktopMemoryRepository
  └── WebMemoryRepository
```

العقد لا يعرف `RoomDatabase` أو `Dao` أو `Context` أو `Uri` أو `java.io.File`. تعالج implementation المنصية mapping، transactions، migrations، encryption driver، والملف الفعلي. يمنع هذا التصميم business rules المتفرعة بين قواعد البيانات.

## المراجعة بين البدائل

| خيار | نقاط القوة | المخاطر أو الفجوات | القرار الآن |
| --- | --- | --- | --- |
| A: SQLDelight أو database KMP موحد | SQL/schema ومهاجرات أقرب للمشاركة؛ قد يقلل duplication. | تحويل Room v7، تدقيق encryption وWeb driver، migration/data loss risk. | `CANDIDATE_FOR_SPIKE`، لا اعتماد بعد. |
| B: repository abstraction + adapters | أقل تغيير في Android، يسمح باختيار storage وفق target، يبدأ سريعاً. | يتطلب انضباطاً حتى لا تختلف semantics بين adapters. | **المعتمد للمرحلة الأولى.** |
| C: Room حيث يتاح + equivalent elsewhere | يحافظ على Android بسرعة. | ازدواج schema/migration وسلوك متباين ومحدودية platform. | غير مفضل دون facade قوي؛ ليس قراراً حالياً. |
| D: خدمة تخزين remote موحدة | يسهّل sync/enterprise وربما Web. | trust boundary، تكلفة وتشغيل، offline/privacy، قد تخالف local-first. | يؤجل إلى متطلبات منتج منفصلة. |

## ضمانات الذاكرة المشتركة

تنتقل السياسات التي تمنع تضخم الذاكرة قبل التخزين نفسه. تستخدم AIRI admission policy لقبول المعلومات المرشحة فقط وفق قيمة/ثقة/تكرار/حجم، وnormalization قبل الفهرسة، وbudget واضح للاسترجاع. تملك كل منصة نفس semantics لهذه القواعد؛ لكن source of truth، encryption، sync، وindices تبقى مسؤولية repository adapter.

| قاعدة | مكان التنفيذ | اختبار مطلوب |
| --- | --- | --- |
| رفض المرفقات أو الذكريات غير الصالحة/المكررة | `core-domain` أو `core-memory` | fixture deterministic في `commonTest`. |
| normalization | `core-memory` | Unicode/empty/size edge cases. |
| transactional write | adapter المنصة | restart/failure/rollback لكل persistence backend. |
| migrations | adapter المنصة | fixture لكل version مدعوم؛ Android v1→v7 يبقى محفوظاً. |
| encryption at rest | adapter المنصة + secure key contract | key unavailable/rotation/no plaintext log. |
| sync conflict | طبقة sync منفصلة | offline/edit/delete/merge acceptance tests. |

## Desktop وWeb

تحتاج Desktop قاعدة بيانات محلية وموقع ملفات وإدارة مفاتيح لكل OS؛ وتحتاج Web account boundary وقيود quota/lifecycle/CORS. لا يفترض أن IndexedDB يساوي SQLite أو أن browser storage يصلح للـsecrets. إذا استخدم Web تخزيناً محلياً، تكون البيانات قابلة للاستبدال والتطهير ويتاح للمستخدم التحكم الواضح؛ تبقى الرموز الحساسة وفق `SECURITY_MODEL.md`.

## Spike قبل اختيار backend موحد

لا يُفتح spike قبل Gate 2C. يقارن البديلان الفعليان على dataset متفق عليه ويفحص:

1. schema portability وmigration من Android Room بلا فقد؛
2. استعلامات RAG metadata وlatency under budget؛
3. التشفير وإدارة المفاتيح؛
4. offline durability/recovery؛
5. دعم Windows وLinux؛
6. خيار Web المتوافق وقيود quota؛
7. license/SBOM/commercial fit.

ينتج spike جدول نتائج وأوامر build واختبارات؛ إلى ذلك الحين لا تعرض الوثائق أي backend كدعم متعدد المنصات فعلي.

## المراجع

[1]: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html "The basics of Kotlin Multiplatform project structure"
[2]: https://developer.android.com/kotlin/multiplatform "Kotlin Multiplatform | Android Developers"
