package com.airi.assistant.ai

data class ModelStats(
    val tokensPerSecond: Float,
    val memoryUsage: Long,
    val loadTimeMs: Long
)

object ModelBenchmark {
    fun estimateRam(modelSize: Long): Long {
        // RAM ≈ model_size × 1.2 (تقريب هندسي)
        return (modelSize * 1.2).toLong()
    }

    fun getFormattedRam(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return "%.2f GB".format(gb)
    }
}
