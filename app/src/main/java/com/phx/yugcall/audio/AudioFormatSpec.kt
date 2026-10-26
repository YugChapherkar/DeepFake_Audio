package com.phx.yugcall.audio

/**
 * Audio contract shared by capture, file decoding and the detector.
 *
 * These values are dictated by the model, not by preference: AASIST-style
 * countermeasures are trained on 16 kHz mono waveforms of a fixed length, and
 * feeding them anything else silently degrades the score rather than failing.
 */
object AudioFormatSpec {
    const val SAMPLE_RATE = 16_000

    /**
     * 64,600 samples (~4.04 s) is the AASIST training crop length. The model
     * accepts other lengths, but scores are only comparable at the length it
     * was trained on, so we always pad/trim to exactly this.
     */
    const val WINDOW_SAMPLES = 64_600

    /** Emit a fresh score once per second; consecutive windows overlap heavily. */
    const val HOP_SAMPLES = SAMPLE_RATE

    /**
     * Windows quieter than this (RMS, full-scale) are treated as silence and
     * skipped. Scoring near-silence produces confident nonsense in both
     * directions, which is worse than reporting nothing.
     */
    const val SILENCE_RMS_THRESHOLD = 0.003f

    val WINDOW_DURATION_SECONDS: Float = WINDOW_SAMPLES.toFloat() / SAMPLE_RATE
}

/**
 * One fixed-length analysis window.
 *
 * @param samples exactly [AudioFormatSpec.WINDOW_SAMPLES] samples in [-1, 1].
 * @param rms root-mean-square level, used for the input meter and silence gating.
 */
data class AudioWindow(
    val samples: FloatArray,
    val rms: Float,
    /**
     * True when Android itself is feeding this app silence instead of the
     * microphone — `AudioRecordingConfiguration.isClientSilenced()`. During a
     * cellular call this is true for every third-party app, speakerphone or
     * not, so a silent window here means "the OS is blocking us", not "nobody
     * is talking".
     */
    val silencedBySystem: Boolean = false,
) {
    val isSilent: Boolean get() = rms < AudioFormatSpec.SILENCE_RMS_THRESHOLD

    // FloatArray uses identity equals/hashCode, so the generated data class
    // implementations would be wrong in a misleading way. Compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioWindow) return false
        return rms == other.rms && silencedBySystem == other.silencedBySystem &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int =
        (31 * samples.contentHashCode() + rms.hashCode()) * 31 + silencedBySystem.hashCode()
}

/** Root-mean-square of [n] samples starting at 0. */
fun rmsOf(samples: FloatArray, n: Int = samples.size): Float {
    if (n <= 0) return 0f
    var sum = 0.0
    for (i in 0 until n) {
        val s = samples[i]
        sum += s.toDouble() * s
    }
    return kotlin.math.sqrt(sum / n).toFloat()
}
