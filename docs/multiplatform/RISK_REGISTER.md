# سجل مخاطر التحول متعدد المنصات

يعرض هذا السجل المخاطر الحالية المعروفة، لا احتمالات افتراضية. تقيّم الأولوية من تداخل الاحتمال والأثر، وتبقى مفتوحة إلى أن يتحقق معيار الإغلاق بدليل قابل للتكرار.

| ID | الخطر | الاحتمال | الأثر | الأولوية | الوقاية/التخفيف | المالك | معيار الإغلاق | الحالة |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CP-01 | تباعد تاريخ `main` والخط المحمي: لا commits مشتركة و28 commit في baseline. | مرتفع | مرتفع | حرجة | checkpoint منشور ودمج اتجاه واحد إلى `cp-foundation`؛ لا تعديل للخط المحمي. | معماري المشروع | كل milestone يمر review لفرق الدمج وAndroid regression. | مفتوحة |
| CP-02 | نقل Android imports إلى `commonMain` بالخطأ. | مرتفع | مرتفع | حرجة | فاحص dependency وحظر AndroidX/Room/JNI/JVM APIs في core. | core owner | CI يرفض leak واختبارات common تبني على أكثر من target. | مفتوحة |
| CP-03 | إعادة كتابة شاملة تفسد Android بدلاً من extraction. | متوسط | مرتفع | عالية | نقل groups صغيرة وسياسات pure أولاً، integration Android في كل gate. | migration owner | Gate 2 يثبت reuse من دون حذف/إعادة كتابة غير مبررة. | مفتوحة |
| CP-04 | JNI Android يُعامل كـruntime Desktop/Web. | مرتفع | مرتفع | حرجة | `PlatformModelRuntime` وnative build منفصل لكل target. | runtime owner | inference/cancel فعليان لكل OS؛ Web feasibility مستقلة. | مفتوحة |
| CP-05 | الادعاء بدعم Windows/Linux/Web من Gradle target فقط. | مرتفع | مرتفع | حرجة | matrix بمعاني حالات صارمة وأدلة لكل خلية. | release owner | artifact + acceptance evidence قبل `RUNTIME_VERIFIED`. | مفتوحة |
| CP-06 | تحويل Room v7 إلى storage موحد يسبب data loss أو يكسر migrations. | متوسط | مرتفع | عالية | repository boundary أولاً وspike قبل backend decision. | data owner | migration fixture/restart/encryption/perf results موثقة. | مفتوحة |
| CP-07 | browser bundle أو storage يكشف provider/API/OAuth secret. | متوسط | حرج | حرجة | Web model يمنع secrets في client ويحتاج server boundary/PKCE. | security owner | bundle/network review وWeb auth acceptance tests. | مفتوحة |
| CP-08 | اختلاف semantics للذاكرة بين adapters يسبب قبول/استرجاع غير متسق. | متوسط | مرتفع | عالية | admission/normalization/ranking في core مع fixtures مشتركة. | memory owner | common policy suite + contract tests لكل repository. | مفتوحة |
| CP-09 | توفر API أو library لـJVM لا يعني دعم Windows/Linux production. | مرتفع | متوسط | عالية | اختبار package/runtime لا build فقط، ووسم كل OS منفصلاً. | desktop owner | artifact وتشغيل على OS الموافق. | مفتوحة |
| CP-10 | محاولة Web local inference تستهلك ذاكرة أو تكون غير متوافقة أو مضللة. | مرتفع | متوسط | عالية | feasibility gate مستقل وremote-only fallback صريح. | web/runtime owner | browser evidence لWASM/WebGPU أو `BLOCKED` موثق. | مفتوحة |
| CP-11 | abstractions كثيرة بلا حاجة تزيد التعقيد وduplicate logic. | متوسط | متوسط | متوسطة | contract فقط لحد منصي موجود وبمستهلكين أو عزل Android حالي. | architecture owner | review يربط كل interface بـحد/adapter فعلي. | مفتوحة |
| CP-12 | tool/skill يحصل على filesystem/process/network خارج قدرات المنصة. | متوسط | حرج | حرجة | manifest capability + permission grant + registry intersection. | security/skills owner | allowed/denied integration tests على target. | مفتوحة |
| CP-13 | cancellation/streaming behavior يختلف عبر providers/targets. | متوسط | مرتفع | عالية | shared generation gate وcontract stream/cancel، tests ضد callback قديم. | agent/runtime owner | active cancel/retry tests لكل adapter. | مفتوحة |
| CP-14 | نقل UI قبل النواة ينتج واجهة موحدة تخرق UX المنصة. | متوسط | متوسط | متوسطة | Desktop-first بعد core؛ share state/tokens، لا shell مفروض. | UI owner | keyboard/window/web responsive acceptance criteria. | مفتوحة |
| CP-15 | scheduler semantics تختلف: WorkManager مقابل OS/browser lifecycle. | مرتفع | متوسط | عالية | `PlatformScheduler` contract يعرّف exactly-once/not-guaranteed/retry/cancel. | scheduling owner | OS/browser behavior documented and tested. | مفتوحة |
| CP-16 | دخول تبعيات جديدة بلا SBOM/licensing/security مراجعة. | متوسط | مرتفع | عالية | inventory وتجديد dependency/license review قبل الاعتماد. | release/security owner | SBOM وlicense matrix محدثان. | مفتوحة |
| CP-17 | استنزاف موارد الجهاز بسبب local model أو RAG/indices. | متوسط | مرتفع | عالية | platform capability probe، budgets، backpressure، وتجارب devices. | runtime/memory owner | performance budgets على جهاز/OS مستهدف. | مفتوحة |
| CP-18 | عدم توافر جهاز Windows/Linux أو IdP/provider credentials يمنع runtime proof. | مرتفع | متوسط | عالية | التصنيف `EXTERNAL_VERIFICATION_REQUIRED` وتوفير خطة دليل خارجية. | release owner | log/artifact من بيئة target معتمدة. | مفتوحة |
| CP-19 | بقاء التغييرات المحلية/binaries من build في commit. | متوسط | متوسط | متوسطة | `.gitignore` و`git status` قبل كل commit. | contributor | tree نظيفة وreview للملفات المتعقبة. | مخففة |
| CP-20 | دمج مبكر إلى `architecture-refactor` يفقد قابلية rollback ويخلط الخط المرجعي. | منخفض | حرج | عالية | branch rule وmerge gate موثقان. | repository owner | لا PR للخط المحمي قبل تحقق جميع شروط الترقية. | مفتوحة |
| CP-21 | Kotlin Multiplatform 1.9.22 يحذر أن AGP 8.10.1 أعلى من آخر إصدار AGP مختبر له (8.2)، بينما Compose Desktop الحديث يحتاج toolchain K2 أحدث. | متوسط | مرتفع | عالية | يبقى التحذير ظاهراً؛ تُقيّم ترقية Kotlin/Compose/AGP/KSP في spike منفصل مع rollback، ولا يضاف Compose Desktop بالتخمين. | build owner | compatibility matrix رسمية وCI مستمر ناجح وخطة upgrade/rollback قبل `app-desktop`. | مفتوحة |
| CP-22 | حاجز cancellation/generation يعتمد ذرات JVM لا تدخل `commonMain` مباشرة. | متوسط | مرتفع | عالية | لا يستبدل بمتغيرات عادية؛ spike منفصل لـAtomicFU أو primitive متوافق مثبت. | core/runtime owner | tests concurrent وبناء JVM/Android وAndroid integration مع dependency متوافقة. | مفتوحة |

## مخاطر خارجية مشروطة

| ID | التبعية الخارجية | الأثر | الحالة الصحيحة | الإجراء |
| --- | --- | --- | --- | --- |
| EXT-01 | حسابات ومفاتيح cloud providers | يمنع تحقق responses/fallback الحقيقي. | `EXTERNAL_VERIFICATION_REQUIRED` | test tenant مفصول، لا مفاتيح production. |
| EXT-02 | أجهزة Android فعلية متنوعة | يمنع الحكم على thermal/mic/native runtime. | `EXTERNAL_VERIFICATION_REQUIRED` | device matrix وevidence من hardware. |
| EXT-03 | Windows/Linux target machines | يمنع packaging/native/runtime proof. | `EXTERNAL_VERIFICATION_REQUIRED` | CI runner أو hardware لكل OS/architecture. |
| EXT-04 | OAuth IdP redirect registration | يمنع runtime auth proof. | `EXTERNAL_VERIFICATION_REQUIRED` | dev client وredirect URIs منفصلة. |
| EXT-05 | browser support for requested Web features | قد يحد local inference/background features. | `BLOCKED` أو `EXTERNAL_VERIFICATION_REQUIRED` | browser matrix وfeasibility prototype. |

## بروتوكول المراجعة

تراجع المخاطر عند إغلاق كل Gate وعند إضافة dependency أو target أو permission أو storage backend. إغلاق خطر يتطلب reference لcommit وcommand/result أو test case أو artifact؛ لا يغلق بتصريح أو نجاح compilation منفرد. إذا ظهر خطر يتطلب تغييراً مدمراً غير معتمد، يتوقف milestone المعني فقط ويُحدّث `MIGRATION_PLAN.md` قبل المتابعة.
