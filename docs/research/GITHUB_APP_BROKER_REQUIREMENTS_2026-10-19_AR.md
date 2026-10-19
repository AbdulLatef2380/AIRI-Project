# متطلبات GitHub App OAuth عبر Broker — 2026-10-19

## النتيجة الحالية

لا يوجد في مستودع AIRI Broker خلفي منشور أو مشروع Backend مستقل لتبادل GitHub authorization code. الموجود هو عقد Android typed وعميل `NotConfiguredGithubBrokerClient`، ولذلك لا يجوز إضافة HTTP adapter فعلي قبل اعتماد عقد wire ونشر خدمة تملك الأسرار.

آخر نسخة مفحوصة من `main` هي commit `9158abb003e2f01c0b08043e9055f8cb48c89a5e`، والفرع المحلي نظيف ومتزامن مع `origin/main` وقت الفحص.

## ما تثبته البنية الحالية

- `GithubBrokerClient` يعرّف عمليات المجال: بدء التفويض، تبادل code، التجديد، الإبطال، وسرد المستودعات.
- `NotConfiguredGithubBrokerClient` يفشل مغلقاً بحالة `BROKER_NOT_CONFIGURED` ولا يفتح متصفحاً ولا يصنع نجاحاً وهمياً.
- `GithubAuthorizationCoordinator` يملك state أحادي الاستخدام، عزل المشروع، التحقق من callback، التجديد، الإبطال، وتخزين الجلسة المشفر.
- `GitHubMutationPolicy` و`GitHubConnector` يفرضان موافقة task/continuation قبل `create_issue`, `comment_issue`, `update_issue`, `create_pull_request`, و`push`.
- أدوات القراءة الحالية تمر عبر `ConnectorRuntimeManager`، لكن `GitHubConnector` ما زال يملك مسار تنفيذ مباشر يعتمد على credential/PAT. لذلك ربط OAuth الحقيقي بالأدوات يتطلب إزالة هذا الازدواج، لا مجرد تبديل زر الاتصال.

## حقائق GitHub التي تقيد التصميم

GitHub App web flow يستخدم `client_id` وcallback مسجلاً و`state` عشوائياً، ويوصي بـPKCE عبر `code_challenge_method=S256`. تبادل code يحتاج `client_secret`، لذلك يجب أن يتم داخل Broker وليس داخل APK. ويمكن طلب `offline_access` للحصول على user access token منتهي الصلاحية وrefresh token؛ token المستخدم المنتهي يعيش افتراضياً 8 ساعات وrefresh token 6 أشهر، ويُستهلك refresh token مرة واحدة عند التجديد [1] [2].

صلاحيات GitHub App يجب أن تكون أقل ما يلزم. نجاح طلب API يعتمد على صلاحيات التطبيق وصلاحيات المستخدم معاً، وتحديد المستودعات متاح عبر installation/user installation APIs. يجب استعمال IDs الدائمة للمستخدم والمستودع، لا login أو slug وحدهما، وإعادة فحص وصول المؤسسة وSSO دورياً [3] [4].

تحديث ملف في GitHub يحتاج `sha` عند استبدال ملف، ويرجع `409` عند التعارض؛ لذلك يجب أن تبقى الكتابة محمية بموافقة ثابتة وبـSHA/branch متحقق منه قبل التنفيذ [5].

## أقل بنية خلفية آمنة قابلة للنشر

1. خدمة HTTPS خلفية باسم AIRI GitHub Broker، تعمل بحاوية وتملك نطاقاً ثابتاً وTLS. لا يوضع `client_secret` أو GitHub App private key في Android أو Git.
2. مخزن أسرار مُدار للخادم يحوي `GITHUB_APP_CLIENT_ID` و`GITHUB_APP_CLIENT_SECRET` وأي private key مطلوب لعمليات installation، مع تدوير وإبطال دون إعادة بناء APK.
3. مخزن جلسات خادمي مشفر يحفظ فقط: هوية AIRI، project ID، GitHub numeric user ID، installation ID، repository IDs، granted permissions، expiry، refresh-token reference المشفر، وحالة الإبطال. لا تُحفظ raw tokens في logs أو telemetry.
4. مخزن قصير العمر للـOAuth transaction/state وhandoff code أحادي الاستخدام، مع TTL قصير وربط بـproject وPKCE verifier وredirect transaction. يجب رفض replay وproject mismatch.
5. Callback HTTPS على Broker. بعد التحقق من state وPKCE وتبادل code لدى GitHub، يعيد Broker إلى التطبيق handoff code أحادي الاستخدام فقط. التطبيق يستبدل handoff code بجلسة opaque أو capability محددة؛ لا يعاد raw client secret أو private key.
6. طبقة GitHub API server-side تنفذ أولاً عمليات القراءة فقط بعد التحقق من project/session/repository grant. عمليات الكتابة تُقبل فقط مع approval continuation كامل من AIRI، idempotency key وSHA/branch المتحقق منه.
7. Secret Manager/observability يمنع تسجيل authorization code وstate وverifier وaccess/refresh tokens وrequest bodies المحتوية على محتوى المستودع.

هذه بنية متطلبات، وليست عقد API منشوراً. لا ينبغي كتابة endpoint paths أو JSON schemas حتى يُعتمد wire contract ويُحدد النشر الفعلي.

## الصلاحيات المقترحة للنسخة الأولى

- `Metadata: read`.
- `Contents: read`.
- `Issues: read`.
- `Pull requests: read`.
- `Contents: write` و`Issues: write` و`Pull requests: write` لا تُطلب في التفويض الأول إلا إذا كان سطح الكتابة مفعلاً صراحة، وتبقى محمية بطبقة الموافقات.
- `Workflows` لا تُطلب، ولا يُسمح بتعديل `.github/workflows` في النسخة الأولى.

## فجوات يجب إغلاقها قبل التفعيل

- `GithubAuthorizationSession` يحتوي حالياً `accessToken` و`refreshToken`، بينما التصميم الأكثر أماناً يبقيهما خلف Broker ويعرض للتطبيق session/capability opaque. يلزم قرار صريح: proxy server-side opaque session، أو short-lived user token على الجهاز. لا يجوز خلط النموذجين.
- `GithubBrokerClient.beginAuthorization()` لا يعيد browser launch effect أو callback handoff؛ يجب تعديل العقد بعد اعتماد wire contract.
- لا توجد repository selection UI كاملة مرتبطة بقرارات grant؛ يجب أن يختار المستخدم repositories المسموح بها قبل كشف أدوات الوكيل.
- أدوات القراءة القديمة في `ToolRegistry` وأدوات connector يجب أن تستخدم نفس session/repository gate ونفس error taxonomy.
- يجب توفير Broker staging وGitHub App مسجل وredirect URI قبل اختبار provider حي.

## بوابة التفعيل

يبقى `BROKER_NOT_CONFIGURED` هو السلوك الصحيح حتى تتوفر القيم العامة التالية خارج المحادثة الحساسة: broker base URL، environment، GitHub App client ID/slug، callback URI المسجل، wire contract، وأسلوب handoff. لا نحتاج ولا نطلب client secret أو private key في المحادثة.

## المراجع

[1]: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app "Generating a user access token for a GitHub App"
[2]: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens "Refreshing user access tokens"
[3]: https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/best-practices-for-creating-a-github-app "Best practices for creating a GitHub App"
[4]: https://docs.github.com/en/rest/apps/installations "REST API endpoints for GitHub App installations"
[5]: https://docs.github.com/en/rest/repos/contents "REST API endpoints for repository contents"
