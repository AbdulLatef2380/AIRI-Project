# AIRI Scheduled Automation Acceptance Matrix

هذه الوثيقة تفصل بين ما يثبته المصدر واختبارات JVM، وما لا يمكن إثباته دون Android OS فعلي.

| السيناريو | عقد المصدر | اختبار محلي متاح | اختبار جهاز مطلوب | حالة الإصدار |
|---|---|---|---|---|
| queued job persistence | `DurableTaskManager` يكتب حالة المهمة ذرياً ويستخدم unique WorkManager name | lifecycle/retry/ownership tests | kill process ثم فتح التطبيق وقراءة الحالة | `LOCAL_CONTRACT_VERIFIED` |
| network constraint | `requiresNetwork` يترجم إلى `NetworkType.CONNECTED` | policy tests | قطع/استعادة الشبكة ومراقبة retry | `EXTERNAL_VERIFICATION_REQUIRED` |
| charging constraint | `requiresCharging` يترجم إلى WorkManager constraint | model tests | بطارية/شحن حقيقي | `EXTERNAL_VERIFICATION_REQUIRED` |
| transient retry | `ScheduledWorkerOutcomePolicy.MAX_RETRY_ATTEMPTS = 3`، retry لا يصبح success | `ScheduledWorkerOutcomePolicyTest` | قياس backoff في OS | `LOCAL_CONTRACT_VERIFIED` |
| Doze/app standby | لا يوجد ادعاء بأن JVM يثبت wake delivery | لا ينطبق | `adb shell dumpsys deviceidle force-idle` ثم انتظار job | `EXTERNAL_VERIFICATION_REQUIRED` |
| reboot | WorkManager هو owner للاستمرارية، لا يوجد reboot proof في هذا المستودع | لا ينطبق | schedule → reboot → verify one execution | `EXTERNAL_VERIFICATION_REQUIRED` |
| timezone/DST | payload يحتفظ بالـinstant/contract، لكن OS trigger policy خارج JVM | policy tests فقط | تغيير المنطقة وDST مع one-time/periodic jobs | `EXTERNAL_VERIFICATION_REQUIRED` |
| notification permission/channel | channel باسم `airi_tasks_channel` وإشعار completion مشروط بـ`showNotification` | source/static checks | Android 13+ permission، blocked channel، tap interaction | `EXTERNAL_VERIFICATION_REQUIRED` |
| duplicate execution | unique work uses `ExistingWorkPolicy.KEEP` | durable link/idempotency tests | force-stop/restart and inspect run count | `EXTERNAL_VERIFICATION_REQUIRED` |
| cancellation | cancel calls unique work cancellation and updates durable state | lifecycle tests | cancel while queued/running and verify no stale callback | `EXTERNAL_VERIFICATION_REQUIRED` |

## Required device run

قبل رفع scheduling إلى `RUNTIME_VERIFIED` يجب حفظ run evidence يتضمن API level، manufacturer/model، timezone قبل/بعد، battery state، Doze state، notification permission/channel state، task id، run count، وtimestamps. لا يكفي screenshot أو compilation.

## Safety rule

فشل maintenance أو provider لا يتحول إلى نجاح مصطنع: النتيجة إما retry ضمن الحد أو `FAILED`. الأعمال التي تتطلب approval تبقى held في الوضع المجدول ولا تُنفذ تلقائياً.
