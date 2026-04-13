package com.airi.assistant.ai

data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
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
            quantization = "Q4_K_M",
            sizeBytes = 1_900L * 1024 * 1024,
            ramRequiredMb = 3072,
            contextSize = 32768,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true",
            fileName = "qwen2.5-3b-q4_k_m.gguf"
        )
    )
}
