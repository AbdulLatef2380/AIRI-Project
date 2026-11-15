# AIRI Response Experience A/B/C Implementation Plan

**Goal:** تثبيت عقود الأدلة والتركيب، توحيد current-turn في البث والرد النهائي، وإغلاق مسار artifact من المرجع الموثق إلى بطاقة قابلة للمعاينة مع حالات فشل واضحة.

**Architecture:** تبقى طبقة التخطيط pure وprovider-independent. يحمل `ResponseComposition` evidence typed لا flags نجاح مضللة، ويُبنى resolver من turn الحالي فقط مع إبقاء history للأرقام فقط. يمرر ChatScreen نفس عقد السياق للبث والرسالة المكتملة، بينما يتحقق output pipeline من ownership ووجود الملف قبل إظهار بطاقة نجاح.

**Tech Stack:** Kotlin, Jetpack Compose, MVVM, Coroutines/Flow, Room-backed ArtifactManager, pure JVM tests.

**Spec:** `docs/generative-ui/GENERATIVE_UI.md`, `docs/generative-ui/AIRI_RESPONSE_DNA.md`, `docs/generative-ui/RESPONSE_PRESENTATION_CONTEXT.md`, و`pasted_content.txt` المرفق الأخير.

## Global Constraints

- لا إرسال تلقائي لـActions/Forms؛ المعاينة والـcomposer منفصلان عن Send.
- لا evidence تاريخي في current turn.
- لا artifact/media نجاح دون persisted, owned, verifiable output.
- لا عرض لمسار filesystem أو payload حساس.
- streaming لا يجعل GenUI تفاعليًا قبل اكتمال fence والتحقق.
- كل تغيير يجب أن يملك اختبارًا أو بوابة static واضحة.

## Review Focus

- رسالة عادية تذكر image/document/profile لا تتحول إلى deliverable دون evidence typed.
- streaming والنهائي لنفس turn لا يرثان مرفقات تاريخية.
- artifact مفقود/محذوف/من مشروع آخر لا يظهر كبطاقة نجاح.
- ثنائي غير قابل للنص لا يُقرأ UTF-8 في preview.
- composition لا تعلن section artifact/media/workspace بلا نتيجة مثبتة.

### Task A: Contract Stabilization

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/text/ConversationResponseComposition.kt`
- Modify: `app/src/main/java/com/airi/assistant/ui/text/CurrentTurnContextResolver.kt`
- Test: `app/src/test/java/com/airi/assistant/ui/text/ConversationResponseCompositionTest.kt`
- Test: `app/src/test/java/com/airi/assistant/ui/text/CurrentTurnContextResolverTest.kt`

- [ ] إضافة typed delivery state وartifact evidence إلى العقود مع invariants تمنع إعلان مخرج مكتمل بلا مرجع.
- [ ] جعل resolver يعيد current-turn evidence صريحًا منفصلًا عن history metrics.
- [ ] إضافة اختبارات absent/pending/failed/completed وعدم تسريب history.

### Task B: Resolver and Composition Closure

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt`
- Modify: `app/src/main/java/com/airi/assistant/ui/text/AssistantResponsePresentationPolicy.kt`
- Modify: `app/src/main/java/com/airi/assistant/ui/text/ContentAwareMessageRenderer.kt`
- Test: pure JVM resolver/composition tests.

- [ ] استخدام current-turn projection نفسها للبث والنهائي مع assistant anchor صحيح.
- [ ] منع sections الخاصة بالتسليم من الظهور لمجرد كلمات الطلب؛ ربطها بحالة evidence.
- [ ] تثبيت GenUI fence: streaming inert، final validated فقط.

### Task C: Output Pipeline Closure

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/ui/text/ArtifactReferenceResolver.kt`
- Modify: `app/src/main/java/com/airi/assistant/ui/components/ArtifactReferenceCardView.kt`
- Modify: `app/src/main/java/com/airi/assistant/ui/screens/ArtifactPreviewScreen.kt`
- Modify/add: resource strings as needed.
- Test: `app/src/test/java/com/airi/assistant/ui/text/ArtifactReferenceResolverTest.kt` and manager/pipeline tests where available.

- [ ] إرجاع typed reference resolution مع حالات missing/cross-project/invalid.
- [ ] بطاقة artifact تعرض الحالة الموثقة، hash/provenance المختصر، وتبقى قابلة للفهم عند الفشل.
- [ ] المعاينة النصية فقط للمحتوى النصي؛ الثنائية تمر عبر URI/MIME أو تعرض unsupported دون UTF-8 decoding.
- [ ] إبقاء كل route project-scoped وعدم كشف filesystem path.

### Final verification

- [ ] `git diff --check`
- [ ] static security/XML gates الموجودة في المستودع.
- [ ] `./gradlew :app:testDebugUnitTest` إن توفرت Android SDK، وإلا توثيق الحظر بدقة.
- [ ] حفظ progress report يربط النتائج بالـSHA.

## Task D: Runtime lifecycle and trace closure

- تثبيت انتقالات `PAUSED`/`RESUMING` داخل `ExecutionStateMachine` مع بقاء terminal outcome exactly-once.
- إبقاء correlation tuple (`sessionId`, `executionId`, `messageId`) هو المرجع الوحيد لربط AgentLoop وtrace وواجهة المحادثة.
- إضافة اختبار JVM يمنع استبدال نتيجة نهائية بعد الاستئناف أو الإلغاء.

## Task E: Accessible and resilient response surfaces

- جعل عناصر GenUI التفاعلية قابلة للاستخدام عبر TalkBack مع أسماء وأدوار واضحة وحجم لمس لا يقل عن 48dp.
- استخدام ألوان الثيم، بما فيها لون الخطأ، بدل ألوان ثابتة لا تضمن التباين في dark/high-contrast themes.
- إعلان أخطاء الحقول المطلوبة عبر live region، ودعم RTL عبر موارد مترجمة دون نصوص إنجليزية ثابتة.
- لا تُعتبر اختبارات Compose/instrumentation ناجحة إلا بعد توفر Android SDK وتشغيلها فعليًا.
