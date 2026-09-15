package com.phx.yugcall

import android.Manifest
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.phx.yugcall.audio.AudioCapture
import com.phx.yugcall.audio.AudioDecoder
import com.phx.yugcall.audio.AudioFormatSpec
import com.phx.yugcall.audio.rmsOf
import com.phx.yugcall.detect.DetectorState
import com.phx.yugcall.detect.OnnxSpoofDetector
import com.phx.yugcall.detect.SpoofDetector
import com.phx.yugcall.detect.WindowScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainViewModel"

/** How many recent windows contribute to the live verdict (~15 s of audio). */
private const val LIVE_WINDOW_MEMORY = 15

enum class ScanSource { LIVE, FILE }

data class ScanRecord(
    val id: Long,
    val source: ScanSource,
    val timestampMillis: Long,
    val outcome: AnalysisOutcome.Scored,
)

data class UiState(
    val detectorState: DetectorState,
    val isListening: Boolean = false,
    val isAnalysing: Boolean = false,
    val liveOutcome: AnalysisOutcome? = null,
    val inputLevel: Float = 0f,
    val history: List<ScanRecord> = emptyList(),
    val message: String? = null,
    /** Median wall-clock inference time per window, once anything has been scored. */
    val medianInferenceMillis: Double? = null,
) {
    val detectorReady: Boolean get() = detectorState is DetectorState.Ready
}

class MainViewModel(
    private val capture: AudioCapture,
    private val decoder: AudioDecoder,
    private val detectorProvider: suspend () -> SpoofDetector,
    private val riskEngine: RiskEngine = RiskEngine(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState(detectorState = DetectorState.Loading))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Set once the model finishes loading; null until then and if loading failed. */
    @Volatile
    private var detector: SpoofDetector? = null

    init {
        // Off the main thread: ORT session creation is slow enough to be
        // visible as a stall during Activity startup.
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { detectorProvider() }
            detector = loaded
            _uiState.update { it.copy(detectorState = loaded.state) }
        }
    }

    private fun requireDetector(): SpoofDetector =
        detector ?: error("Detector is still loading")

    private var listenJob: Job? = null
    private var analyseJob: Job? = null
    private var nextRecordId = 0L
    private val liveScores = ArrayDeque<WindowScore>()
    private var liveSkipped = 0
    private val latenciesMillis = ArrayDeque<Double>()

    /**
     * Times one inference and folds it into the rolling median.
     *
     * Reported in the UI because a number measured on the device is the only
     * thing that settles whether the CPU path is fast enough on this hardware.
     *
     * Does not switch dispatcher: callers are responsible for being off the
     * main thread already, so a long offline run dispatches once rather than
     * once per window.
     */
    private fun timedScore(samples: FloatArray): WindowScore {
        val detector = requireDetector()
        val started = System.nanoTime()
        val score = detector.score(samples)
        recordLatency((System.nanoTime() - started) / 1_000_000.0)
        return score
    }

    private fun recordLatency(millis: Double) {
        latenciesMillis.addLast(millis)
        while (latenciesMillis.size > LATENCY_MEMORY) latenciesMillis.removeFirst()
        val sorted = latenciesMillis.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        _uiState.update { it.copy(medianInferenceMillis = median) }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleListening() {
        if (_uiState.value.isListening) stopListening() else startListening()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (listenJob?.isActive == true) return
        if (!_uiState.value.detectorReady) {
            _uiState.update { it.copy(message = detectorBlockedMessage()) }
            return
        }

        liveScores.clear()
        liveSkipped = 0
        _uiState.update {
            it.copy(isListening = true, liveOutcome = null, message = null, inputLevel = 0f)
        }

        listenJob = viewModelScope.launch {
            try {
                capture.windows().collect { window ->
                    _uiState.update { it.copy(inputLevel = window.rms) }

                    if (window.isSilent) {
                        liveSkipped++
                    } else {
                        val score = withContext(Dispatchers.Default) {
                            timedScore(window.samples)
                        }
                        liveScores.addLast(score)
                        while (liveScores.size > LIVE_WINDOW_MEMORY) liveScores.removeFirst()
                    }

                    val outcome = riskEngine.assess(liveScores.toList(), liveSkipped)
                    _uiState.update { it.copy(liveOutcome = outcome) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "live capture failed", t)
                _uiState.update {
                    it.copy(
                        isListening = false,
                        message = t.message ?: "Microphone capture failed.",
                    )
                }
            }
        }
    }

    fun stopListening() {
        if (!_uiState.value.isListening) return
        listenJob?.cancel()
        listenJob = null

        val outcome = riskEngine.assess(liveScores.toList(), liveSkipped)
        liveScores.clear()
        liveSkipped = 0
        _uiState.update { state ->
            state.copy(
                isListening = false,
                inputLevel = 0f,
                liveOutcome = outcome,
                history = state.history.prepend(outcome, ScanSource.LIVE),
            )
        }
    }

    fun analyseFile(uri: Uri?) {
        if (uri == null) return
        if (analyseJob?.isActive == true) return
        if (!_uiState.value.detectorReady) {
            _uiState.update { it.copy(message = detectorBlockedMessage()) }
            return
        }

        _uiState.update { it.copy(isAnalysing = true, message = null, liveOutcome = null) }

        analyseJob = viewModelScope.launch {
            val outcome = try {
                val samples = decoder.decodeToMono16k(uri)
                withContext(Dispatchers.Default) { scoreOffline(samples) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "file analysis failed", t)
                AnalysisOutcome.Failed(t.message ?: "Could not decode the selected file.")
            }

            _uiState.update { state ->
                state.copy(
                    isAnalysing = false,
                    liveOutcome = outcome,
                    history = state.history.prepend(outcome, ScanSource.FILE),
                    message = (outcome as? AnalysisOutcome.Failed)?.reason,
                )
            }
        }
    }

    /**
     * Scores a decoded file with non-overlapping windows. Live capture overlaps
     * windows for responsiveness; offline there is no latency to hide, so
     * disjoint windows give independent evidence and cost less.
     */
    private suspend fun scoreOffline(samples: FloatArray): AnalysisOutcome {
        val size = AudioFormatSpec.WINDOW_SAMPLES
        if (samples.isEmpty()) return AnalysisOutcome.Failed("The file contained no audio.")

        val scores = mutableListOf<WindowScore>()
        var skipped = 0
        var offset = 0
        while (offset < samples.size) {
            // A long file is many seconds of inference; without this, cancelling
            // the screen leaves the CPU grinding through the whole recording.
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + size, samples.size)
            val slice = samples.copyOfRange(offset, end)
            // A trailing fragment shorter than a third of a window is mostly
            // padding once tiled, so it contributes more artefact than signal.
            if (slice.size >= size / 3) {
                if (rmsOf(slice) < AudioFormatSpec.SILENCE_RMS_THRESHOLD) {
                    skipped++
                } else {
                    scores += timedScore(slice)
                }
            }
            offset = end
        }
        return riskEngine.assess(scores, skipped)
    }

    private fun detectorBlockedMessage(): String = when (val s = _uiState.value.detectorState) {
        is DetectorState.Loading -> "Still loading the model — try again in a moment."
        is DetectorState.Unavailable -> "Cannot analyse audio: ${s.reason}"
        is DetectorState.Ready -> "Detector is ready but the request was rejected."
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun List<ScanRecord>.prepend(
        outcome: AnalysisOutcome,
        source: ScanSource,
    ): List<ScanRecord> {
        val scored = outcome as? AnalysisOutcome.Scored ?: return this
        val record = ScanRecord(
            id = nextRecordId++,
            source = source,
            timestampMillis = System.currentTimeMillis(),
            outcome = scored,
        )
        return (listOf(record) + this).take(MAX_HISTORY)
    }

    override fun onCleared() {
        listenJob?.cancel()
        analyseJob?.cancel()
        detector?.close()
    }

    companion object {
        private const val MAX_HISTORY = 50
        private const val LATENCY_MEMORY = 30

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T {
                    val app = context.applicationContext
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(
                        capture = AudioCapture(app),
                        decoder = AudioDecoder(app),
                        detectorProvider = { OnnxSpoofDetector.fromAsset(app) },
                    ) as T
                }
            }
    }
}
