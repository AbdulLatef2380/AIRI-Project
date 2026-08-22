# عقد الاستمرارية والصوت

**الحالة:** `IMPLEMENTATION_COMPLETE` لبروتوكول metadata المنقح، موافقة المستخدم، آلة حالات الصوت، واختبارات JVM. **`RUNTIME_VERIFICATION_PENDING`** لسياسات Firestore الإنتاجية، جهاز Android ثانٍ، وضع الخلفية، والميكروفون/Audio Focus ومزودي realtime.

## الغرض

يمد هذا العقد مسار `CloudSyncCoordinator` القائم ولا ينشئ مزامنة موازية. يضيف استمرارية محدودة وآمنة لتقدم المهمة، ويرسخ آلة حالات جلسة الصوت الحية. لا يعني وجود sync أن الجهاز الثاني يستطيع تنفيذ المهمة أو الوصول إلى محتواها.

| المسار | مصدر الحقيقة | ما نُفذ |
|---|---|---|
| تقدم المهمة المحلي | `DurableTaskManager` و`durable_tasks.json` | تصدير `TaskContinuitySnapshot` محدود إلى 250 مهمة |
| النقل الاختياري | `CloudSyncCoordinator` و`CloudSyncWorker` | pull ثم push إلى `users/{uid}/task_continuity/{taskId}` عند تفعيل موافقتين |
| الموافقة | `UserPreferences.taskContinuitySyncEnabled` | default=false، toggle ظاهر في Privacy & Data ومعطل إن لم تكن cloud sync مفعلة |
| الصوت | `LiveVoiceSession` في `LiveVoiceService` | حارس انتقالات يحمي تدفق listen → thinking → response وbarge-in/recovery |

## ما يُزامن وما لا يُزامن

> **القاعدة:** snapshot الاستمرارية هو إشارة تقدم، وليس أمراً للتنفيذ عن بُعد ولا نسخة من سياق الوكيل.

| داخل `TaskContinuitySnapshot` | مستبعد عمداً |
|---|---|
| `taskId`، `projectId`، lifecycle status، `updatedAtMs`، current run/step، progress، وحالة كل plan step | `input`، `description`، `title`، `result`، `checkpointData`، approval detail، timeline text، diagnostics، artifact paths، tool output، secrets، وagent context |

يوفر المستخدم موافقة مستقلة عبر **Sync task progress across devices** بعد تفعيل cloud sync. يظل الخيار معطلاً في الواجهة إن لم تُفعّل المزامنة السحابية، ولا يرسل العامل الدوري أي snapshot ما لم تكن الموافقتان مفعّلتين. لا يغيّر هذا الإعداد سياسة مزامنة الذاكرة أو الملفات أو المحادثات.

## دمج الحالة ومنع التنفيذ المزدوج

عند الاستقبال، يقبل `DurableTaskManager.mergeContinuitySnapshot` الإصدار المدعوم فقط، ويطبق snapshot أحدث حصراً على مهمة موجودة محلياً. لا تنشئ snapshot البعيدة مهمة جديدة، لأن مواصفات المهمة وinput تبقيان محليتين. وإذا كان التنفيذ المحلي `RUNNING`، يرفض الدمج كيلا يزيح سلطة الجهاز المنفذ.

عندما تحمل snapshot البعيدة الحالة `RUNNING`، يمثلها المستقبل بـ`PAUSED`. هذا يمنع بدء WorkManager أو agent على جهاز ثانٍ تلقائياً. يسجل الدمج الحدث `CONTINUITY_MERGED` في timeline المنقح. الاستئناف عبر جهاز ثانٍ يتطلب لاحقاً تجربة صريحة موثقة مع trust وpairing وapproval؛ لا يدعي هذا العقد اكتمالها.

## آلة حالات الصوت

`LiveVoiceSession` تقبل فقط الانتقالات التالية، بالإضافة إلى `→ IDLE` و`→ RECOVERING` من أي حالة لمعالجة الإيقاف والخطأ:

| الحالة السابقة | الحالة المسموحة التالية |
|---|---|
| `IDLE` | `LISTENING` |
| `LISTENING` | `THINKING` |
| `THINKING` | `STREAMING_RESPONSE` |
| `STREAMING_RESPONSE` | `INTERRUPTED` |
| `INTERRUPTED` | `LISTENING` |
| `RECOVERING` | `LISTENING` |

أي انتقال آخر يسجل `AIRI VOICE_INVALID_TRANSITION` ولا يغير الحالة. مثال ذلك محاولة بدء streaming response من `IDLE`. بقي سجل Android هو الإعداد الافتراضي في التطبيق، لكن حقن logger اختباري يسمح بفحص آلة الحالات ضمن JVM بدون Android Log stub.

## أدلة الاختبار

| الدليل | التغطية |
|---|---|
| `TaskContinuitySnapshotTest` | الحقول المسموح بها وعدم تسرب input/checkpoint/result/title/step title |
| `DurableTaskProductKernelTest` | توافق دورة run/plan/approval الحالية مع عقد DurableTask |
| `LiveVoiceSessionTest` | تدفق turn الشرعي، رفض streaming غير الشرعي، وbarge-in ثم re-arm |
| `:app:compileDebugKotlin` | تكامل Firestore وWorkManager وCompose والموارد |
| `:app:lintDebug` | فحص الموارد ينجح محلياً بعد إغلاق مفاتيح es/zh وتوافق placeholders |

## فجوات الإغلاق الصريحة

لم يُتحقق بعد من Firestore Security Rules أو مشهد مزامنة بين جهازين، ولا يوجد سجل presence/capabilities أو pairing trust أو mTLS/credential rotation لشبكة الأجهزة. لا تتم مزامنة artifacts أو logs أو المحتوى، ولا يوجد resume عن بعد. مزودات realtime للصوت ما زالت غير موصولة end-to-end داخل `LiveVoiceService`، ولا يوجد تحقق على جهاز حقيقي من الميكروفون وAudio Focus وoffline Arabic STT أو استمرار الخدمة في الخلفية.
