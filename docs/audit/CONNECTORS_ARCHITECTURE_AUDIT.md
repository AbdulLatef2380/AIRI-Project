# تدقيق معماري وأمني لطبقة Connectors في AIRI

## نطاق التدقيق

شمل التدقيق الفرعين `main` و`cp-foundation` عند النقطتين `1e9ee61b` و`958bb7d7`. لا توجد فروقات وظيفية جوهرية في نطاق Connectors بين الفرعين؛ الاختلاف بينهما تاريخي/مترجم إلى commits مقابلة، ولذلك تنطبق النتائج التالية على الفرعين.

تمت مراجعة عقد `Connector`، والسجل، والإقلاع، وإدارة التنفيذ، والصحة، والأسرار، وموصلات LLM وGitHub وGoogle وTelegram وIFTTT وZapier وn8n وMCP وNotion والموصلات المحلية، إضافة إلى مسار `ToolRegistry` القديم والاختبارات والتوثيق.

## الخلاصة التنفيذية

طبقة Connectors تمتلك أساسًا معماريًا جيدًا: عقد موحد، `StateFlow` للحالة، تشفير للأسرار، عزل لفشل الموصلات، مهلات تنفيذ، وبعض مسارات الموافقة الدائمة، خصوصًا في GitHub. لكنها **ليست مكتملة إنتاجيًا بعد**؛ السبب ليس نقص عدد الموصلات، بل عدم اتساق العقود عبر الموصلات ومسارات استدعاء موازية تتجاوز الطبقة الموحدة.

أهم نتيجة هي وجود ثلاث طبقات يجب إصلاحها قبل التوسع:

1. **مسار تنفيذ موحد وصلاحيات مركزية**؛ لأن `ToolRegistry` لا يزال ينشئ `GithubService` و`TelegramService` مستقلين، بينما بعض أدوات Google مجرد stubs تعيد الفشل دائمًا.
2. **دلالات موحدة للصحة والفشل وإعادة المحاولة**؛ لأن موصلات متعددة تعلن نجاحًا شكليًا، أو تعتبر وجود السر صحة، أو تعيد محاولة آثار خارجية غير قابلة للإثبات.
3. **حدود موحدة للأسرار والآثار الخارجية**؛ لأن بعض الموصلات لا تمسح السر عند `disconnect`، وTelegram يضع token داخل URL، وMCP/n8n/webhooks لا تملك بوابة صلاحية/موافقة موحدة.

## ما تم بناؤه جيدًا

| المجال | الإيجابيات المثبتة |
|---|---|
| العقد | `Connector` يحدد دورة حياة واضحة، و`ConnectorInput` يحمل سياق التنفيذ، و`ConnectorOutput` يميز Success/Failure/ApprovalRequired/Streaming. |
| السجل | `ConnectorRegistry` يستخدم `ConcurrentHashMap` ويدعم metadata مرتبة وقراءة دون قفل. |
| الأسرار | `ConnectorAuthManager` يستخدم `EncryptedSharedPreferences` وMasterKey بتشفير AES-256-GCM، ولا يطبع الأسرار صراحة في السجل المعتاد. |
| GitHub | توجد حماية project/task/run/step، وcapability أحادية الاستعمال، وdurable approval وcontinuation وidempotency context لمسارات mutation. |
| OAuth | Zapier يستخدم state أحادي الاستعمال وPKCE/S256، وGoogle يمسح token عند 401/403 في مساره المخصص. |
| التشغيل | `ConnectorRuntimeManager` يفرض timeout، يعزل فشل الموصل، ويحافظ على `CancellationException` في المسار الرئيسي. |
| المحلي | `AndroidIntentConnector` يملك سياسة روابط ترفض العناوين غير المناسبة وتدعم takeover للمستخدم، وContacts يستخدم selection arguments بدل دمج المدخلات مباشرة. |
| التوثيق | README يضع مبادئ صحيحة: لا يكفي وجود UI أو build لإثبات provider حي، ولا يجوز وضع الأسرار في Git أو logs. |

## النتائج الرئيسية مرتبة حسب الأولوية

### P0/P1 — يجب إصلاحها قبل اعتبار الطبقة جاهزة

#### 1. وجود مسارين متوازيين للموصلات

**المثبت:** `ConnectorBootstrap` يسجل موصلات GitHub وTelegram وGoogle في `ConnectorRegistry`، لكن `app/src/main/java/com/airi/assistant/ai/tools/ToolRegistry.kt` ينشئ `GithubService` و`TelegramService` مستقلين، بينما أدوات Gmail وDrive وCalendar في الملف نفسه تعيد فشلًا ثابتًا بدل استدعاء `GoogleConnector`.

**الأثر:** قد تعرض واجهة الموصل حالة، بينما تستدعي المهارة تنفيذًا آخر أو stub. كما يمكن أن تختلف سياسات الأسرار، الصلاحيات، retry، وتسجيل النشاط بين المسارين.

**القرار المقترح:** جعل `ConnectorRegistry` مصدر الحقيقة الوحيد. يجب تحويل أدوات المهارات إلى bridge typed يستدعي `ConnectorRuntimeManager`، ثم إزالة الخدمات القديمة أو إبقاؤها كطبقة نقل داخل الموصل فقط.

#### 2. إعادة محاولة الآثار الخارجية بعد نتيجة شبكة ملتبسة

**المثبت:** `ConnectorRuntimeManager` يعيد المحاولة عندما تكون `retryable=true`. عدة موصلات تحول كل exception HTTP تقريبًا إلى retryable، بما في ذلك GitHub `create_issue` وTelegram `send_message` وMCP/automation webhooks.

**الأثر:** إذا قبل الخادم العملية ثم انقطع الرد، قد ينشئ Issue أو يرسل رسالة أو ينشئ صفحة مرة أخرى.

**القرار المقترح:** يجب أن يعلن كل action عقده:

- read-only: يمكن retry بشروط.
- mutation idempotent: يحتاج idempotency key قابلًا للتحقق.
- mutation غير قابل للإثبات: لا retry تلقائي، بل `unknown_outcome` وطلب تحقق أو continuation.

#### 3. ضعف التتبع المتزامن للطلبات الجارية

**المثبت:** المفتاح في `ConnectorRuntimeManager` مبني من `connectorId + action + System.currentTimeMillis()`. كما أن تحديث `MutableStateFlow` يتم من snapshot غير ذري لـ`ConcurrentHashMap`.

**الأثر:** استدعاءان في نفس millisecond قد يتشاركان المفتاح، وقد تضيع حالة أحدهما أو يظهر `inflightActions` ناقصًا.

**القرار المقترح:** استخدام UUID أو AtomicLong لكل invocation، و`Mutex` أو مصدر تحديث ذري واحد لـStateFlow، مع اختبارات ضغط/interleaving.

#### 4. صلاحيات مركزية غير مكتملة

**المثبت:** `ConnectorRuntimeManager.execute()` يتحقق من وجود الموصل وحالته، لكنه لا يفرض `agentId` أو `project/task/run/step ownership` أو capability موحدة قبل dispatch. بعض الموصلات تفرض صلاحياتها داخليًا، وبعضها لا يفعل ذلك.

**الأثر:** يعتمد الأمان على حسن تنفيذ كل connector، ويمكن لمسار موثوق جزئيًا أن يستدعي موصلًا خارجيًا أو محليًا دون بوابة موحدة.

**القرار المقترح:** إضافة `ConnectorAuthorizationContext` و`ConnectorCapabilityPolicy` قبل التنفيذ، مع deny-by-default للأفعال الحساسة، وتسجيل قرار السماح/الرفض دون أسرار.

#### 5. تسريب محتمل لأسرار Telegram وwebhook

**المثبت:** `TelegramConnector` يبني الطلبات كالتالي: `https://api.telegram.org/bot$token/...`. كما أن `N8nConnector` يضع جزءًا من webhook URL في `ConnectorState` و`ConnectorOutput.data`، وتقبل Zapier/IFTTT عناوين webhook من المدخلات دون allowlist كافية.

**الأثر:** قد تظهر الأسرار في URL logs أو proxy telemetry أو UI state أو مخرجات الوكيل.

**القرار المقترح:** إزالة الأسرار من URLs قدر الإمكان، فرض redaction على state/output/errors، وتطبيق HTTPS + host allowlist + سياسة موافقة للمضيف الجديد. يجب تدوير أي Telegram tokens تعرضت سابقًا لسجلات URL.

#### 6. تجاوز سياسة تعطيل التكامل في n8n

**المثبت:** `N8nConnector` لا يفحص `ReleaseScopePolicy.externalAutomationIntegrationsEnabled` قبل قراءة credential أو التنفيذ، خلاف IFTTT وZapier.

**الأثر:** يمكن لتكامل خارجي أن يبقى قابلًا للاستدعاء رغم تعطيله على مستوى الإصدار.

**القرار المقترح:** بوابة موحدة قبل قراءة السر وقبل الاتصال وقبل عرض capability.

#### 7. نجاحات وهمية في موصلات الأتمتة

**المثبت:** n8n يحول `null` من `N8nIntegration` إلى `ConnectorOutput.Success`. وIFTTT/Zapier يعيدان نصوصًا مثل `Trigger failed` داخل `Success`، وZapier `pause_zap`/`resume_zap` لا ينفذان API فعليًا بل يعيدان رسالة request sent.

**الأثر:** الوكيل والواجهة قد يعتقدان أن العملية تمت بينما فشلت أو لم تبدأ أصلًا.

**القرار المقترح:** عقد HTTP typed موحد: status code، provider code، request id، retryability، ونتيجة واضحة. الإجراء غير المدعوم يجب أن يعيد `unsupported_action` لا Success.

### P1 — مخاطر صحة وتشغيل وخصوصية

#### دورة الحياة

- `ConnectorRegistry.register()` يستبدل المرجع دون فصل القديم.
- `unregister()` قد يفصل مرجعًا لم يعد هو المرجع الحالي في حالات race.
- `connectAll()` fire-and-forget ويبتلع exceptions ولا يعيد نتيجة أو Job.
- `ConnectorHealthMonitor.start()` غير محمي ضد الاستدعاء المتوازي ولا يملك `stop/cancel`.
- health monitor ينتظر 200ms ثابتة ثم ينشر snapshot قد تكون ناقصة.
- health monitor يقرأ `connected` ولا يعرض `healthy` رغم وجوده في `ConnectorState`.
- بعض connectors تعتبر وجود token أو URL دليلًا على الصحة دون probe.

#### الأسرار

- `ConnectorAuthManager.isTokenValid()` لا يبدأ بفحص `token.isNotBlank()` عندما يوجد expiration مستقبلي.
- getters عامة (`getToken/getCredential`) غير مربوطة بـproject أو capability أو consumer.
- `commit()` متزامن على thread المستدعي وقد يسبب jank إذا استدعي من main thread.
- GitHub وTelegram لا يطبقان contract الموحد بعد `disconnect`: Telegram يغير state فقط، وGitHub لا يمسح PAT؛ ويمكن أن يبقى التنفيذ قادرًا على قراءة السر.

#### LLM/API

- `LlmCertPins.PINNING_ENABLED = false` رغم وجود بنية pinning وتعليقات تفترض تفعيلها في production.
- أخطاء 400/401/403/404 تعامل أحيانًا كأخطاء قابلة لإعادة المحاولة.
- fallback قد يرسل نفس prompt إلى مزود سحابي ثانٍ دون سياسة خصوصية أو موافقة واضحة.
- Gemini يضع API key في query string.
- `baseUrl` قابل للتهيئة دون HTTPS/host allowlist كافية.
- لا توجد حدود مركزية موحدة لحجم history وmax tokens وtemperature.

#### GitHub/Google/Telegram

- Telegram لا يحفظ offset في `get_updates`، وقد يعيد نفس التحديثات.
- Telegram لا يغلق OkHttp response صراحةً في helpers.
- GitHub يتبع `Link: rel=next` دون تقييد host، وقد يرسل Authorization إلى مضيف غير GitHub.
- GitHub HTTP helpers لا تملك تصنيفًا موحدًا لـ401/403/404/429/5xx ولا إدارة موارد موحدة.
- Google Drive query لا يهرب apostrophe رسميًا.
- Google وTelegram لا يفرضان clamp موحدًا للـlimit/max results.

#### Local/MCP

- قراءة Clipboard تعيد النص الكامل للوكيل دون موافقة أو redaction.
- Contacts يعيد أسماء وأرقامًا كاملة، ويفحص permission عند connect فقط.
- `DeviceActionPolicy` يعيد Allowed للأفعال غير المعروفة بدل deny-by-default.
- `McpConnector` قد يبتلع `CancellationException` عبر `runCatching`.
- `create_page` في Notion/MCP أثر جانبي بلا approval/capability موحد.
- JSON غير الصالح في بعض MCP paths يستبدل بصمت بـ`{}` بدل رفض المدخل.
- retry على POST create_page قد يكرر الصفحة.

### P2 — نواقص جودة وتوثيق

- README لا يوثق `start/stop`، semantics الخاصة بـconnectAll، retry budget، health timeout، أو الفرق بين global AuthManager وproject SecretVault.
- لا يوجد taxonomy موحد للأخطاء بين الموصلات.
- رسائل provider الخام تدخل أحيانًا إلى state أو output.
- بعض المخرجات تعيد response body كاملًا أو JSON خامًا دون حد حجم واضح.
- لا توجد تغطية موحدة لـmain-thread safety، cancellation الحقيقي، lifecycle races، أو provider sandbox evidence.

## تقييم التوثيق الحالي مقابل التنفيذ

| العقد المعلن في README | الحالة الفعلية |
|---|---|
| availability لا تعني Healthy دون تحقق | غير محقق في n8n وIFTTT وبعض LLM paths؛ وجود URL/token يكفي أحيانًا. |
| الأسرار مربوطة بسياق المشروع/capability | محقق جيدًا في GitHub project path فقط؛ غير موحد في AuthManager وباقي الموصلات. |
| الأثر الخارجي يحتاج approval/typed continuation | محقق جزئيًا في GitHub، وغير موحد في Telegram/MCP/webhooks. |
| الفشل لا يتحول إلى نجاح شكلي | مكسور في n8n وIFTTT وZapier لبعض المسارات. |
| retry محدود حسب العقد | غير محقق؛ `retryable=true` واسع ومكرر على مستويين. |
| disconnect ينهي القدرة على التنفيذ | غير محقق في GitHub وTelegram بسبب بقاء الأسرار قابلة للقراءة. |

## خريطة الاستدعاء الحالية

```text
Chat / Agent / Skill
  ├─ ToolRegistry القديم
  │    ├─ GithubService
  │    ├─ TelegramService
  │    └─ Google stubs (تفشل دائمًا لبعض الأفعال)
  │
  └─ ConnectorRuntimeManager
       └─ ConnectorRegistry
            ├─ RemoteLlmConnector
            ├─ GitHubConnector
            ├─ GoogleConnector
            ├─ TelegramConnector
            ├─ IFTTT / Zapier / n8n
            ├─ Notion MCP
            └─ Local/System connectors
```

هذه الازدواجية هي أكبر مشكلة معمارية؛ قبل إضافة موصلات جديدة يجب إغلاق المسار القديم أو جعله adapter داخليًا للمسار الموحد.

## ترتيب التنفيذ المقترح

### المرحلة C1 — توحيد المصدر والصلاحيات

1. اعتماد `ConnectorRegistry` كمصدر الحقيقة الوحيد.
2. إنشاء bridge typed من أدوات المهارات إلى `ConnectorRuntimeManager`.
3. إزالة Google stubs وGithubService/TelegramService المستقلين أو تحويلهما إلى transport داخلي.
4. إضافة `ConnectorAuthorizationContext` وdeny-by-default للأفعال الحساسة.
5. تعريف matrix لكل action: read، write، approval، ownership، retry safety، privacy.

### المرحلة C2 — إصلاح الصحة والفشل وإعادة المحاولة

1. فصل `configured` و`connected` و`healthy` و`degraded` و`unknown`.
2. جعل health monitor يستخدم `awaitAll` مع timeout مستقل وsnapshot مكتملة و`stop()` idempotent.
3. تصنيف HTTP errors مركزيًا مع Retry-After وbackoff/jitter.
4. منع retry للـunknown outcome في mutations.
5. استخدام invocation ID حقيقي وتحديث inflight ذري.

### المرحلة C3 — حماية الأسرار والشبكة

1. إصلاح `disconnect` لجميع الموصلات لمسح/إبطال السر أو فصل القدرة فعليًا.
2. إزالة الأسرار من URL/state/output/error.
3. HTTPS وhost allowlist وURL/path encoding لكل webhook وpagination.
4. إصلاح certificate pinning أو تعطيل ادعاء الحماية وتوثيق القرار؛ لا يجوز بقاء release مع pinning معطلاً دون قرار صريح.
5. ربط getters السرية بـcapability/consumer بدل getter عالمي عام.

### المرحلة C4 — اختبارات حقيقية

يجب إضافة اختبارات تغطي:

- lifecycle: connect/disconnect/execute.
- التزامن وreplace/unregister وinflight.
- cancellation وtimeout الذي يلغي HTTP call فعليًا.
- 2xx/4xx/401/403/404/409/429/5xx لكل provider.
- retry وعدم تكرار side effects.
- OAuth state/PKCE/cancel/revoke/recovery.
- عدم تسريب token في URL/log/state/output.
- PII/privacy للحافظة وجهات الاتصال.
- Google/GitHub/Telegram paths من خلال registry نفسه.
- MCP schemas، UUID، filter JSON، pagination، وحجم الاستجابة.

### المرحلة C5 — إثبات provider فعلي

بعد نجاح اختبارات الوحدة والتكامل فقط، تُجرى اختبارات sandbox على جهاز Android حقيقي أو CI مناسب، باستخدام credentials اختبارية منفصلة، وتتضمن consent وcancel وrevoke و401/403 وrate limit وnetwork loss وrecovery. لا يكفي نجاح compile أو ظهور بطاقة Connected في UI.

## الحكم النهائي

**الحكم: Request Changes — لا يُنصح بإعلان Connectors مكتملة أو فتح تكاملات خارجية إضافية قبل تنفيذ C1 وC2، ثم C3 للموصلات التي تحمل أسرارًا أو تنفذ آثارًا خارجية.**

الطبقة ليست فاشلة؛ لديها أساس يمكن البناء عليه، وGitHub يقدم نموذجًا جيدًا لمسار approval وownership. لكن يجب تعميم هذا النموذج بدل إضافة موصلات منفردة بسياسات مختلفة.

هذه المراجعة تقرير فهم وتحديد فجوات فقط؛ لم تُجرَ تعديلات على كود Connectors في هذه الجولة.
