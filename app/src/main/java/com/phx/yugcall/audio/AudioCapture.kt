package com.phx.yugcall.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

private const val TAG = "AudioCapture"
private const val BYTES_PER_SAMPLE = 2
private const val PCM16_FULL_SCALE = 32768f

/**
 * Streams overlapping fixed-length windows from the microphone as raw PCM.
 *
 * This replaces the earlier `MediaRecorder` implementation. `MediaRecorder`
 * only hands back an encoded file once recording stops, which cannot support
 * live scoring; [AudioRecord] delivers raw samples as they arrive.
 *
 * Note on call audio: `VOICE_CALL` / `VOICE_DOWNLINK` require the privileged
 * `CAPTURE_AUDIO_OUTPUT` permission and are not available to normal apps, so on
 * a stock device the far end of a call can only be captured acoustically over
 * a second device's microphone — measured on a Pixel 6a, Android silences
 * this app's capture for the whole of a cellular call even with speakerphone
 * on (`AudioRecordingConfiguration.isClientSilenced()` reports true), so the
 * same phone cannot listen to its own call. Any model deployed here needs
 * replay/room augmentation in
 * training to survive that.
 */
class AudioCapture(private val context: Context) {

    /**
     * Cold flow of analysis windows. Collecting opens the microphone;
     * cancelling releases it.
     *
     * The flow is buffered with [BufferOverflow.DROP_OLDEST] so that slow
     * inference drops stale windows instead of stalling the reader and
     * overrunning the AudioRecord ring buffer. A dropped window costs us one
     * score; a buffer overrun corrupts every subsequent one.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun windows(): Flow<AudioWindow> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormatSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer != AudioRecord.ERROR && minBuffer != AudioRecord.ERROR_BAD_VALUE) {
            "16 kHz mono PCM capture is unsupported on this device"
        }

        // Hold at least a second so a GC pause cannot drop samples.
        val bufferBytes = maxOf(minBuffer, AudioFormatSpec.SAMPLE_RATE * BYTES_PER_SAMPLE)

        val record = AudioRecord(
            preferredAudioSource(),
            AudioFormatSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord failed to initialise — the mic may be held by another app")
        }

        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter the recording state"
            }

            val accumulator = WindowAccumulator()
            val pcm = ShortArray(AudioFormatSpec.HOP_SAMPLES / 2)
            val floats = FloatArray(pcm.size)

            while (currentCoroutineContext().isActive) {
                val read = record.read(pcm, 0, pcm.size)
                if (read < 0) error("AudioRecord.read failed with $read")
                if (read == 0) continue

                for (i in 0 until read) floats[i] = pcm[i] / PCM16_FULL_SCALE

                var offset = 0
                while (offset < read) {
                    offset += accumulator.push(floats, offset, read - offset)
                    while (accumulator.readyToEmit()) {
                        // Checked per window, not once: silencing starts and
                        // stops with the call, mid-session.
                        val silenced = record.activeRecordingConfiguration?.isClientSilenced == true
                        emit(accumulator.snapshot().copy(silencedBySystem = silenced))
                    }
                }
            }
        } finally {
            // stop() throws if the recorder never produced data; releasing still
            // has to happen, so swallow and log rather than masking the real cause.
            runCatching { record.stop() }.onFailure { Log.w(TAG, "AudioRecord.stop failed", it) }
            record.release()
        }
    }.buffer(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.IO)

    /**
     * Prefers `UNPROCESSED`, which bypasses AGC and noise suppression. Those
     * stages are tuned for intelligibility and erase exactly the spectral
     * artefacts a spoof detector keys on. Not every device implements it.
     */
    private fun preferredAudioSource(): Int {
        val supported = runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                ?.toBoolean() ?: false
        }.getOrDefault(false)

        return if (supported) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }
}

/**
 * Slides a fixed-length window over an incoming sample stream, producing a
 * snapshot every [hop] samples once the window has filled.
 *
 * Kept free of Android types so it can be unit tested on the JVM.
 */
class WindowAccumulator(
    private val windowSize: Int = AudioFormatSpec.WINDOW_SAMPLES,
    private val hop: Int = AudioFormatSpec.HOP_SAMPLES,
) {
    init {
        // push() shifts by at most one hop, so a hop larger than the window
        // would underflow the shift arithmetic.
        require(windowSize > 0) { "windowSize must be positive" }
        require(hop in 1..windowSize) { "hop must be in 1..windowSize" }
    }

    private val window = FloatArray(windowSize)
    private var filled = 0
    private var sinceEmit = 0

    /**
     * Copies up to [available] samples from [chunk] starting at [from],
     * shifting older samples out as needed.
     *
     * @return how many samples were consumed; callers must loop until the
     *   chunk is drained, checking [readyToEmit] in between.
     */
    fun push(chunk: FloatArray, from: Int, available: Int): Int {
        require(available > 0) { "available must be positive" }
        val take = minOf(available, hop)
        val overflow = filled + take - windowSize
        if (overflow > 0) {
            System.arraycopy(window, overflow, window, 0, filled - overflow)
            filled -= overflow
        }
        System.arraycopy(chunk, from, window, filled, take)
        filled += take
        sinceEmit += take
        return take
    }

    fun readyToEmit(): Boolean = filled >= windowSize && sinceEmit >= hop

    /** Returns a defensive copy; resets the hop counter. */
    fun snapshot(): AudioWindow {
        sinceEmit = 0
        val copy = window.copyOf()
        return AudioWindow(copy, rmsOf(copy))
    }
}
