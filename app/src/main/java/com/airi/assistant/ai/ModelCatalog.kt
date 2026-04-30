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
    val fileName: String,
    /**
     * GGUF "general.architecture" string this entry maps to. Used to
     * filter out models the native build cannot load (see [SUPPORTED_ARCHS]).
     * Defaults to "qwen2" so older entries keep working.
     */
    val architecture: String = "qwen2",
    /**
     * If false, the entry is hidden from "Available to Download". This
     * flag is purely advisory — runtime checks against [SUPPORTED_ARCHS]
     * are still authoritative — but it lets us keep historical entries
     * defined without surfacing them in the picker.
     */
    val supported: Boolean = true
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
            fileName = "qwen2.5-0.5b-q4_k_m.gguf",
            architecture = "qwen2"
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
            fileName = "qwen2.5-1.5b-q4_k_m.gguf",
            architecture = "qwen2"
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
            fileName = "qwen2.5-3b-q4_k_m.gguf",
            architecture = "qwen2"
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
            fileName = "gemma-2b-it-Q4_K_M.gguf",
            architecture = "gemma"
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
            fileName = "gemma-2b-it-Q5_K_M.gguf",
            architecture = "gemma"
        ),

        // ── Gemma 2 family (now supported via the gemma2 arch wired in CMake) ─
        CatalogEntry(
            id = "gemma-2-2b-it-q4-k-m",
            name = "Gemma 2 2B Instruct",
            description = "Gemma 2 الجديد بحجم صغير — جودة محسّنة وقالب Gemma الصحيح",
            type = ModelType.GEMMA,
            quantization = "Q4_K_M",
            sizeBytes = 1_710L * 1024 * 1024,
            ramRequiredMb = 2048,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf?download=true",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            architecture = "gemma2"
        ),
        CatalogEntry(
            id = "gemma-2-9b-it-q4-k-m",
            name = "Gemma 2 9B Instruct",
            description = "Gemma 2 الكبير — جودة عالية، يحتاج 6GB+ RAM",
            type = ModelType.GEMMA,
            quantization = "Q4_K_M",
            sizeBytes = 5_760L * 1024 * 1024,
            ramRequiredMb = 6144,
            contextSize = 8192,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf?download=true",
            fileName = "gemma-2-9b-it-Q4_K_M.gguf",
            architecture = "gemma2"
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
            fileName = "llama-3.2-1b-instruct-q4_k_m.gguf",
            architecture = "llama"
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
            fileName = "llama-3.2-3b-instruct-q4_k_m.gguf",
            architecture = "llama"
        ),

        // ── Mistral family ─────────────────────────────────────────────
        // Hidden: spec-supported architectures are gemma/gemma2/gemma3/llama/qwen/coding
        // Mistral 7B v0.3 is technically loadable (it ships as the "llama" arch),
        // but the product spec excludes the family from the picker.
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
            fileName = "mistral-7b-instruct-v0.3-q4_k_m.gguf",
            architecture = "llama",
            supported = false
        ),

        // ── Phi family (Microsoft) ─────────────────────────────────────
        // Hidden: Phi-3.x ships as the "phi3" arch, which is NOT compiled into
        // the pruned native build (would crash with "unknown model architecture").
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
            fileName = "phi-3.5-mini-instruct-q4_k_m.gguf",
            architecture = "phi3",
            supported = false
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
            fileName = "qwen2.5-coder-1.5b-q4_k_m.gguf",
            architecture = "qwen2"
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
            fileName = "qwen2.5-coder-3b-q4_k_m.gguf",
            architecture = "qwen2"
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
            fileName = "tinyllama-1.1b-chat-v1.0-q4_k_m.gguf",
            architecture = "llama"
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
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            architecture = "llama"
        )
    )

    /**
     * Entries safe to surface in the "Available to Download" picker:
     *   - declared `supported = true` by the entry, AND
     *   - architecture is in the native build's [SUPPORTED_ARCHS] set.
     *
     * Anything excluded here would either fail at load time or violates
     * the product spec for visible architectures.
     */
    val visibleEntries: List<CatalogEntry>
        get() = entries.filter { it.supported && it.architecture in SUPPORTED_ARCHS }
}
