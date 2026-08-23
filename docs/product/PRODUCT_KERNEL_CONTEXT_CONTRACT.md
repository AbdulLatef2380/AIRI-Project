# عقد AIRI Product Kernel + Project Context

## الهدف

يربط هذا العقد مسار الرسالة الحي بالمشروع النشط من دون إنشاء مخزن موازٍ أو إرسال كل محتوى المشروع إلى النموذج. يدخل `ChatViewModel` إلى `RagRetriever` بالـ`projectId` للمساحة النشطة، ويضيف `RagRetriever` كتلتين منفصلتين ومحدودتين: ذاكرة/معرفة مسترجعة مقيدة بالنطاق، ومرجع metadata للملف والمخرج الخاصين بالمشروع من `ProjectContextResolver`.

> لا يعد هذا العقد أن AIRI يملك Mission aggregate مكتملًا أو محرر ملفات أو استئناف tool بعد الموافقة. إنه يغلق وصلة **Project-owned context → admitted prompt reference** فوق المخازن الحية القائمة.

## الملكية والحدود المنفذة

| المورد | المصدر الحي | مفتاح الملكية | ما يدخل السياق | حاجز العزل |
|---|---|---|---|---|
| Workspace/Project | `WorkspaceRuntime.WorkspaceSession` | `sessionId` | الاسم والوصف فقط | resolver يرفض `projectId` غير المسجل. |
| Project files | `ProjectFileManager` | `ProjectFile.projectId` | الاسم، MIME، lifecycle، index state؛ بلا URI أو path أو hash أو محتوى | `forProject(projectId)` ثم admission يطابق `candidate.projectId`. |
| Project knowledge | `ProjectKnowledgeManager` و`RagRetriever` | `KnowledgeChunk.projectId` | provenance للمصدر في resolver؛ النص فقط في RAG المقيد بالـproject | `search(projectId, ...)` وفلتر RAG المقيّد. |
| Memory | `MemoryManager` و`RagRetriever` | `ChatMessage.projectId` وscope/privacy/expiry | passages المقبولة فقط | `PROJECT` لا يرى إلا المشروع المطابق؛ privacy/expiry مفروضان قبل prompt. |
| Artifacts | `ArtifactManager` | `sessionId` المطابق للمشروع الحالي | الاسم والنوع والإصدار؛ بلا path أو preview خام | `forSession(projectId)` ثم admission يطابق الملكية. |
| Secrets | `SecretVault` | capability مرتبطة بagent/task/operation | **لا شيء** | broker يمنع raw secret، لكن project-scoped secrets ليست منفذة بعد. |

## Context admission

`ProjectContextAdmissionPolicy` لا تسمح بمرجع إلا عند تطابق `candidate.projectId == requestedProjectId`. ترتب metadata ثم file ثم knowledge provenance ثم artifact، وتوقف الإدخال عند ميزانية `1,800` حرف. لا تقص المرجع جزئياً؛ المرجع الذي لا يتسع يُسقط مع عداد omissions. وتبقى المعرفة النصية تحت ميزانية RAG الخاصة بها بدلاً من تكرارها في metadata.

```text
User message
  → active workspace projectId
  → scoped RAG (memory + knowledge; privacy/scope/expiry)
  → ProjectContextResolver (metadata/files/artifacts; ownership/budget)
  → combined untrusted reference blocks
  → model routing and execution policy
```

## ضمانات وعدم ضمانات

| الحالة | الدليل | الوصف |
|---|---|---|
| `LOCAL_VERIFIED` | `ProjectContextAdmissionPolicyTest` | يرفض مرجع Project A عند طلب Project B، ويطبق ميزانية الحروف، ويعيد `Unscoped` للمعرف الفارغ. |
| `LOCAL_VERIFIED` | `tools/verify_core_changes.py` | يثبت وجود gate الملكية والميزانية وربط resolver بمسار RAG. |
| `RUNTIME_VERIFICATION_PENDING` | Android جهاز حقيقي | اختيار مساحة، إرسال رسالة، وتحقق visual/TalkBack من السياق على نموذج محلي وسحابي. |
| `PARTIAL` | SecretVault | الأسرار محمية بقدرات ولا تدخل prompt، لكنها لا تملك بعد owner مشروعاً قابلاً للفرض. |
| `PARTIAL` | DurableTask/Artifact | المهمة تحمل `projectId` والمخرج يحمل session؛ لا توجد بعد علاقة artifact→task/run/step أو provenance كامل. |
| `PARTIAL` | Approval | الموافقة مرتبطة بالفعل بـtask/run/step في Trust Center؛ resume exact step يحتاج عقد execution continuation منفصل. |

## اختبارات العزل التالية

تغطي هذه الدفعة admission للمراجع التي يمكن للـresolver قراءتها. تبقى اختبارات التكامل التالية مطلوبة قبل أي ادعاء `VERIFIED` لعزل Product Kernel كاملاً:

1. رفض broker secret عند محاولة capability من Project A داخل execution Project B بعد إضافة `projectId` إلى capability.
2. ربط artifact بــtask/run/step ثم رفض lookup المتقاطع.
3. تنفيذ approval→paused step→resume مع process death.
4. اختبار UI Android فعلي لسياق مشروعين وRAG/knowledge/attachments.
