package com.airi.assistant.ai

enum class ModelType(val label: String) {
    QWEN("Qwen"),
    GEMMA("Gemma"),
    MISTRAL("Mistral"),
    LLAMA("LLaMA");

    companion object {
        fun inferFromFileName(fileName: String): ModelType {
            val lower = fileName.lowercase()
            return when {
                "gemma" in lower -> GEMMA
                "mistral" in lower || "mixtral" in lower -> MISTRAL
                "llama" in lower || "tinyllama" in lower -> LLAMA
                else -> QWEN
            }
        }
    }
}

data class ModelInfo(
    val name: String,
    val fileName: String,
    val size: Long,
    val quantization: String,
    val path: String,
    val source: ModelSource,
    val id: String = path,
    val type: ModelType = ModelType.QWEN,
    val isLocal: Boolean = true,
    val ramRequiredMb: Int = 0,
    val contextSize: Int = 4096
)
