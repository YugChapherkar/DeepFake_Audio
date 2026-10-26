package com.phx.yugcall.audio

import com.phx.yugcall.detect.fitToLength
import kotlin.math.sqrt

/**
 * Prepares a live microphone window for scoring when speech fills only part
 * of it.
 *
 * Why this exists: a live window is the last 4.04 s of audio, so a 2-second
 * utterance arrives surrounded by room silence. The model never saw that shape
 * in training, where short clips were *tiled* to fill the window, and it
 * scores such windows as more genuine. Tested off-device with a 2.7 s fake
 * centred in a quiet window: 58% spoof as-is, 87% trimmed.
 *
 * Why it's conservative: trimming continuous speech and tiling the result
 * creates a seam the model reads as synthetic. In the same test, a real voice
 * went from 16% to 62%. So this only acts when there's a genuine silence gap —
 * speech covering less than [MAX_SPAN_FRACTION] of the window — and returns
 * the window unchanged otherwise.
 *
 * @return [window] itself when speech already fills it, the speech span tiled
 *   to the window length when there's silence to remove, or null when the
 *   speech is too short to score: under a third of a window (1.35 s),
 *   counting the 200 ms kept either side. That's the same minimum the
 *   file-upload path applies to a trailing fragment.
 */
fun trimToSpeech(window: FloatArray): FloatArray? {
    val frames = window.size / FRAME
    if (frames == 0) return null

    val rms = FloatArray(frames) { f ->
        var sum = 0.0
        for (i in f * FRAME until (f + 1) * FRAME) sum += window[i].toDouble() * window[i]
        sqrt(sum / FRAME).toFloat()
    }
    val peak = rms.max()
    val threshold = maxOf(AudioFormatSpec.SILENCE_RMS_THRESHOLD, peak * RELATIVE_THRESHOLD)
    val first = rms.indexOfFirst { it > threshold }
    if (first < 0) return null
    val last = rms.indexOfLast { it > threshold }

    val start = maxOf(0, first * FRAME - PAD)
    val end = minOf(window.size, (last + 1) * FRAME + PAD)
    val span = end - start

    if (span >= window.size * MAX_SPAN_FRACTION) return window
    if (span < AudioFormatSpec.WINDOW_SAMPLES / 3) return null
    return fitToLength(window.copyOfRange(start, end), window.size)
}

/** 20 ms at 16 kHz. */
private const val FRAME = 320

/** A frame counts as speech above 5% of the window's loudest frame (about −26 dB). */
private const val RELATIVE_THRESHOLD = 0.05f

/** Keep 200 ms either side of the detected speech so word edges aren't clipped. */
private const val PAD = 3_200

/** At or above this share of the window, treat the speech as continuous and leave it alone. */
private const val MAX_SPAN_FRACTION = 0.75f
