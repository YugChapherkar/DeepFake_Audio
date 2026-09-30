package com.phx.yugcall.detect

import android.Manifest
import androidx.annotation.RequiresPermission
import com.phx.yugcall.AnalysisOutcome
import com.phx.yugcall.Calibration
import com.phx.yugcall.RiskEngine
import com.phx.yugcall.audio.AudioCapture
import com.phx.yugcall.history.ScanHistoryStore
import com.phx.yugcall.history.ScanSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveState(
    val detectorState: DetectorState = DetectorState.Loading,
    val isListening: Boolean = false,
    val outcome: AnalysisOutcome? = null,
    val inputLevel: Float = 0f,
    val medianInferenceMillis: Double? = null,
    val error: String? = null,
    /** Android is feeding us silence — during a call, for every third-party app. */
    val silencedBySystem: Boolean = false,
) {
    val detectorReady: Boolean get() = detectorState is DetectorState.Ready
}

/**
 * Owns the microphone, the model handle, and the live scoring loop as one
 * long-lived session, shared by the in-app "Listen" screen and the
 * call-overlay service (see VoxShieldApplication).
 *
 * Sharing one instance is deliberate, not an optimisation: two independent
 * AudioRecord sessions would fight over the mic (Android hands non-silence to
 * whichever started capturing most recently and silences the other), and
 * loading the ~1.15 MB model twice would waste memory and startup time for no
 * benefit. [scope] must outlive any single Activity or Service so a session
 * started from the overlay survives the host app being backgrounded.
 */
class LiveListeningSession(
    private val scope: CoroutineScope,
    private val capture: AudioCapture,
    private val detectorProvider: suspend () -> SpoofDetector,
    private val history: ScanHistoryStore,
    private val riskEngine: RiskEngine = RiskEngine(calibration = Calibration.BUNDLED_MODEL),
) {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    @Volatile
    private var detector: SpoofDetector? = null
    private var listenJob: Job? = null
    private val scores = ArrayDeque<WindowScore>()
    private var skipped = 0
    private val latencies = ArrayDeque<Double>()

    init {
        scope.launch {
            val loaded = detectorProvider()
            detector = loaded
            _state.update { it.copy(detectorState = loaded.state) }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (listenJob?.isActive == true) return
        if (!_state.value.detectorReady) {
            _state.update { it.copy(error = "The model is still loading — try again in a moment.") }
            return
        }
        scores.clear()
        skipped = 0
        _state.update { it.copy(isListening = true, outcome = null, error = null, inputLevel = 0f) }

        listenJob = scope.launch {
            try {
                capture.windows().collect { window ->
                    _state.update {
                        it.copy(inputLevel = window.rms, silencedBySystem = window.silencedBySystem)
                    }
                    if (window.isSilent) {
                        skipped++
                    } else {
                        val score = withContext(Dispatchers.Default) { timedScore(window.samples) }
                        scores.addLast(score)
                        while (scores.size > LIVE_WINDOW_MEMORY) scores.removeFirst()
                    }
                    _state.update { it.copy(outcome = riskEngine.assess(scores.toList(), skipped)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update {
                    it.copy(isListening = false, error = t.message ?: "Microphone capture failed.")
                }
            }
        }
    }

    /** Stops capture, finalises the outcome, and files it into shared history under [source]. */
    fun stop(source: ScanSource): AnalysisOutcome? {
        if (!_state.value.isListening) return _state.value.outcome
        listenJob?.cancel()
        listenJob = null
        val outcome = riskEngine.assess(scores.toList(), skipped)
        scores.clear()
        skipped = 0
        _state.update {
            it.copy(isListening = false, inputLevel = 0f, outcome = outcome, silencedBySystem = false)
        }
        history.add(outcome, source)
        return outcome
    }

    private fun timedScore(samples: FloatArray): WindowScore {
        val d = detector ?: error("Detector is still loading")
        val started = System.nanoTime()
        val score = d.score(samples)
        val ms = (System.nanoTime() - started) / 1_000_000.0
        latencies.addLast(ms)
        while (latencies.size > LATENCY_MEMORY) latencies.removeFirst()
        val sorted = latencies.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        _state.update { it.copy(medianInferenceMillis = median) }
        return score
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val LIVE_WINDOW_MEMORY = 15
        const val LATENCY_MEMORY = 30
    }
}
