package com.phx.yugcall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpeechTrimTest {

    private val w = AudioFormatSpec.WINDOW_SAMPLES

    private fun tone(n: Int, amp: Float = 0.3f) =
        FloatArray(n) { i -> (amp * sin(2 * PI * 220 * i / 16_000.0)).toFloat() }

    /** [speechSeconds] of tone centred in an otherwise silent window. */
    private fun burst(speechSeconds: Double): FloatArray {
        val out = FloatArray(w)
        val t = tone((speechSeconds * 16_000).toInt())
        val off = (w - t.size) / 2
        System.arraycopy(t, 0, out, off, t.size)
        return out
    }

    @Test
    fun `continuous speech is returned untouched`() {
        val window = tone(w)
        assertSame(window, trimToSpeech(window))
    }

    @Test
    fun `speech filling most of the window is left alone`() {
        // 3.5 s of a 4.04 s window is past the 75% cut-off.
        val window = burst(3.5)
        assertSame(window, trimToSpeech(window))
    }

    @Test
    fun `a short utterance is trimmed and tiled to the window length`() {
        val out = trimToSpeech(burst(2.0))
        assertNotNull(out)
        assertEquals(w, out!!.size)
        // The input is ~50% silence. Tiling keeps only the 200 ms pad either side
        // of each repeat, so what's left should be well under half that.
        val quiet = out.count { abs(it) < 1e-4f }
        assertTrue("too much silence left: $quiet samples", quiet < w / 4)
    }

    @Test
    fun `speech shorter than a third of a window is not scored`() {
        // 0.5 s plus 200 ms padding either side is 0.9 s, under the 1.35 s minimum.
        assertNull(trimToSpeech(burst(0.5)))
    }

    @Test
    fun `digital silence is not scored`() {
        assertNull(trimToSpeech(FloatArray(w)))
    }
}
