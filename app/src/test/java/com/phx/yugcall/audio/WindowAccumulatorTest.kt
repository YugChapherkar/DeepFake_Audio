package com.phx.yugcall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowAccumulatorTest {

    /** Drains a chunk the way the capture loop does, collecting every emission. */
    private fun WindowAccumulator.feed(chunk: FloatArray): List<AudioWindow> {
        val out = mutableListOf<AudioWindow>()
        var offset = 0
        while (offset < chunk.size) {
            offset += push(chunk, offset, chunk.size - offset)
            while (readyToEmit()) out += snapshot()
        }
        return out
    }

    @Test
    fun `emits nothing until the window is full`() {
        val acc = WindowAccumulator(windowSize = 100, hop = 10)
        assertTrue(acc.feed(FloatArray(99)).isEmpty())
        assertFalse(acc.readyToEmit())
    }

    @Test
    fun `emits once the window fills`() {
        val acc = WindowAccumulator(windowSize = 100, hop = 10)
        val windows = acc.feed(FloatArray(100) { it.toFloat() })
        assertEquals(1, windows.size)
        assertEquals(100, windows[0].samples.size)
        assertEquals(0f, windows[0].samples.first(), 0f)
        assertEquals(99f, windows[0].samples.last(), 0f)
    }

    @Test
    fun `emits once per hop after filling`() {
        val acc = WindowAccumulator(windowSize = 100, hop = 10)
        acc.feed(FloatArray(100))
        // 35 more samples spans three whole hops.
        assertEquals(3, acc.feed(FloatArray(35)).size)
    }

    @Test
    fun `window slides so the newest sample is always last`() {
        val acc = WindowAccumulator(windowSize = 10, hop = 5)
        acc.feed(FloatArray(10) { it.toFloat() })       // 0..9
        val windows = acc.feed(FloatArray(5) { 10f + it })  // 10..14
        assertEquals(1, windows.size)
        assertArrayEquals(
            floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f),
            windows[0].samples,
        )
    }

    @Test
    fun `handles a chunk larger than the hop`() {
        val acc = WindowAccumulator(windowSize = 10, hop = 5)
        val windows = acc.feed(FloatArray(30) { it.toFloat() })
        // Fills at 10, then a window per 5 samples through sample 30.
        assertEquals(5, windows.size)
        assertEquals(29f, windows.last().samples.last(), 0f)
    }

    @Test
    fun `snapshot is a defensive copy`() {
        val acc = WindowAccumulator(windowSize = 4, hop = 2)
        val first = acc.feed(FloatArray(4) { it.toFloat() }).single()
        acc.feed(FloatArray(2) { 99f })
        assertEquals(0f, first.samples[0], 0f)
    }

    @Test
    fun `rejects a hop larger than the window`() {
        // push() shifts by at most one hop; a larger hop would underflow it.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WindowAccumulator(windowSize = 10, hop = 11)
        }
    }

    @Test
    fun `rejects a non-positive hop`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WindowAccumulator(windowSize = 10, hop = 0)
        }
    }

    @Test
    fun `accepts a hop equal to the window`() {
        val acc = WindowAccumulator(windowSize = 4, hop = 4)
        assertEquals(1, acc.feed(FloatArray(4)).size)
    }

    @Test
    fun `rms marks quiet windows as silent`() {
        val acc = WindowAccumulator(windowSize = 8, hop = 4)
        val quiet = acc.feed(FloatArray(8) { 0.0001f }).single()
        assertTrue(quiet.isSilent)

        val loudAcc = WindowAccumulator(windowSize = 8, hop = 4)
        val loud = loudAcc.feed(FloatArray(8) { 0.5f }).single()
        assertFalse(loud.isSilent)
        assertEquals(0.5f, loud.rms, 1e-6f)
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i], 0f)
    }
}
