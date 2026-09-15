package com.phx.yugcall.overlay

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Reports whether a cellular or VoIP call is currently active, by polling
 * [AudioManager.getMode].
 *
 * This exists so the app can be honest instead of silently scoring silence:
 * Android's audio HAL silences every third-party `AudioRecord` the instant
 * `MODE_IN_CALL` or `MODE_IN_COMMUNICATION` is entered, for any audio source,
 * and no permission lifts that. There is no change callback for audio mode on
 * the API levels this app supports, so this polls cheaply instead of using
 * `READ_PHONE_STATE`, which this app does not need for anything else.
 */
class CallStateMonitor(context: Context) {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun isInCallOrCommunication(): Boolean = when (audioManager?.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }

    /** Emits the current value immediately, then re-checks every [pollMs]. */
    fun flow(pollMs: Long = 750): Flow<Boolean> = flow {
        while (currentCoroutineContext().isActive) {
            emit(isInCallOrCommunication())
            delay(pollMs)
        }
    }.distinctUntilChanged()
}
