package com.phx.yugcall

import android.app.Application
import com.phx.yugcall.audio.AudioCapture
import com.phx.yugcall.detect.LiveListeningSession
import com.phx.yugcall.detect.OnnxSpoofDetector
import com.phx.yugcall.detect.SpoofDetector
import com.phx.yugcall.history.ScanHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds everything that must outlive any single Activity or Service: the
 * loaded model, the live listening session (microphone + scoring loop), and
 * scan history.
 *
 * The in-app "Listen" screen and the call-protection overlay both read and
 * drive [liveSession] rather than each owning their own capture pipeline —
 * that is what stops them from fighting over the microphone or loading the
 * detector twice. See [LiveListeningSession] for why that sharing matters.
 */
class VoxShieldApplication : Application() {

    /** Outlives any one screen: a session started from the overlay must survive
     * the host Activity being backgrounded or destroyed. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val scanHistory = ScanHistoryStore()

    private val detectorMutex = Mutex()

    @Volatile
    private var cachedDetector: SpoofDetector? = null

    /**
     * Loads the model at most once, off the calling thread, and hands every
     * caller — the live session and one-off file analysis alike — the same
     * instance rather than paying the ~1.15 MB read + ORT session cost twice.
     */
    suspend fun detector(): SpoofDetector = detectorMutex.withLock {
        cachedDetector ?: OnnxSpoofDetector.fromAsset(this).also { cachedDetector = it }
    }

    val liveSession: LiveListeningSession by lazy {
        LiveListeningSession(
            scope = applicationScope,
            capture = AudioCapture(this),
            detectorProvider = { detector() },
            history = scanHistory,
        )
    }
}
