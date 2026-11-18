package com.airi.assistant.ai

import java.io.File

/** Chooses the performant CPU cluster when Android exposes per-core frequencies. */
object CpuThreadPolicy {
    private const val MAX_THREADS = 4

    fun recommendedThreads(): Int {
        val frequencies = (0 until Runtime.getRuntime().availableProcessors())
            .mapNotNull { cpu ->
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.toLongOrNull()
            }
            .sorted()
        if (frequencies.size < 2) {
            return Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
        }
        val largestGapIndex = frequencies.zipWithNext()
            .withIndex()
            .maxByOrNull { (_, pair) -> pair.second - pair.first }
            ?.index ?: return frequencies.size.coerceIn(1, MAX_THREADS)
        val gap = frequencies[largestGapIndex + 1] - frequencies[largestGapIndex]
        // Ignore noise between otherwise uniform cores; use all available
        // workers (still capped) unless a clear big/LITTLE split is visible.
        if (gap < frequencies.last() / 10) {
            return frequencies.size.coerceIn(1, MAX_THREADS)
        }
        return (frequencies.size - largestGapIndex - 1).coerceIn(1, MAX_THREADS)
    }
}
