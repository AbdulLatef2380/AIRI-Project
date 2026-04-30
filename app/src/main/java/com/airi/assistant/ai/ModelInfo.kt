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

/**
 * Architectures the vendored llama.cpp build can actually load.
 * The native CMake prune currently compiles: llama, llama4, qwen, qwen2,
 * qwen3, qwen2vl, qwen3vl, gemma, gemma2, gemma3.
 *
 * Anything outside this set (e.g. phi3, mistral-as-mistral-arch) will fail
 * with "unknown model architecture: '...'" at load time. The catalog and
 * "Available to Download" UI use [SUPPORTED_ARCHS] to hide entries we
 * cannot honor, instead of letting the user download a broken file.
 */
val SUPPORTED_ARCHS: Set<String> = setOf(
    "llama", "llama4",
    "qwen", "qwen2", "qwen3", "qwen2vl", "qwen3vl",
    "gemma", "gemma2", "gemma3",
)

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
