package com.phx.yugcall.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FitToLengthTest {

    @Test
    fun `returns input unchanged at the exact length`() {
        val input = FloatArray(8) { it.toFloat() }
        assertTrue(input === fitToLength(input, 8))
    }

    @Test
    fun `truncates longer input`() {
        val out = fitToLength(FloatArray(10) { it.toFloat() }, 4)
        assertEquals(4, out.size)
        assertEquals(3f, out.last(), 0f)
    }

    @Test
    fun `tiles shorter input rather than zero-padding`() {
        // Matches pad() in aasist/data_utils.py: repeat, do not zero-fill.
        val out = fitToLength(floatArrayOf(1f, 2f, 3f), 8)
        assertEquals(8, out.size)
        assertEquals(listOf(1f, 2f, 3f, 1f, 2f, 3f, 1f, 2f), out.toList())
    }

    @Test
    fun `rejects an empty window`() {
        assertThrows(IllegalArgumentException::class.java) {
            fitToLength(FloatArray(0), 4)
        }
    }
}

class WindowScoreTest {

    @Test
    fun `cm score is the bonafide margin`() {
        assertEquals(2f, WindowScore(spoofLogit = -1f, bonafideLogit = 1f).cmScore, 1e-6f)
    }

    @Test
    fun `softmax is symmetric around equal logits`() {
        assertEquals(0.5f, WindowScore(1f, 1f).spoofSoftmax, 1e-6f)
    }

    @Test
    fun `softmax stays finite for extreme logits`() {
        // Naive exp() overflows here; the max-shift keeps it in range.
        val hot = WindowScore(spoofLogit = 800f, bonafideLogit = -800f)
        assertTrue(hot.spoofSoftmax.isFinite())
        assertEquals(1f, hot.spoofSoftmax, 1e-6f)

        val cold = WindowScore(spoofLogit = -800f, bonafideLogit = 800f)
        assertTrue(cold.spoofSoftmax.isFinite())
        assertEquals(0f, cold.spoofSoftmax, 1e-6f)
    }

    @Test
    fun `softmax increases with the spoof logit`() {
        val low = WindowScore(spoofLogit = 0f, bonafideLogit = 2f).spoofSoftmax
        val high = WindowScore(spoofLogit = 2f, bonafideLogit = 0f).spoofSoftmax
        assertTrue(abs(high - low) > 0.5f)
        assertTrue(high > low)
    }
}

class UnavailableDetectorTest {

    @Test
    fun `reports why it is unavailable`() {
        val detector = UnavailableDetector("model missing")
        assertEquals(DetectorState.Unavailable("model missing"), detector.state)
    }

    @Test
    fun `refuses to score rather than inventing a value`() {
        val detector = UnavailableDetector("model missing")
        assertThrows(IllegalStateException::class.java) { detector.score(FloatArray(10)) }
    }
}
