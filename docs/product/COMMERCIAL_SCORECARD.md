# AIRI Core Commercial Technical Scorecard

> لا تستخدم هذه البطاقة نسباً مئوية أو ادعاءات تسويقية. كل محور يحمل حالة دليل قابلة للتتبع.

| المحور | الحالة | أساس التقييم |
|---|---|---|
| Product core | **BUILD_VERIFIED** | محادثة، إلغاء، ذاكرة، مرفقات، صوت، مهارات، ونماذج ضمن builds/CI. |
| Architecture | **SOURCE_VERIFIED** | حدود وملكية حالة موثقة مع فاحصات مصدر واختبارات. |
| Security | **BUILD_VERIFIED** | فاحص أسرار/Manifest/مرفقات وضمانات مصدر؛ اختبار اختراق مستقل **EXTERNAL**. |
| Privacy | **SOURCE_VERIFIED** | admission، تخزين خاص للمرفقات، حذف منسق، وحدود بيانات موثقة. |
| Reliability | **RUNTIME_VERIFIED** على المحاكي | CI يشغّل instrumentation؛ ظروف الأجهزة والمزودين الفعلية **NOT_RUNTIME_VERIFIED**. |
| Models/routing | **SOURCE_VERIFIED** | مسارات local/cloud وقدرات في حالة النموذج؛ صحة المزود الإنتاجية **EXTERNAL**. |
| Memory/RAG | **SOURCE_VERIFIED** | session isolation، prompt boundary، admission، وحذف. |
| Voice | **BUILD_VERIFIED** | Vosk/TTS والنص الجزئي يتجمعان؛ الميكروفون والموديل الفعليان **NOT_RUNTIME_VERIFIED**. |
| Attachments | **BUILD_VERIFIED** | سياسة حجم/نوع، خاصية التخزين، duplicate، وتنظيف الجلسة. |
| Skills/connectors | **SOURCE_VERIFIED** | فصل skill/connector ومسار OAuth؛ موصلات العملاء الفعلية **EXTERNAL**. |
| UI/UX/localization | **BUILD_VERIFIED** | Compose/RTL وparity للموارد؛ مسح بصري على أجهزة متنوعة **NOT_RUNTIME_VERIFIED**. |
| Maintainability | **BUILD_VERIFIED** | CI، scripts، Room schemas، ووثائق تدفق وحزمة تجارية. |
| White-label | **PARTIALLY_VERIFIED** | مسار الفصل موثق؛ flavors/overlays شريك حقيقي لم تُبن بعد. |
| Licensing/IP | **PARTIALLY_VERIFIED** | جرد مصدر مباشر وفصل مسؤوليات؛ clearance قانوني ورسوم متعدية **EXTERNAL**. |
| Deployment | **BUILD_VERIFIED** | Release/AAB/R8/JNI/CI؛ حسابات وتوقيع Play نهائي **EXTERNAL**. |

## استخدام البطاقة

تحدّث الحالة فقط عقب دليل قابل للتكرار: build، test، تشغيل جهاز، أو مراجعة خارجية موثقة. لا تحول `SOURCE_VERIFIED` إلى `RUNTIME_VERIFIED` لمجرد أن الواجهة أو الدالة موجودة.
