package com.airi.assistant.ai

data class ModelInfo(
    val name: String,
    val fileName: String,
    val size: Long,
    val quantization: String,
    val path: String,
    val source: ModelSource
)
