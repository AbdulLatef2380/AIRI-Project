package com.airi.assistant.ai

data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val type: ModelType = ModelType.QWEN,
    val quantization: String,
    val sizeBytes: Long,
    val ramRequiredMb: Int,
    val contextSize: Int,
    val downloadUrl: String,
    val fileName: String
)

object ModelCatalog {
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "qwen2.5-0.5b-q4",
            name = "Qwen2.5 0.5B Instruct",
            description = "أصغر نموذج — سريع جداً ومناسب للأجهزة المحدودة",
            type = ModelType.QWEN,
            quantization = "Q4_K_M",
            sizeBytes = 397L * 1024 * 1024,
            ramRequiredMb = 1024,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
            fileName = "qwen2.5-0.5b-q4_k_m.gguf"
        ),
        CatalogEntry(
            id = "qwen2.5-1.5b-q4",
            name = "Qwen2.5 1.5B Instruct",
            description = "النموذج الافتراضي — توازن ممتاز بين الأداء والحجم",
            type = ModelType.QWEN,
            quantization = "Q4_K_M",
            sizeBytes = 934L * 1024 * 1024,
            ramRequiredMb = 2048,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
            fileName = "qwen2.5-1.5b-q4_k_m.gguf"
        ),
        CatalogEntry(
            id = "qwen2.5-3b-q4",
            name = "Qwen2.5 3B Instruct",
            description = "نموذج أكبر — إجابات أكثر دقة، يحتاج ذاكرة أعلى",
            type = ModelType.QWEN,
            quantization = "Q4_K_M",
            sizeBytes = 1_900L * 1024 * 1024,
            ramRequiredMb = 3072,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true",
            fileName = "qwen2.5-3b-q4_k_m.gguf"
        ),
        CatalogEntry(
            id = "gemma-2b-it-q4-k-m",
            name = "Gemma 2B Instruct",
            description = "Gemma صغير وسريع — مناسب للأجهزة الضعيفة مع قالب محادثة Gemma الصحيح",
            type = ModelType.GEMMA,
            quantization = "Q4_K_M",
            sizeBytes = 1_670L * 1024 * 1024,
            ramRequiredMb = 1800,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-Q4_K_M.gguf?download=true",
            fileName = "gemma-2b-it-Q4_K_M.gguf"
        ),
        CatalogEntry(
            id = "gemma-2b-it-q5-k-m",
            name = "Gemma 2B Instruct",
            description = "Gemma بدقة أعلى — جودة أفضل من Q4 ويحتاج ذاكرة إضافية",
            type = ModelType.GEMMA,
            quantization = "Q5_K_M",
            sizeBytes = 1_990L * 1024 * 1024,
            ramRequiredMb = 2300,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-Q5_K_M.gguf?download=true",
            fileName = "gemma-2b-it-Q5_K_M.gguf"
        ),

        // ── Llama family ───────────────────────────────────────────────
        CatalogEntry(
            id = "llama-3.2-1b-instruct-q4",
            name = "Llama 3.2 1B Instruct",
            description = "Meta Llama 3.2 1B — أسرع نموذج Llama، مثالي للأجهزة المتوسطة",
            type = ModelType.LLAMA,
            quantization = "Q4_K_M",
            sizeBytes = 808L * 1024 * 1024,
            ramRequiredMb = 1536,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "llama-3.2-1b-instruct-q4_k_m.gguf"
        ),
        CatalogEntry(
            id = "llama-3.2-3b-instruct-q4",
            name = "Llama 3.2 3B Instruct",
            description = "Meta Llama 3.2 3B — جودة أعلى، يحتاج ذاكرة 3GB+",
            type = ModelType.LLAMA,
            quantization = "Q4_K_M",
            sizeBytes = 2_020L * 1024 * 1024,
            ramRequiredMb = 3072,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "llama-3.2-3b-instruct-q4_k_m.gguf"
        ),

        // ── Mistral family ─────────────────────────────────────────────
        CatalogEntry(
            id = "mistral-7b-instruct-v03-q4",
            name = "Mistral 7B Instruct v0.3",
            description = "Mistral 7B — قوي جداً، يحتاج هاتف بـ 6GB RAM على الأقل",
            type = ModelType.MISTRAL,
            quantization = "Q4_K_M",
            sizeBytes = 4_370L * 1024 * 1024,
            ramRequiredMb = 5120,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf?download=true",
            fileName = "mistral-7b-instruct-v0.3-q4_k_m.gguf"
        ),

        // ── Phi family (Microsoft) ─────────────────────────────────────
        CatalogEntry(
            id = "phi-3.5-mini-instruct-q4",
            name = "Phi-3.5 Mini Instruct",
            description = "Microsoft Phi-3.5 Mini 3.8B — استدلال قوي وسريع",
            type = ModelType.LLAMA, // Phi uses LLaMA-style ChatML; mapped to LLAMA template
            quantization = "Q4_K_M",
            sizeBytes = 2_390L * 1024 * 1024,
            ramRequiredMb = 3584,
            contextSize = 4096,
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
            fileName = "phi-3.5-mini-instruct-q4_k_m.gguf"
        ),

        // ── Coding-specialised ─────────────────────────────────────────
        CatalogEntry(
            id = "qwen2.5-coder-1.5b-q4",
            name = "Qwen2.5 Coder 1.5B",
            description = "متخصص في كتابة وإصلاح الكود — Python, Kotlin, JS, C++",
            type = ModelType.QWEN,
            quantization = "Q4_K_M",
            sizeBytes = 986L * 1024 * 1024,
            ramRequiredMb = 2048,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "qwen2.5-coder-1.5b-q4_k_m.gguf"
        ),
        CatalogEntry(
            id = "qwen2.5-coder-3b-q4",
            name = "Qwen2.5 Coder 3B",
            description = "نموذج كود أكبر — جودة أفضل بكثير، يحتاج 3GB RAM",
            type = ModelType.QWEN,
            quantization = "Q4_K_M",
            sizeBytes = 1_930L * 1024 * 1024,
            ramRequiredMb = 3072,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "qwen2.5-coder-3b-q4_k_m.gguf"
        ),

        // ── Ultra-light fallback ───────────────────────────────────────
        CatalogEntry(
            id = "tinyllama-1.1b-chat-q4",
            name = "TinyLlama 1.1B Chat",
            description = "أصغر نموذج Llama — يعمل على أي هاتف تقريباً",
            type = ModelType.LLAMA,
            quantization = "Q4_K_M",
            sizeBytes = 668L * 1024 * 1024,
            ramRequiredMb = 1024,
            contextSize = 2048,
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf?download=true",
            fileName = "tinyllama-1.1b-chat-v1.0-q4_k_m.gguf"
        ),
        CatalogEntry(
            id = "smollm2-1.7b-instruct-q4",
            name = "SmolLM2 1.7B Instruct",
            description = "نموذج HuggingFace جديد، خفيف وذكي",
            type = ModelType.LLAMA,
            quantization = "Q4_K_M",
            sizeBytes = 1_060L * 1024 * 1024,
            ramRequiredMb = 1792,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf?download=true",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf"
        )
    )
}
