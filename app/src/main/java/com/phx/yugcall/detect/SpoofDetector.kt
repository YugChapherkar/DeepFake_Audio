package com.phx.yugcall.detect

import kotlin.math.exp

/**
 * Whether a usable countermeasure model is loaded.
 *
 * [Unavailable] is a first-class state on purpose: when no model is present the
 * app reports that it cannot assess the audio. It never substitutes a synthetic
 * or placeholder score, because a fabricated risk number is worse than no number
 * in a product whose entire output is a trust judgement.
 */
sealed interface DetectorState {
    /**
     * The model is being read and compiled off the main thread. Reading a
     * ~1.2 MB asset and building an ORT session with full graph optimisation
     * takes long enough to jank Activity startup if done inline.
     */
    data object Loading : DetectorState

    /**
     * @param backend the provider that actually loaded, which may differ from
     *   the one requested if it was unavailable on this device.
     */
    data class Ready(
        val modelName: String,
        val inputSamples: Int,
        val backend: ExecutionBackend,
        val threads: Int,
    ) : DetectorState

    data class Unavailable(val reason: String) : DetectorState
}

/**
 * Raw model output for a single window.
 *
 * Class indices follow the AASIST convention (aasist/data_utils.py assigns
 * `1` to bona fide, `0` to spoof).
 */
data class WindowScore(
    val spoofLogit: Float,
    val bonafideLogit: Float,
) {
    /**
     * The countermeasure score used throughout the ASVspoof literature —
     * higher means *more genuine*. This is the quantity EER is computed over
     * and the one to feed into calibration.
     */
    val cmScore: Float get() = bonafideLogit - spoofLogit

    /**
     * Softmax over the two logits, in [0, 1], higher means more synthetic.
     *
     * This is NOT a probability that the speaker is synthetic. A softmax over
     * a cross-entropy-trained head is systematically overconfident and is not
     * calibrated to any real-world prior. Present it to a user only after
     * running it through a fitted calibration — see `RiskEngine`.
     */
    val spoofSoftmax: Float
        get() {
            // Shift by the max for numerical stability.
            val m = maxOf(spoofLogit, bonafideLogit)
            val a = exp((spoofLogit - m).toDouble())
            val b = exp((bonafideLogit - m).toDouble())
            return (a / (a + b)).toFloat()
        }
}

/** Scores fixed-length 16 kHz mono windows for synthetic-speech artefacts. */
interface SpoofDetector : AutoCloseable {
    val state: DetectorState

    /**
     * Scores one window. Input shorter than the model's expected length is
     * tiled to fit and longer input is truncated, matching the reference
     * implementation's evaluation-time padding.
     *
     * @throws IllegalStateException if [state] is not [DetectorState.Ready].
     */
    fun score(samples: FloatArray): WindowScore
}

/** Stand-in used when no model asset is bundled. Every call fails loudly. */
class UnavailableDetector(private val reason: String) : SpoofDetector {
    override val state: DetectorState = DetectorState.Unavailable(reason)
    override fun score(samples: FloatArray): WindowScore =
        throw IllegalStateException("No detector model loaded: $reason")
    override fun close() = Unit
}

/**
 * Tiles or truncates [samples] to exactly [length].
 *
 * Mirrors `pad()` in aasist/data_utils.py: short input is repeated rather than
 * zero-filled, because trailing silence shifts the score distribution the model
 * was fitted on.
 */
fun fitToLength(samples: FloatArray, length: Int): FloatArray {
    if (samples.size == length) return samples
    if (samples.size > length) return samples.copyOf(length)
    require(samples.isNotEmpty()) { "cannot pad an empty window" }
    val out = FloatArray(length)
    var written = 0
    while (written < length) {
        val n = minOf(samples.size, length - written)
        System.arraycopy(samples, 0, out, written, n)
        written += n
    }
    return out
}
