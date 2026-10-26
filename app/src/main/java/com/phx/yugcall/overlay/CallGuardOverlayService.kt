package com.phx.yugcall.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.phx.yugcall.MainActivity
import com.phx.yugcall.R
import com.phx.yugcall.VoxShieldApplication
import com.phx.yugcall.detect.LiveState
import com.phx.yugcall.history.ScanSource
import com.phx.yugcall.ui.theme.YugCallTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground (microphone-type) service behind Call Protection.
 *
 * While the switch in the app is on, protection is *armed*: this service runs,
 * watches for calls, and posts a heads-up notification when one starts.
 * Tapping that notification — or the "Show" action on the ongoing one — opens
 * the pill (see [OverlayPill]). Dragging the pill onto the target at the bottom
 * of the screen hides it again without disarming, so the next call still
 * notifies. Only "Stop", or the switch in the app, disarms.
 *
 * It never captures audio itself — it starts, stops and observes the app-wide
 * [com.phx.yugcall.detect.LiveListeningSession] (see [VoxShieldApplication]),
 * so the pill, the in-app "Listen" screen, and shared history all agree on one
 * run instead of each owning a separate microphone session.
 */
class CallGuardOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var callStateMonitor: CallStateMonitor

    /** A Compose view attached directly to WindowManager, plus its lifecycle plumbing. */
    private class OverlayWindow(
        val view: ComposeView,
        val params: WindowManager.LayoutParams,
        val owner: OverlayLifecycleOwner,
    )

    private var pill: OverlayWindow? = null
    private var dismissTarget: OverlayWindow? = null

    // Remembered so the pill reopens where the user last left it.
    private var pillX = 0
    private var pillY = -1

    private val liveState = mutableStateOf(LiveState())
    private val inCall = mutableStateOf(false)
    private val dismissArmed = mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NotificationManager::class.java)
        callStateMonitor = CallStateMonitor(this)
        createChannels()

        val notification = buildOngoingNotification()
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE itself needs API 30;
        // minSdk is 29, so the two-arg call is the real fallback on API 29, not
        // dead code — the type is inferred from the manifest's
        // android:foregroundServiceType there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val app = application as VoxShieldApplication
        serviceScope.launch { app.liveSession.state.collect { liveState.value = it } }
        serviceScope.launch {
            callStateMonitor.flow().collect { active ->
                inCall.value = active
                if (active && pill == null) postCallDetected() else if (!active) cancelCallDetected()
            }
        }

        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            // The initial start from the app has no action: show the pill at
            // once so turning the switch on has visible feedback. A sticky
            // restart after the process was killed also lands here.
            ACTION_SHOW_PILL, null -> {
                cancelCallDetected()
                showPill()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hidePill()
        cancelCallDetected()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pill

    private fun showPill() {
        if (pill != null) return
        val params = overlayParams(touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            if (pillY < 0) {
                pillX = dp(16)
                pillY = dp(96)
            }
            x = pillX
            y = pillY
        }
        pill = addOverlay(params) {
            OverlayPill(
                liveState = liveState.value,
                inCall = inCall.value,
                onTap = ::toggleListening,
                onDragStart = ::showDismissTarget,
                onDrag = ::movePill,
                onDragEnd = ::endDrag,
            )
        }
    }

    private fun hidePill() {
        val app = application as VoxShieldApplication
        if (liveState.value.isListening) app.liveSession.stop(ScanSource.OVERLAY)
        hideDismissTarget()
        pill?.let { removeOverlay(it) }
        pill = null
    }

    private fun movePill(dx: Float, dy: Float) {
        val window = pill ?: return
        window.params.x += dx.toInt()
        window.params.y += dy.toInt()
        pillX = window.params.x
        pillY = window.params.y
        runCatching { windowManager.updateViewLayout(window.view, window.params) }
        dismissArmed.value = isOverDismissZone(window)
    }

    private fun endDrag() {
        val dismiss = dismissArmed.value
        dismissArmed.value = false
        hideDismissTarget()
        if (dismiss) {
            // Next time, reopen at the default spot rather than on top of the
            // dismiss target it was just dropped on.
            pillY = -1
            hidePill()
        }
    }

    /** Bottom fifth of the screen, around the horizontal centre — generous on purpose. */
    private fun isOverDismissZone(window: OverlayWindow): Boolean {
        val screen = screenBounds()
        val centerX = window.params.x + window.view.width / 2
        val centerY = window.params.y + window.view.height / 2
        return centerY > screen.height() * 0.80f &&
            kotlin.math.abs(centerX - screen.width() / 2) < screen.width() * 0.30f
    }

    private fun toggleListening() {
        val app = application as VoxShieldApplication
        if (liveState.value.isListening) {
            app.liveSession.stop(ScanSource.OVERLAY)
        } else {
            startListeningWithPermissionCheck(app)
        }
    }

    // ------------------------------------------------------- dismiss target

    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val params = overlayParams(touchable = false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(48)
        }
        dismissTarget = addOverlay(params) { DismissTarget(armed = dismissArmed.value) }
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { removeOverlay(it) }
        dismissTarget = null
    }

    // ---------------------------------------------------- window plumbing

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_FOCUSABLE: touches on the call/dialer UI outside our small
        // window must keep working. LAYOUT_NO_LIMITS lets the pill be dragged
        // to the very edges. The dismiss target also passes every touch
        // through — it is only a visual.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    private fun addOverlay(
        params: WindowManager.LayoutParams,
        content: @Composable () -> Unit,
    ): OverlayWindow {
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this).apply {
            owner.attachTo(this)
            setContent { YugCallTheme { content() } }
        }
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager.addView(view, params)
        return OverlayWindow(view, params, owner)
    }

    private fun removeOverlay(window: OverlayWindow) {
        runCatching { windowManager.removeView(window.view) }
        window.owner.destroy()
    }

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val m = resources.displayMetrics
            Rect(0, 0, m.widthPixels, m.heightPixels)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * [LiveListeningSession.start] is `@RequiresPermission(RECORD_AUDIO)`, and
     * the setup flow that starts this service already guarantees the
     * permission is granted before the service can ever be started (a Service
     * cannot itself show a runtime permission dialog). This documents that
     * invariant at the one call site instead of scattering `@Suppress` calls.
     *
     * The only way this check can fail is the user revoking the permission
     * from system Settings while protection is armed — handled by explaining
     * why the feature stopped rather than the pill just vanishing.
     */
    private fun startListeningWithPermissionCheck(app: VoxShieldApplication) {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            @Suppress("MissingPermission")
            app.liveSession.start()
        } else {
            Toast.makeText(
                this,
                "Microphone permission was revoked — turning off Call Protection.",
                Toast.LENGTH_LONG,
            ).show()
            stopSelf()
        }
    }

    // ------------------------------------------------------- notifications

    private fun createChannels() {
        // Channels exist since API 26; minSdk is 29, so no version guard.
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Call protection", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown for as long as call protection is on." }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, "Call detected", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Pops up when a call starts, to open the VoxShield pill." }
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, CallGuardOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildOngoingNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VoxShield call protection is on")
            .setContentText("You'll get a notification when a call starts.")
            .setContentIntent(openApp)
            .addAction(0, "Show", servicePendingIntent(ACTION_SHOW_PILL, REQUEST_SHOW))
            .addAction(0, "Stop", servicePendingIntent(ACTION_STOP, REQUEST_STOP))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun postCallDetected() {
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Call detected")
            .setContentText("Tap to open the VoxShield pill.")
            .setContentIntent(servicePendingIntent(ACTION_SHOW_PILL, REQUEST_CALL_SHOW))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(CALL_NOTIFICATION_ID, notification)
    }

    private fun cancelCallDetected() {
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val CALL_NOTIFICATION_ID = 4202
        private const val CHANNEL_ID = "call_protection"
        private const val CALL_CHANNEL_ID = "call_detected"
        private const val REQUEST_SHOW = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_CALL_SHOW = 3

        const val ACTION_STOP = "com.phx.yugcall.overlay.ACTION_STOP"
        const val ACTION_SHOW_PILL = "com.phx.yugcall.overlay.ACTION_SHOW_PILL"

        private val _isRunning = MutableStateFlow(false)

        /** Whether protection is armed — the UI's source of truth for the switch. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallGuardOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallGuardOverlayService::class.java))
        }
    }
}
