package com.airi.assistant.ui.run

import kotlin.math.abs

/**
 * Dependency-free image comparison for the screenshot reference check (Part 5 §7.6). Works on ARGB
 * pixel arrays so it can be unit-tested on the JVM; the device test feeds it decoded bitmaps.
 */
object PixelComparator {
    data class Result(
        val sameSize: Boolean,
        val differingPixels: Int,
        val totalPixels: Int,
    ) {
        val ratio: Double get() = if (totalPixels == 0) 0.0 else differingPixels.toDouble() / totalPixels

        /** Match when the sizes agree and at most [maxRatio] of the pixels differ beyond the tolerance. */
        fun matches(maxRatio: Double): Boolean = sameSize && ratio <= maxRatio
    }

    /**
     * @param channelTolerance largest per-channel (A, R, G, B) difference still treated as equal;
     *   absorbs anti-aliasing and compression noise between devices of one density.
     */
    fun compare(
        expected: IntArray, expectedWidth: Int, expectedHeight: Int,
        actual: IntArray, actualWidth: Int, actualHeight: Int,
        channelTolerance: Int = 2,
    ): Result {
        require(expected.size == expectedWidth * expectedHeight) { "expected pixel array does not match its size" }
        require(actual.size == actualWidth * actualHeight) { "actual pixel array does not match its size" }
        if (expectedWidth != actualWidth || expectedHeight != actualHeight) {
            return Result(sameSize = false, differingPixels = maxOf(expected.size, actual.size), totalPixels = maxOf(expected.size, actual.size))
        }
        var differing = 0
        for (i in expected.indices) if (differs(expected[i], actual[i], channelTolerance)) differing++
        return Result(sameSize = true, differingPixels = differing, totalPixels = expected.size)
    }

    /** Red where [expected] and [actual] differ, a faded copy of [actual] elsewhere; same-size inputs only. */
    fun diffImage(expected: IntArray, actual: IntArray, channelTolerance: Int = 2): IntArray =
        IntArray(actual.size) { i ->
            if (differs(expected[i], actual[i], channelTolerance)) {
                0xFFFF0000.toInt()
            } else {
                val a = actual[i]
                val r = ((a shr 16) and 0xFF) / 3 + 170
                val g = ((a shr 8) and 0xFF) / 3 + 170
                val b = (a and 0xFF) / 3 + 170
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

    private fun differs(a: Int, b: Int, tolerance: Int): Boolean =
        abs((a ushr 24) - (b ushr 24)) > tolerance ||
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > tolerance ||
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > tolerance ||
            abs((a and 0xFF) - (b and 0xFF)) > tolerance
}
