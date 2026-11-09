package com.airi.assistant.ui.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelComparatorTest {
    private val white = 0xFFFFFFFF.toInt()
    private fun image(w: Int, h: Int, color: Int = white) = IntArray(w * h) { color }

    @Test fun identicalImagesMatch() {
        val r = PixelComparator.compare(image(4, 4), 4, 4, image(4, 4), 4, 4)
        assertTrue(r.matches(0.0))
        assertEquals(0, r.differingPixels)
    }

    @Test fun differenceWithinToleranceIsIgnored() {
        val near = image(4, 4, 0xFFFEFEFE.toInt())
        assertTrue(PixelComparator.compare(image(4, 4), 4, 4, near, 4, 4, channelTolerance = 2).matches(0.0))
        assertFalse(PixelComparator.compare(image(4, 4), 4, 4, near, 4, 4, channelTolerance = 0).matches(0.0))
    }

    @Test fun theDifferingShareIsCountedAndThresholded() {
        val actual = image(10, 10).also { for (i in 0 until 5) it[i] = 0xFF000000.toInt() }
        val r = PixelComparator.compare(image(10, 10), 10, 10, actual, 10, 10)
        assertEquals(5, r.differingPixels)
        assertEquals(0.05, r.ratio, 1e-9)
        assertTrue(r.matches(0.05))
        assertFalse(r.matches(0.04))
    }

    @Test fun differentSizesNeverMatch() {
        val r = PixelComparator.compare(image(4, 4), 4, 4, image(4, 5), 4, 5)
        assertFalse(r.sameSize)
        assertFalse(r.matches(1.0))
    }

    @Test fun theDiffImageMarksOnlyDifferingPixels() {
        val actual = image(2, 1).also { it[1] = 0xFF000000.toInt() }
        val diff = PixelComparator.diffImage(image(2, 1), actual)
        assertEquals(0xFFFF0000.toInt(), diff[1])
        assertTrue(diff[0] != 0xFFFF0000.toInt())
    }

    @Test fun malformedArraysAreRejected() {
        val bad = runCatching { PixelComparator.compare(IntArray(3), 2, 2, IntArray(4), 2, 2) }
        assertTrue(bad.isFailure)
    }
}
