package com.airi.assistant.terminal

/** Conservative limits shared by the isolated service and its client. */
data class IsolatedProcessLimits(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val cpuTimeMs: Long = DEFAULT_CPU_TIME_MS,
    val memoryBytes: Long = DEFAULT_MEMORY_BYTES,
    val maxOutputBytes: Long = DEFAULT_OUTPUT_BYTES,
    val maxCommandChars: Int = DEFAULT_COMMAND_CHARS,
) {
    init {
        require(timeoutMs in 1L..MAX_TIMEOUT_MS)
        require(cpuTimeMs in 1L..MAX_CPU_TIME_MS)
        require(memoryBytes in 1L..MAX_MEMORY_BYTES)
        require(maxOutputBytes in 1L..MAX_OUTPUT_BYTES)
        require(maxCommandChars in 1..MAX_COMMAND_CHARS)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val DEFAULT_CPU_TIME_MS = 15_000L
        const val MAX_CPU_TIME_MS = 60_000L
        const val DEFAULT_MEMORY_BYTES = 128L * 1024L * 1024L
        const val MAX_MEMORY_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_OUTPUT_BYTES = 256L * 1024L
        const val MAX_OUTPUT_BYTES = 512L * 1024L
        const val DEFAULT_COMMAND_CHARS = 8_192
        const val MAX_COMMAND_CHARS = 8_192
    }
}
