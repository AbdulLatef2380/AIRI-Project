# AIRI — مصفوفة اختبار Runtime

> **قاعدة:** CI الحالي يثبت build/unit/lint وبعض instrumentation في بيئة CI، لكنه لا يستبدل جهاز ARM64 أو حساب provider. لا يُستخدم `DONE` أو `VERIFIED` بلا evidence مناسب.

| ID | السيناريو | المستوى | البيئة المطلوبة | النتيجة الحالية | الدليل/الاختبار | الحالة التالية |
|---|---|---|---|---|---|---|
| RT-01 | fresh install وبدء التشغيل | instrumentation/runtime | ARM64 API 26، 35/36 | غير منفذ | CI يجهز APK فقط | `BLOCKED` — real device required |
| RT-02 | login Google success/cancel/provider/network error | integration/runtime | حساب Google اختباري + ARM64 | source/policy فقط | `GoogleIntegrationSignInPolicyTest` وCI | `EXTERNAL_PENDING` |
| RT-03 | GitHub OAuth callback/state/expiry/revoke | integration/runtime | GitHub App/credentials + device | غير منفذ | source audit فقط | `EXTERNAL_PENDING` |
| RT-04 | logout ثم login مع بقاء local policy | runtime | ARM64 API 26 و35/36 | غير منفذ | Auth/DataDeletion source tests جزئية | `BLOCKED` |
| RT-05 | account switching/ownership | integration/runtime | حسابان اختباريان + device | غير منفذ | profile/session unit جزئي | `EXTERNAL_PENDING` |
| RT-06 | create/switch/delete conversation | UI/runtime | ARM64، Room، process recreation | policy/CI فقط | `ChatSessionActionPolicyTest` وCI | `BLOCKED` |
| RT-07 | draft survives model/session/rotation/process kill | UI/runtime | ARM64 API 26/35/36 | policy/CI فقط | `ChatComposerDraft` tests وCI | `BLOCKED` |
| RT-08 | attachment picker/read/preview/remove/retry/cancel | UI/integration | files/camera/audio + device | security/policy فقط | attachment security regression وCI | `BLOCKED` |
| RT-09 | model first/second/10th request | integration/runtime | local model أو provider credentials + device | غير منفذ end-to-end | router/model policies فقط | `EXTERNAL_PENDING` |
| RT-10 | selected model remains bound after reload/switch | integration/runtime | device + local/cloud models | policy/CI جزئي | `ModelLoadRequestPolicyTest` وCI | `BLOCKED` |
| RT-11 | stream partial/empty/timeout/retry/cancel | integration/runtime | provider/local engine + device | policy/source جزئي | execution/credit tests جزئية | `EXTERNAL_PENDING` |
| RT-12 | agent plan shows admitted actions only | instrumentation/UI | CI emulator ثم ARM64 visual | CI policy pass؛ UI device غير منفذ | `TaskExecutionTrackerTest` وCI | `BLOCKED` للمرئي |
| RT-13 | agent failure/cancel/restart | runtime | device + owned tool/sandbox | policy/CI جزئي | `ExecutionStatusBus` tests | `BLOCKED` |
| RT-14 | usage no-charge on pre-inference failure/cancel | unit/integration | fake contract ثم provider | unit policy pass؛ provider غير منفذ | `CreditMeteringEngine`/`TokenAccountant` tests | `EXTERNAL_PENDING` |
| RT-15 | Arabic/English/Spanish/Chinese RTL/LTR | UI | device، font scales، TalkBack | resource parity/CI؛ visual غير منفذ | localization health | `BLOCKED` |
| RT-16 | light/dark/system/dynamic theme contrast | UI | API 26/35/36 devices | source/CI؛ screenshot غير منفذ | theme source audit | `BLOCKED` |
| RT-17 | permission grant/deny/permanent deny/settings return | runtime | API 26 و35/36 ARM64 | policy/CI فقط | permission policies | `BLOCKED` |
| RT-18 | Accessibility enabled/disabled disclosure | runtime | API 26 و35/36 ARM64 | exact component source check | `AccessibilityServiceStateTest` | `BLOCKED` |
| RT-19 | microphone/Vosk/wake/TTS lifecycle | runtime | ARM64 + mic/audio | policy/CI فقط | `VoiceCapabilityPolicyTest` | `BLOCKED` |
| RT-20 | WorkManager doze/reboot/retry/cancel | runtime | device/OEM or authorized farm | policy/CI جزئي | `ScheduledJobInputPolicyTest` | `EXTERNAL_PENDING` |
| RT-21 | Zapier/IFTTT remain disabled | source/instrumentation | release artifact | CI verified | `ReleaseScopePolicyTest` + run `33051994194` | `CI_VERIFIED`; no provider activation |
| RT-22 | Terminal/Sandbox isolation/process cleanup | integration/runtime | authorized sandbox/device | source partial | security contracts | `EXTERNAL_PENDING` |
| RT-23 | secrets redaction/keystore/rotation/delete | integration/runtime | device keystore + consent telemetry | source/CI only | `SecureApiKeyStoreTest`/security scan | `BLOCKED` |
| RT-24 | update Not Configured/no fake state | unit/UI | CI + device visual | policy/CI verified | `UpdateAvailabilityPolicyTest` | `CI_VERIFIED` / UI pending |
| RT-25 | local data erase cancel/confirm/restart | integration/runtime | device + Room/files | source/CI previous | deletion tests | `BLOCKED` |

## Execution order

تبدأ الاختبارات الآلية بالسياسات الخالصة، ثم عقود repository/connector، ثم instrumentation، ثم UI، وأخيراً runtime الخارجي. أي صف device/provider يُحافظ على `BLOCKED` أو `EXTERNAL_PENDING` حتى تتوفر البيئة والاعتماد والتفويض الفعلي.
