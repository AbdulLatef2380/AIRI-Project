# AIRI Product Capability Matrix

**حالة الوثيقة:** مصدر الحقيقة لواجهة المنتج ومواد الإصدار
**قاعدة الترقية:** لا تنتقل القدرة من `PLANNED` أو `ARCHITECTED` إلى `IMPLEMENTED` إلا مع source-set واضح، أمر بناء ناجح، اختبار سلوك، وartifact أو سجل تشغيل.

## مصفوفة الإصدار الحالي

| القدرة | Android | Desktop Linux | Desktop Windows | Web | السلوك المعروض للمستخدم | الدليل الحالي |
|---|---|---|---|---|---|---|
| محادثة نصية محلية | `IMPLEMENTED` | `RUNTIME_VERIFIED` محدود | `BUILDS` فقط | `PLANNED` | Android يعمل ضمن نطاقه؛ Desktop لا يدّعي inference قبل adapter | `docs/multiplatform/PLATFORM_MATRIX.md` و `GATE_DESKTOP_CAPABILITIES.md` |
| اختيار النموذج | `IMPLEMENTED` | `BUILD_VERIFIED` مع حالات unavailable | `BUILD_VERIFIED` مع حالات unavailable | `PLANNED` | النموذج غير الجاهز يُرفض بسبب typed reason ولا يتحول إلى نجاح | `app-desktop/.../DesktopAgent.kt`, `DesktopCapabilities.kt`, اختبارات Desktop |
| inference/remote adapter Desktop | `N/A` | `PLANNED` | `PLANNED` | `PLANNED` | تظهر رسالة إعداد واضحة؛ لا يوجد رد مصطنع | `DesktopExecutionStatus.AWAITING_MODEL_CONFIGURATION` |
| skills execution Desktop | `N/A` | `PLANNED` | `PLANNED` | `PLANNED` | skills غير المتاحة تُعرض مع سبب وتُرفض | `DesktopCapabilities.kt`, `GATE_DESKTOP_CAPABILITIES.md` |
| history/preferences Desktop | `IMPLEMENTED` Android | `BUILD_VERIFIED` | `BUILD_VERIFIED` | `PLANNED` | سجل محلي وتفضيلات capability hints؛ التشفير/المزامنة ليست ادعاءً | `DesktopConversationStore.kt`, `DesktopPreferences.kt` |
| attachments Desktop | `IMPLEMENTED` Android | `BUILD_VERIFIED` | `BUILD_VERIFIED` | `PLANNED` | نسخ خاص، اسم AIRI، حدود حجم، وتنظيف عند clear؛ picker التفاعلي خارجي | `DesktopAttachmentStore.kt`, اختبارات Desktop |
| pairing/remote control | `IMPLEMENTED` Android/core | `BUILD_VERIFIED` | `BUILD_VERIFIED` | `PLANNED` | الأوامر مقيدة بالجلسة والتسلسل وموافقة محلية للنص | `PairedDesktopControl.kt`, `core-domain` remote tests |
| scheduling | `IMPLEMENTED` عبر WorkManager | `PLANNED` | `PLANNED` | `PLANNED` | retries/constraints/persistence موثقة؛ Doze/reboot/timezone/notification تحتاج جهازاً | `AIRI_SCHEDULE_ACCEPTANCE_MATRIX.md` |
| realtime cloud voice | `PLANNED` / contract only | `PLANNED` | `PLANNED` | `PLANNED` | لا يظهر كمسار cloud نشط؛ المسار النشط محلي Vosk + Android TTS | `app/src/main/java/com/airi/assistant/voice/README.md` |

## قواعد الواجهة

1. لا تعرض الواجهة زر تنفيذ أو وعداً بقدرة حالتها `PLANNED` أو `ARCHITECTED`.
2. كل رفض capability يجب أن يكون typed وقابلاً للعرض للمستخدم، مع اقتراح الخطوة التالية دون اختلاق نتيجة.
3. كلمة `realtime` لا تعني full-duplex transport؛ لا تستخدم في تسمية مسار نشط قبل إثبات microphone → transport → model → AudioTrack end-to-end.
4. أي ادعاء `RUNTIME_VERIFIED` يجب أن يذكر النظام والجهاز/المحاكي وسيناريو القبول، لا مجرد نجاح compilation.

## قائمة release review

- [ ] تحديث هذه المصفوفة مع كل release candidate.
- [ ] ربط كل ترقية بحالة commit وأمر الاختبار وartifact.
- [ ] مراجعة `docs/AIRI_KNOWN_LIMITATIONS.md` و`docs/AIRI_RELEASE_READINESS_CHECKLIST.md` في نفس التغيير.
- [ ] منع capability placeholders من الظهور كميزات مكتملة في Android/Desktop UI.
- [ ] تسجيل العناصر الخارجية كـ`EXTERNAL_VERIFICATION_REQUIRED` بدلاً من PASS افتراضي.
