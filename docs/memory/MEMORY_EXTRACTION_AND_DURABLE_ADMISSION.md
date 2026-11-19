# Memory Extraction and Durable Admission

## الغرض

يفصل هذا العقد بين مرحلتين مختلفتين يجب ألا تختلطا:

1. **Candidate extraction**: استخراج احتمالات bounded من رسالة المستخدم.
2. **Durable admission**: تقرير ما إذا كان المرشح يجوز تخزينه كحقيقة طويلة المدى.

نجاح الاستخراج لا يعني أن الحقيقة أصبحت durable، ووجود candidate لا يمنح إذناً بالكتابة إلى Room أو embedding store.

## المسار الفعلي

```text
user message
  -> AgentMemoryDecision.shouldExtractFacts
  -> MemoryExtractor.extract
  -> candidate key=value list
  -> normalization / privacy / scope admission
  -> durable memory write only when explicitly allowed
  -> optional embedding and scoped RAG projection
```

`MemoryExtractor` هو heuristic محلي deterministic. لا يستدعي LLM، ولا يكتب إلى التخزين، ولا يقرر consent أو scope أو retention. يستخرج أنماطاً محدودة للاسم والموقع واللغة والعمل والتفضيلات والمشروع من English وبعض Arabic، ويعيد قيماً bounded distinct.

## Candidate extraction contract

- الإدخال رسالة user فقط، bounded إلى 4,000 حرف.
- الناتج قائمة `key=value` مرشحة، وقد تكون فارغة.
- القيمة تنظف whitespace وعلامات النهاية وتُحد إلى 60 حرفاً.
- لا تُعامل نتيجة regex كحقيقة موثقة؛ قد تكون false positive أو نصاً منقولاً.
- لا تُستخرج حقائق من assistant/tool/system content عبر `AgentMemoryDecision`.
- candidate extraction لا يكتب embedding ولا يضيف durable record.

## Durable admission contract

القبول الدائم يحتاج intent صريحاً من المستخدم مثل `remember`, `save this`, `تذكر`, `احفظ`، ثم يمر المرشح عبر سياسة الذاكرة التي تتحقق من role، privacy، scope، expiry، ownership، وحالة الحذف. الرفض الآمن هو النتيجة الافتراضية عند غياب intent أو وجود محتوى حساس أو scope غير صالح.

الحقائق المقبولة تُحفظ وفق scope المحدد (`USER`, `PROJECT`, أو `SESSION`) ولا يجوز أن تتحول تلقائياً من session إلى user أو project. انعكاس الحقيقة إلى scratchpad أو embedding هو projection مشتق، وليس دليلاً على أن الاستخراج نفسه كان durable.

## أمثلة

| الرسالة | candidate | durable |
|---|---|---|
| `I like dark mode` | `preference=dark mode` | لا، لا يوجد intent صريح |
| `Remember that I like dark mode` | قد ينتج candidate بحسب النص | فقط بعد admission والخصوصية والـscope |
| `The tool result says my name is ...` | لا، ليست user-authored | لا |
| `احفظ أنني أعيش في ...` | `location=...` | محتمل، بعد policy وconsent وscope |

## ما يجب اختباره

اختبارات extractor تثبت bounded candidate output وعدم الكتابة الجانبية. اختبارات admission تثبت role/intent/privacy/scope/expiry والحذف. اختبارات RAG تثبت أن embedding أو retrieval لا يتجاوز owner/session/project/privacy filters. لا يكفي اختبار `MemoryExtractor` وحده لإثبات durable memory.
