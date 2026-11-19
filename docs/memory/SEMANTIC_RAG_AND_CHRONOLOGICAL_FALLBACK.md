# Semantic RAG and Chronological Fallback Contract

## الحالة المضمونة

لا يُعد semantic RAG مضمُوناً إلا عندما يكون embedding backend محمّلاً وصالحاً فعلياً عبر نموذج embedding GGUF ومسار JNI/native المرتبط بـllama. وجود `EmbeddingService` أو جدول `message_embedding` أو كود `RagRetriever` في المصدر لا يثبت أن semantic retrieval يعمل على الجهاز.

التسلسل الفعلي هو:

```text
embedding GGUF selected
  -> JNI/native embedding context loaded
  -> EmbeddingService.isReady() == true
  -> vectors stored and dimension-compatible
  -> RagRetriever semantic path
```

إذا لم يتحقق هذا الشرط، يجب أن تبقى النتيجة classified كـ`SEMANTIC_UNAVAILABLE`، لا أن تُعرض على أنها semantic match.

## fallback الزمني المقصود

`RagChronologicalFallback` ليس فشلاً صامتاً ولا بديلاً semantic. هو fallback مقصود وآمن يعيد أحدث الرسائل أو السياق المسموح به بحدود الحجم عندما يكون embedding backend غير محمّل، في cold start، بعد unload بسبب ضغط الذاكرة، أو عند عدم توافق الأبعاد.

قواعده:

- الترتيب زمني ومحدود، وليس ترتيباً حسب similarity.
- لا ينشئ score semantic اصطناعياً.
- لا يرفع scope أو privacy visibility.
- لا يثبت أن المحتوى مرتبط دلالياً بسؤال المستخدم.
- يجب أن يبقى provenance في السياق واضحاً: `chronological_fallback`.

## مصدر التنفيذ

- `memory/embedding/EmbeddingService.kt`: readiness وsemantic top-k.
- `memory/rag/RagRetriever.kt`: اختيار semantic أو fallback.
- `memory/rag/RagChronologicalFallback.kt`: fallback الزمني.
- `ai/LlamaNative.kt` و`ai/LlamaManager.kt`: native embedding path وGGUF loading.

## لغة التقارير

مسموح: `semantic retrieval runtime-verified with loaded embedding GGUF/JNI`.

غير مسموح: `semantic RAG available` اعتماداً على source inspection فقط، أو وصف chronological fallback بأنه semantic result. يجب فصل `source-verified` عن `runtime-verified` في كل تقرير.
