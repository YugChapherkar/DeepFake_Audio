package com.phx.yugcall

import com.phx.yugcall.detect.WindowScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    @Test
    fun `unfitted calibration reproduces the raw softmax`() {
        // slope=1, intercept=0 makes Platt scaling the identity, so the result
        // must match the model's own softmax exactly. Anything else means the
        // "uncalibrated" path is silently transforming scores.
        val score = WindowScore(spoofLogit = 1.5f, bonafideLogit = -0.5f)
        assertEquals(
            score.spoofSoftmax,
            Calibration.UNFITTED.spoofProbability(score.cmScore),
            1e-6f,
        )
    }

    @Test
    fun `unfitted calibration is flagged as unfitted`() {
        assertFalse(Calibration.UNFITTED.fitted)
    }

    @Test
    fun `probability falls as the bonafide margin grows`() {
        val c = Calibration.UNFITTED
        assertTrue(c.spoofProbability(-4f) > c.spoofProbability(0f))
        assertTrue(c.spoofProbability(0f) > c.spoofProbability(4f))
        assertEquals(0.5f, c.spoofProbability(0f), 1e-6f)
    }

    @Test
    fun `a fitted slope sharpens the transition`() {
        val sharp = Calibration(slope = 4f, intercept = 0f, fitted = true)
        val flat = Calibration(slope = 0.25f, intercept = 0f, fitted = true)
        assertTrue(sharp.spoofProbability(-1f) > flat.spoofProbability(-1f))
    }
}

class RiskThresholdsTest {

    @Test
    fun `bands map as documented`() {
        val t = RiskThresholds.PLACEHOLDER
        assertEquals(RiskLevel.LOW, t.levelFor(0.39f))
        assertEquals(RiskLevel.MEDIUM, t.levelFor(0.40f))
        assertEquals(RiskLevel.MEDIUM, t.levelFor(0.69f))
        assertEquals(RiskLevel.HIGH, t.levelFor(0.70f))
        assertEquals(RiskLevel.HIGH, t.levelFor(1.0f))
    }

    @Test
    fun `rejects inverted bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            RiskThresholds(mediumAbove = 0.8f, highAbove = 0.2f, provenance = "bad")
        }
    }

    @Test
    fun `placeholder thresholds are labelled as unfitted`() {
        assertTrue(RiskThresholds.PLACEHOLDER.provenance.contains("placeholder"))
    }
}

class RiskEngineTest {

    private val engine = RiskEngine()

    /** Builds a score with the given bonafide margin. */
    private fun score(cm: Float) = WindowScore(spoofLogit = 0f, bonafideLogit = cm)

    @Test
    fun `no scores yields NoSpeech rather than a verdict`() {
        val outcome = engine.assess(emptyList(), windowsSkipped = 4)
        assertEquals(AnalysisOutcome.NoSpeech(4), outcome)
    }

    @Test
    fun `strongly genuine audio is LOW risk`() {
        val outcome = engine.assess(List(10) { score(6f) }) as AnalysisOutcome.Scored
        assertEquals(RiskLevel.LOW, outcome.riskLevel)
        assertEquals(10, outcome.windowsScored)
    }

    @Test
    fun `strongly synthetic audio is HIGH risk`() {
        val outcome = engine.assess(List(10) { score(-6f) }) as AnalysisOutcome.Scored
        assertEquals(RiskLevel.HIGH, outcome.riskLevel)
    }

    @Test
    fun `a single suspicious window does not escalate a long genuine call`() {
        // The 90th-percentile aggregate exists precisely so one outlier window
        // cannot flip the verdict the way max() would.
        val scores = List(19) { score(6f) } + score(-8f)
        val outcome = engine.assess(scores) as AnalysisOutcome.Scored
        assertEquals(RiskLevel.LOW, outcome.riskLevel)
    }

    @Test
    fun `a sustained run of suspicious windows does escalate`() {
        val scores = List(10) { score(6f) } + List(10) { score(-8f) }
        val outcome = engine.assess(scores) as AnalysisOutcome.Scored
        assertEquals(RiskLevel.HIGH, outcome.riskLevel)
    }

    @Test
    fun `uncalibrated results refuse to render a percentage`() {
        val outcome = engine.assess(List(3) { score(-6f) }) as AnalysisOutcome.Scored
        assertFalse(outcome.isCalibrated)
        assertEquals("—", outcome.percentLabel)
    }

    @Test
    fun `calibrated results render a percentage`() {
        val calibrated = RiskEngine(
            calibration = Calibration(slope = 1f, intercept = 0f, fitted = true),
        )
        val outcome = calibrated.assess(List(3) { score(0f) }) as AnalysisOutcome.Scored
        assertTrue(outcome.isCalibrated)
        assertEquals("50%", outcome.percentLabel)
    }

    @Test
    fun `skipped window count is carried through`() {
        val outcome = engine.assess(List(2) { score(0f) }, windowsSkipped = 7)
            as AnalysisOutcome.Scored
        assertEquals(7, outcome.windowsSkipped)
        assertEquals(2, outcome.windowsScored)
    }
}

class PercentileTest {

    @Test
    fun `single element`() {
        assertEquals(5f, percentile(listOf(5f), 0.9f), 0f)
    }

    @Test
    fun `endpoints`() {
        val xs = listOf(0f, 1f, 2f, 3f, 4f)
        assertEquals(0f, percentile(xs, 0f), 1e-6f)
        assertEquals(4f, percentile(xs, 1f), 1e-6f)
    }

    @Test
    fun `interpolates between neighbours`() {
        val xs = listOf(0f, 10f)
        assertEquals(5f, percentile(xs, 0.5f), 1e-6f)
        assertEquals(9f, percentile(xs, 0.9f), 1e-6f)
    }

    @Test
    fun `median of an odd list is the middle element`() {
        assertEquals(2f, percentile(listOf(0f, 1f, 2f, 3f, 4f), 0.5f), 1e-6f)
    }

    @Test
    fun `rejects an empty list`() {
        assertThrows(IllegalArgumentException::class.java) { percentile(emptyList(), 0.5f) }
    }
}
