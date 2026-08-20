# خطة ترحيل AIRI متعددة المنصات

## قواعد التنفيذ

كل العمل يجري على `cp-*` ثم يدمج إلى `main` **فقط** عند نجاح بوابة milestone الخاصة به. يبقى `architecture-refactor` خط Android المحمي، ولا يستقبل عملاً متعدد المنصات ضمن هذا البرنامج. لا ينشئ أي milestone ادعاء منصة من اسم Gradle target أو screenshot؛ يلزم بناء قابل للتكرار واختبار قبول بحسب الحالة المستهدفة.

| قاعدة | التطبيق العملي |
| --- | --- |
| استخراج تدريجي | نقل أقل مجموعة مكتملة من نماذج وسياسات pure في كل مرة، مع استبدال Android call site بعد نجاح الاختبار. |
| Android غير قابل للتفاوض | يمر كل milestone بـ static checks وبناء Android واختباراته المتاحة قبل الدمج في `main`. |
| حدود منصية صريحة | ينشأ adapter عند وجود API حقيقي مختلف؛ لا تنشأ طبقة abstraction لمجرد احتمال بعيد. |
| دليل قبل ادعاء | `BUILDS` يحتاج artifact وأمر بناء؛ `RUNTIME_VERIFIED` يحتاج سيناريو تشغيل ونتيجة. |
| لا أسرار في المصدر | تُمرر الأسرار في آليات المنصة الآمنة أو في server boundary، لا في common code أو Web client. |

## التسلسل

| Gate | الفرع المقترح | ناتج محدود | قبول إلزامي | حالة المنصات بعده |
| --- | --- | --- | --- | --- |
| Gate 1: التحليل | `cp-foundation` | فاحص التبعيات، خريطة الحدود، البنية، المخاطر والمصفوفة. | تدقيق النتائج، فحوص Android الحالية، توثيق مصادر KMP. | Android كما هو؛ Desktop/Web `PLANNED`. |
| Gate 2A: domain core | `cp-core` | `core-domain` KMP: attachment/memory/routing/cancellation policies وmodels. | `commonTest`، target Android، Android يستهلك module، لا imports Android في common. | AIRI Core `IMPLEMENTED` ثم `BUILDS` بعد تحقق الأوامر. |
| Gate 2B: contracts | `cp-core` | عقود repository/provider/runtime/tool وسجل capability. | لا Context/Room/JNI في contracts؛ Android adapters تعمل دون تكرار business policy. | Core أوسع مع بقاء runtime Android فقط. |
| Gate 2C: memory/routing | `cp-core` | نقل تدريجي لـrepository boundaries والتوجيه المشترك. | policy tests، migration Android، Android regression. | Core memory/routing `BUILDS` عند تحققها. |
| Gate 3A: desktop shell | `cp-desktop` | تطبيق Compose Desktop minimal يحوي composition root وواجهة chat قابلة للاختبار. | حزمة/تشغيل Windows أو Linux فعلياً؛ لا مزايا افتراضية. | Desktop shell `BUILDS` ثم `RUNTIME_VERIFIED` لكل OS موثق. |
| Gate 3B: desktop capabilities | `cp-desktop` | storage، OAuth، providers، attachments، native runtime حيث أمكن. | جدول قبول قدرة-بقدرة وevidence لكل OS. | الحالة لكل قدرة مستقلة، لا حكم شامل مسبق. |
| Gate 4: web | `cp-web` | target Web مستقل وواجهة responsive وعقود browser-safe. | browser build، security review، Web acceptance matrix. | Web يبقى `PLANNED` حتى تحقق كل مسار. |
| Gate 5: hardening | `cp-hardening` | scanning مستمر، documentation evidence، performance and security tests. | مراجعة أمنية، SBOM، regression proof، التزامات licensing. | المرشحون فقط للترقية المدروسة. |

## Gate 1: معايير الإغلاق الحالية

| مخرج | الدليل |
| --- | --- |
| تصنيف source واقعي | `scripts/airi_platform_dependency_scan.py` و`PLATFORM_DEPENDENCY_SCAN.md`. |
| حدود Android/native | `PLATFORM_DEPENDENCY_GRAPH.md`. |
| قرار KMP ومصدره | `CROSS_PLATFORM_ARCHITECTURE.md` مع المراجع الرسمية. |
| مسار Desktop ثم Web | `PLATFORM_MATRIX.md` و`RUNTIME_STRATEGY.md`. |
| التخزين والمصادقة والأمن | مستندات استراتيجية منفصلة. |
| مخاطر قابلة للإدارة | `RISK_REGISTER.md` مع owner وexit criteria. |
| Android لا ينحدر | أوامر التحقق الحالية ناجحة ومسجلة قبل commit. |

## Gate 2: أول استخراج آمن

### النطاق

تبدأ AIRI بـ `core-domain` يحتوي فقط على عناصر تملك منطقاً مستقلاً واختبارات مناسبة، مرتبة بالآتي:

| ترتيب | مجموعة النقل | لماذا هي أولاً | غير مشمول |
| --- | --- | --- | --- |
| 1 | `AttachmentPolicy` وmodels المحايدة | سياسة حقيقية، لا acquisition منصي، واختبار قائم. | `Uri`، picker، file resolver. |
| 2 | `MemoryAdmissionPolicy` و`MemoryTextNormalizer` | تعالج مشكلة الحفظ غير المنضبط وتملك خوارزمية قابلة للاختبار. | Room DAO، embedding runtime، filesystem. |
| 3 | `ExecutionGenerationGate` وexecution states | cancellation/state منطق أعمال لا lifecycle. | coroutine scope مملوك من Android. |
| 4 | `RoutingPolicy` وpreference/error models | model selection/fallback ملك للنواة. | HTTP/SSE client وprovider secrets. |

### قبول Gate 2A

```text
1. core-domain: compileKotlin<target> أو المهمة المكافئة تنجح.
2. core-domain: commonTest ينجح.
3. فاحص المنصة يثبت صفر android/androidx/Room/JNI/java.* في core/**/commonMain.
4. Android app يبني ويستخدم نفس types أو policies المنقولة، لا نسخة متفرعة.
5. verify_core_changes.py وsecurity_scan.py وairi_core_health.py تنجح.
6. مراجعة diff تثبت أن architecture-refactor لم يُعدل.
```

لا يُنقل أي Repository أو UI أو ModelRuntime في هذا gate. إذا كشف نقل عنصر صغير تبعية transitive غير قابلة للحل بلا عقد، يؤجل العنصر ولا يوسع النطاق تلقائياً.

## Gate 3: سطح المكتب

ينفذ Desktop بعد أن يصبح core قابلاً للبناء والتكامل مع Android. يختبر Windows وLinux منفصلين لأن نجاح JVM محلي لا يثبت packaging أو native runtime أو OAuth أو filesystem لكليهما.

| قدرة سطح المكتب | الحد الأدنى للاختبار | حالة ما قبل الاختبار |
| --- | --- | --- |
| بدء التطبيق والدردشة | تشغيل application، فتح محادثة، إرسال واستقبال رد من provider اختبار. | `PLANNED` |
| streaming/cancellation | ظهور chunk ثم إلغاء ولا يصل callback قديم. | `PLANNED` |
| memory/RAG | حفظ مقبول، استرجاع، وإعادة تشغيل persistence. | `PLANNED` |
| attachments | file picker وdrag/drop والتحقق من policy. | `PLANNED` |
| skills/tools | discovery يحترم capability/platform/permissions. | `PLANNED` |
| local models | نموذج صغير محلي من runtime native لكل OS. | `PLANNED` |
| OAuth | PKCE state وcallback وlogout في target فعلي. | `PLANNED` |

## Gate 4: Web

Web يبدأ فقط بعد ثبات العقود على Desktop. يتم بناء UI browser-first ويستخدم providers server-mediated إذا تطلبت الأسرار ذلك. لا يُفترض browser local inference ولا background scheduling و لا filesystem desktop؛ تختبر هذه كقدرات مستقلة أو تعلن `BLOCKED` مع سبب تقني.

## البروتوكول لكل commit

```bash
git fetch --all --prune
git status --short
git diff --check
python3 scripts/airi_platform_dependency_scan.py
python3 tools/verify_core_changes.py
python3 tools/security_scan.py
python3 scripts/airi_core_health.py
python3 scripts/supply_chain_inventory.py
```

تضاف أوامر بناء core وDesktop/Web الفعلية عندما توجد modules قابلة للبناء. لا يُدفع commit يغيّر سلوك Android مع اختبارات محلية فاشلة.

## بوابة الترقية إلى الخط المحمي

لا يناقش دمج أي subset في `architecture-refactor` إلا عند تحقق الشروط جميعاً: بنية سليمة، إثبات Android non-regression، core verified، target platform verified في الحدود المدعاة، مراجعة أمنية، أثر تجاري واضح، وخطة rollback. إلى ذلك الحين تبقى جميع أعمال التحول على `main` وفروعه الثانوية.
