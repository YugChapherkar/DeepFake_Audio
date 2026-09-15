package com.phx.yugcall

import android.Manifest
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phx.yugcall.audio.AudioDecoder
import com.phx.yugcall.audio.AudioFormatSpec
import com.phx.yugcall.audio.rmsOf
import com.phx.yugcall.detect.DetectorState
import com.phx.yugcall.detect.LiveState
import com.phx.yugcall.detect.SpoofDetector
import com.phx.yugcall.detect.WindowScore
import com.phx.yugcall.history.ScanRecord
import com.phx.yugcall.history.ScanSource
import com.phx.yugcall.overlay.CallStateMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainViewModel"

/**
 * Everything the in-app screen renders: the shared live session's state, plus
 * the file-analysis path (which is UI-only — the overlay has no use for it),
 * shared history, and whether a cellular/VoIP call is currently active.
 */
data class ScreenState(
    val live: LiveState = LiveState(),
    val isAnalysing: Boolean = false,
    val fileOutcome: AnalysisOutcome? = null,
    val history: List<ScanRecord> = emptyList(),
    val message: String? = null,
    val inCall: Boolean = false,
) {
    /** File analysis, when present, takes over the hero card from a live run. */
    val displayedOutcome: AnalysisOutcome? get() = fileOutcome ?: live.outcome
    val detectorReady: Boolean get() = live.detectorReady
}

/**
 * Thin coordinator over the app-wide `LiveListeningSession` (shared with the
 * call-protection overlay — see [VoxShieldApplication]) plus the in-app-only
 * file-upload path.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val voxApp get() = getApplication<VoxShieldApplication>()
    private val decoder = AudioDecoder(application)
    private val riskEngine = RiskEngine()
    private val callStateMonitor = CallStateMonitor(application)

    private var analyseJob: Job? = null
    private val _isAnalysing = MutableStateFlow(false)
    private val _fileOutcome = MutableStateFlow<AnalysisOutcome?>(null)
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ScreenState> = combine(
        voxApp.liveSession.state,
        voxApp.scanHistory.records,
        combine(_isAnalysing, _fileOutcome, _message) { analysing, outcome, msg -> Triple(analysing, outcome, msg) },
        callStateMonitor.flow(),
    ) { live, history, (analysing, fileOutcome, message), inCall ->
        ScreenState(
            live = live,
            history = history,
            isAnalysing = analysing,
            fileOutcome = fileOutcome,
            message = message,
            inCall = inCall,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenState())

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleListening() {
        if (uiState.value.live.isListening) {
            voxApp.liveSession.stop(ScanSource.LIVE)
        } else {
            voxApp.liveSession.start()
        }
    }

    fun analyseFile(uri: Uri?) {
        if (uri == null) return
        if (analyseJob?.isActive == true) return
        if (!uiState.value.detectorReady) {
            _message.value = detectorBlockedMessage()
            return
        }

        _isAnalysing.value = true
        _fileOutcome.value = null
        _message.value = null

        analyseJob = viewModelScope.launch {
            val outcome = try {
                val detector = voxApp.detector()
                val samples = decoder.decodeToMono16k(uri)
                withContext(Dispatchers.Default) { scoreOffline(samples, detector) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "file analysis failed", t)
                AnalysisOutcome.Failed(t.message ?: "Could not decode the selected file.")
            }

            _isAnalysing.value = false
            _fileOutcome.value = outcome
            voxApp.scanHistory.add(outcome, ScanSource.FILE)
            (outcome as? AnalysisOutcome.Failed)?.let { _message.value = it.reason }
        }
    }

    /**
     * Scores a decoded file with non-overlapping windows. Live capture overlaps
     * windows for responsiveness; offline there is no latency to hide, so
     * disjoint windows give independent evidence and cost less.
     */
    private suspend fun scoreOffline(samples: FloatArray, detector: SpoofDetector): AnalysisOutcome {
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
                    scores += detector.score(slice)
                }
            }
            offset = end
        }
        return riskEngine.assess(scores, skipped)
    }

    private fun detectorBlockedMessage(): String = when (val s = uiState.value.live.detectorState) {
        is DetectorState.Loading -> "Still loading the model — try again in a moment."
        is DetectorState.Unavailable -> "Cannot analyse audio: ${s.reason}"
        is DetectorState.Ready -> "Detector is ready but the request was rejected."
    }

    fun dismissMessage() {
        _message.value = null
    }

    override fun onCleared() {
        analyseJob?.cancel()
        // The live session and the model belong to VoxShieldApplication, not
        // this ViewModel — they must keep running if the overlay is using them
        // after this screen is gone, so nothing here closes them.
    }
}
