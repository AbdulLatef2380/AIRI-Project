# خطة تنفيذ المرحلة الثالثة: GitHub App OAuth عبر Broker وربط أدوات AIRI

> **للعاملين الوكلاء:** تُنفذ المهام بالترتيب، وكل خطوة لها اختبار مستقل. لا تُنفذ `push` أو `merge` ضمن هذه الخطة دون موافقة صريحة.

**الهدف:** تفعيل GitHub App OAuth عبر Broker حقيقي، ثم تمكين أدوات GitHub القرائية أولاً، وإضافة الكتابة فقط خلف موافقات AIRI المستمرة.

**المعمارية:** يبقى Android عميلاً عاماً لا يملك client secret أو private key. يستقبل Broker callback HTTPS، يتحقق من state وPKCE، يتبادل code مع GitHub، ويحتفظ بالتوكنات خلف جلسة opaque مرتبطة بمستخدم AIRI ومشروعه. ينفذ Broker عمليات GitHub بعد التحقق من repository grant، بينما يبقى `ConnectorToolBridge` و`ConnectorRuntimeManager` و`GitHubMutationPolicy` نقاط الدخول الوحيدة من AgentLoop.

**التقنيات:** Kotlin/Android الحالية، Broker HTTPS خلفي بحاوية، مخزن أسرار مُدار، مخزن جلسات مشفر، GitHub App REST API، اختبارات JVM وcontract/integration tests، وCI الحالي.

**المواصفة المرجعية:** `docs/research/GITHUB_APP_BROKER_REQUIREMENTS_2026-10-19_AR.md`.

## القيود العامة

- لا endpoint أو JSON schema أو handoff behavior يُعتبر معتمداً قبل وثيقة wire contract واختبار contract مشترك بين Broker وAndroid.
- لا client secret أو private key أو access/refresh token داخل APK أو Git أو logs أو telemetry.
- القراءة أولاً؛ لا تُكشف أدوات الكتابة قبل approval continuation صالح.
- لا fallback تلقائي إلى PAT عند فشل OAuth.
- كل جلسة ومخزن repository grant مربوطان بـAIRI user ID وproject ID وGitHub numeric IDs.
- لا `push` أو `merge` دون موافقة المستخدم.

## Review Focus

- إعادة استخدام callback أو handoff code: يجب أن يفشل مرة ثانية ولا ينشئ جلسة.
- تبديل المشروع أثناء OAuth: يجب أن يرفض callback ولا يخلط الجلسات.
- نقص صلاحية أو SAML/SSO: يجب أن تبقى الجلسة غير جاهزة وتعرض سبباً قابلاً للإصلاح.
- repository خارج grant: يجب أن يرفض Broker الطلب قبل GitHub API.
- انتهاء أو إبطال refresh token: يجب أن يطلب إعادة التفويض ولا يستخدم PAT صامتاً.
- كتابة بــSHA قديم أو continuation مستهلك: يجب أن تفشل دون commit جديد.

---

### Task 0: تثبيت الأدلة وقرار Broker

**Files:**
- Modify: `docs/research/GITHUB_APP_BROKER_REQUIREMENTS_2026-10-19_AR.md`
- Create: `docs/integrations/GITHUB_BROKER_WIRE_CONTRACT.md` بعد توفير مواصفات Broker الفعلية فقط.

**Produces:** قرار نشر staging، اسم النطاق، GitHub App registration، callback URI، ونطاقات النسخة الأولى، أو قرار موثق بأن Broker غير جاهز.

- [ ] توثيق Broker base URL وenvironment وcallback URI وGitHub App client ID/slug في إعدادات سرية/CI، لا في Git.
- [ ] توثيق wire schemas الفعلية: start/callback/exchange/session/refresh/revoke/repositories/read/write/error.
- [ ] إضافة contract fixture لا يحتوي أسراراً حقيقية.
- [ ] رفض بدء Task 1 إذا غابت أي قيمة عامة لازمة أو لم تُعتمد schemas.

**التحقق:** فحص الأسرار، تحقق HTTPS/redirect exact match، وقراءة contract من Broker staging.

### Task 1: Broker OAuth transaction وhandoff

**Files:**
- Broker service: create حسب منصة النشر المعتمدة، لا داخل Android module.
- Modify: `app/src/main/java/com/airi/assistant/connector/app/github/GithubBrokerClient.kt` بعد اعتماد wire contract.
- Test: Broker unit/contract tests و`GithubBrokerClientTest`.

**Produces:** state/PKCE transaction أحادي الاستخدام، callback exchange server-side، handoff opaque أحادي الاستخدام، وtyped mapping للأخطاء.

- [ ] اختبار state mismatch وreplay وTTL وproject mismatch.
- [ ] تنفيذ callback بحيث لا يظهر code أو verifier أو token في logs.
- [ ] اختبار refresh rotation: refresh token القديم لا يعمل بعد استعماله.
- [ ] ربط Android browser launch وcallback handoff بالعقد المعتمد فقط.

**التحقق:** contract tests بين Broker وAndroid، اختبارات redaction، وفحص عدم وجود أسرار في artifact.

### Task 2: جلسة المشروع واختيار المستودعات

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/connector/app/github/GithubAuthorizationModels.kt`
- Modify: `GithubAuthorizationCoordinator.kt`, `GithubSessionStore.kt`, `ServiceLocator.kt`
- Modify: `IntegrationsViewModel.kt`, `IntegrationsScreen.kt`
- Test: جلسة المشروع واختيار المستودعات.

**Produces:** جلسة opaque/مقيدة بالمشروع، grant list مبنية على numeric repository IDs، وشاشة اختيار صريحة.

- [ ] منع حفظ raw token في Android إذا اختير proxy mode.
- [ ] عرض repository name للإنسان مع حفظ repository ID وowner ID كمراجع أمان.
- [ ] منع كشف أدوات GitHub قبل اختيار repository grant صالح وفحص read-only.
- [ ] تحديث حالة الجلسة عند expiry/revoke/SSO/permission denial.

**التحقق:** اختبار عزل مشروعين، repository خارج grant، إعادة التفويض، وإبطال الجلسة.

### Task 3: Broker-backed read adapter

**Files:**
- Create/Modify: Broker GitHub read handlers.
- Modify: `GitHubConnector.kt`, `ConnectorRuntimeManager` wiring, `ToolRegistry.kt`.
- Test: read contract/integration tests.

**Produces:** `get_user`, `list_repos`, `list_branches`, `list_commits`, `list_issues`, `search_code`, `get_file`, و`list_prs` عبر مسار session واحد.

- [ ] توحيد الأدوات القديمة والجديدة على repository/session gate واحد.
- [ ] منع `api.github.com` المباشر من Android لمسار OAuth الجديد.
- [ ] تصنيف 401/403/404/409/422/429/5xx دون تسريب response body.
- [ ] إعادة بناء tool catalog بعد connect/revoke/permission change.
- [ ] اختبار قراءة مستودع خاص حقيقي في staging، مع عدم حفظ محتوى حساس في telemetry.

**التحقق:** provider integration test بإذن read فقط، contract tests، وinstrumentation لعرض حالة الأدوات.

### Task 4: كتابة محمية بالموافقة وSHA

**Files:**
- Modify: `GitHubMutationPolicy.kt`, `GitHubConnector.kt`, `DurableTaskManager` integration.
- Broker: write handlers منفصلة عن read handlers.
- Test: approval/SHA/idempotency tests.

**Produces:** `create_issue`, `comment_issue`, `update_issue`, `create_pull_request`, و`push` فقط عبر continuation صالح.

- [ ] إبقاء الكتابة غير مكشوفة حتى ينجح Task 3 ويُثبت read path.
- [ ] لكل كتابة: project/task/mission/run/step، approval ID، expiry، idempotency key، repository ID، branch، expected SHA.
- [ ] رفض SHA القديم ورفض target خارج grant ورفض `.github/workflows` في النسخة الأولى.
- [ ] جعل تنفيذ المحتوى serial وتسجيل commit SHA/URL كدليل محدود، لا raw token.
- [ ] لا retry تلقائي لعملية كتابة بعد timeout غير محسوم؛ الحالة تصبح review-required.

**التحقق:** mutation بدون approval، continuation مستهلك، SHA conflict، duplicate idempotency، وwrite خارج repository grant.

### Task 5: اختبارات الأمان وCI والتشغيل التجريبي

**Files:**
- Modify: CI workflows حسب احتياج Broker.
- Create: staging smoke tests وsecurity test matrix.
- Modify: docs/audit evidence.

**Produces:** evidence قابل للتدقيق دون أسرار.

- [ ] تشغيل unit/contract/integration tests على Broker staging.
- [ ] فحص dependency/container/SAST/secret scanning.
- [ ] اختبار revoke من GitHub ثم أول request لاحق.
- [ ] اختبار SAML/SSO وrate limit وnetwork timeout.
- [ ] اختبار rollback وتعطيل Broker بحيث يعود Android إلى `BROKER_NOT_CONFIGURED` أو `PROVIDER_UNAVAILABLE` دون نجاح وهمي.
- [ ] تحديث مصفوفة الأدلة مع روابط CI فقط.

**التحقق:** Android CI وBroker CI كلاهما أخضر، ثم مراجعة بشرية قبل أي تفعيل production.

## بوابة الانتقال بين المهام

لا يبدأ Task 1 قبل توفر Broker staging وwire contract. لا يبدأ Task 3 قبل إثبات session/repository selection. لا يبدأ Task 4 قبل نجاح read-only provider integration. لا تُنشأ production credentials ولا تُنفذ عمليات كتابة حقيقية إلا بعد موافقة مستقلة على نافذة اختبار محدودة ومستودع اختبار.

## الحالة عند الإنشاء

- Broker فعلي: غير موجود في المستودع أو الإعدادات المفحوصة.
- Android contract: موجود ومغلق افتراضياً.
- القراءة الحالية: موجودة عبر GitHubConnector/PAT وتحتاج توحيداً مع session OAuth.
- الكتابة الحالية: approval-gated محلياً، لكنها ليست Broker-backed بعد.
- CI: Architecture Audit وOracle وSemgrep نجحت على commit الحالي؛ Android CI كان قيد التنفيذ وقت الفحص؛ Deep Audit كان في حالة failure ويجب تشخيصه قبل اعتماد أي release gate.
- لم ينفذ هذا العمل `push` أو `merge`.
