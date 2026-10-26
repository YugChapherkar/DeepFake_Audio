package com.phx.yugcall

import com.phx.yugcall.detect.WindowScore
import kotlin.math.exp
import kotlin.math.roundToInt

enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * Platt scaling: `p(bonafide) = sigmoid(slope * cmScore + intercept)`.
 *
 * A softmax over a cross-entropy head is not a probability of anything in the
 * world — it is systematically overconfident and carries no information about
 * the base rate of synthetic callers. Showing a user "85% synthetic" from an
 * uncalibrated head is a fabricated number.
 *
 * [UNFITTED] is the identity transform, which reproduces the raw softmax
 * exactly. It is marked [fitted] = false so the UI can refuse to present it as
 * a probability. Replace it with coefficients fitted by logistic regression on
 * a held-out validation split before this is shown to anyone as a percentage.
 */
data class Calibration(
    val slope: Float,
    val intercept: Float,
    val fitted: Boolean,
) {
    fun spoofProbability(cmScore: Float): Float {
        val bonafideLogOdds = slope * cmScore + intercept
        return sigmoid(-bonafideLogOdds)
    }

    companion object {
        val UNFITTED = Calibration(slope = 1f, intercept = 0f, fitted = false)

        /**
         * Fitted for the bundled `aasist_l.onnx` (fine-tuned AASIST-L, run of
         * 2026-09-30) by logistic regression on its pooled dev split: ASVspoof
         * 2019 LA dev (4,000 clips) plus held-out In-the-Wild dev speakers
         * (3,605 clips). See docs/EXPERIMENTS.md.
         *
         * Tied to that exact model file. Replacing the asset without refitting
         * makes these numbers wrong — switch back to [UNFITTED] instead.
         *
         * The probability it yields reflects the dev split's class balance
         * (about a third bona fide), not how often real callers are synthetic.
         */
        val BUNDLED_MODEL = Calibration(slope = 1.6442840f, intercept = 0.3187831f, fitted = true)
    }
}

/**
 * Decision boundaries on the calibrated spoof probability.
 *
 * [PLACEHOLDER] preserves the values this project started with. They were
 * picked by hand, not derived from data. Real thresholds come from sweeping the
 * validation set for a target false-positive rate — a false HIGH tells someone
 * their family member is an impersonator, so the tolerable FPR is very low.
 */
data class RiskThresholds(
    val mediumAbove: Float,
    val highAbove: Float,
    val provenance: String,
) {
    init {
        require(mediumAbove < highAbove) { "mediumAbove must be below highAbove" }
    }

    fun levelFor(spoofProbability: Float): RiskLevel = when {
        spoofProbability >= highAbove -> RiskLevel.HIGH
        spoofProbability >= mediumAbove -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    companion object {
        val PLACEHOLDER = RiskThresholds(
            mediumAbove = 0.4f,
            highAbove = 0.7f,
            provenance = "hand-picked placeholder; not fitted to any evaluation set",
        )
    }
}

/** What the analysis concluded, including the cases where it concluded nothing. */
sealed interface AnalysisOutcome {
    /**
     * @param spoofProbability aggregated across windows; only meaningful as a
     *   percentage when [isCalibrated] is true.
     * @param windowsScored how much evidence backs the verdict. One window is
     *   ~4 s of audio and is thin grounds for any claim.
     */
    data class Scored(
        val spoofProbability: Float,
        val medianSpoofProbability: Float,
        val riskLevel: RiskLevel,
        val windowsScored: Int,
        val windowsSkipped: Int,
        val isCalibrated: Boolean,
    ) : AnalysisOutcome {
        val percentLabel: String
            get() = if (isCalibrated) "${(spoofProbability * 100).roundToInt()}%" else "—"
    }

    /** Audio was present but every window was below the silence gate. */
    data class NoSpeech(val windowsSkipped: Int) : AnalysisOutcome

    data class Failed(val reason: String) : AnalysisOutcome
}

/**
 * Turns per-window model scores into a single verdict.
 *
 * Aggregation uses the 90th percentile rather than the mean or the max. The
 * mean lets a few seconds of synthetic audio hide inside a long genuine call;
 * the max makes a single noisy window escalate the whole call. The 90th
 * percentile needs a sustained run of suspicious windows to move.
 */
class RiskEngine(
    private val calibration: Calibration = Calibration.UNFITTED,
    private val thresholds: RiskThresholds = RiskThresholds.PLACEHOLDER,
) {
    fun assess(scores: List<WindowScore>, windowsSkipped: Int = 0): AnalysisOutcome {
        if (scores.isEmpty()) return AnalysisOutcome.NoSpeech(windowsSkipped)

        val probabilities = scores
            .map { calibration.spoofProbability(it.cmScore) }
            .sorted()

        val aggregate = percentile(probabilities, 0.90f)
        return AnalysisOutcome.Scored(
            spoofProbability = aggregate,
            medianSpoofProbability = percentile(probabilities, 0.50f),
            riskLevel = thresholds.levelFor(aggregate),
            windowsScored = scores.size,
            windowsSkipped = windowsSkipped,
            isCalibrated = calibration.fitted,
        )
    }
}

/** Linear-interpolated percentile of an already-sorted list. */
internal fun percentile(sorted: List<Float>, q: Float): Float {
    require(sorted.isNotEmpty()) { "percentile of an empty list" }
    if (sorted.size == 1) return sorted[0]
    val pos = q.coerceIn(0f, 1f) * (sorted.size - 1)
    val lower = pos.toInt()
    val upper = minOf(lower + 1, sorted.size - 1)
    val frac = pos - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * frac
}

internal fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
