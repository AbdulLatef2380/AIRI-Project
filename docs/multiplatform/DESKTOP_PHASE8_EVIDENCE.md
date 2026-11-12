# Desktop Phase 8 Evidence

## القرار

Desktop في هذه المرحلة **Foundation/Prototype capability-gated**، وليس agent runtime مكتملًا. توجد حدود pure/shared واختبارات persistence وattachments وpairing، لكن لا يوجد model adapter أو remote-provider adapter أو executable skill adapter. لذلك تبقى inference وskills execution `PLANNED`.

## ما تم إثباته داخل المستودع

| المسار | الإثبات |
|---|---|
| history | `DesktopConversationStore` يكتب ويعيد تحميل رسائل الجلسة، مع test عبر instance جديد |
| attachments | `DesktopAttachmentStore` يتحقق من regular file/size/count، ينسخ إلى AIRI-owned storage، ولا يحفظ source path، وclear يحذف النسخة |
| preferences | `DesktopPreferencesStore` يحفظ capability-hints ويعيدها |
| pairing | `PairedDesktopControl` يفرض session/sequence/revocation، ويمنع remote text بلا local approval |
| model gating | `DesktopCapabilities` يعلن Android-local unavailable وremote-provider requires configuration؛ `DesktopAgent` يعيد `AWAITING_MODEL_CONFIGURATION` ولا يختلق جواباً |
| package | Linux DEB وWindows MSI/process smoke لهما أدلة سابقة موثقة في gate documents، لكن التفاعل الكامل يبقى خارجياً |

## ما لم يُنفذ

- لا يوجد `DesktopModelPort` موصول بمزود inference فعلي.
- لا يوجد credential broker أو secure desktop vault.
- لا يوجد tool dispatcher/skill adapter مستقل لـDesktop.
- لا يوجد قبول تفاعلي كامل للـpicker أو restart/recovery على Windows وLinux.

## بوابة الإغلاق التالية

لا يجوز تغيير `PLANNED` إلى `RUNTIME_VERIFIED` قبل إضافة adapter خلف port مشترك في `core-domain`، ثم اختبار contract على JVM، ثم provider smoke مع credentials اختبارية، ثم scenario تفاعلي يحفظ request/response/restart evidence. أي adapter جديد يجب ألا يستورد Android classes داخل `commonMain` أو desktop core.
