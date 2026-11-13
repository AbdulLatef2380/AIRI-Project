package com.airi.assistant.voice.realtime

/** Small allocation-bounded linear resampler for mono PCM-16 voice frames. */
object Pcm16Resampler {
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        if (input.isEmpty() || fromRate == toRate) return input
        val outputSize = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val output = ShortArray(outputSize)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (index in output.indices) {
            val source = index * ratio
            val left = source.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = source - left
            val value = input[left] + (input[right] - input[left]) * fraction
            output[index] = value.roundToShort()
        }
        return output
    }

    private fun Double.roundToShort(): Short =
        coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
}
