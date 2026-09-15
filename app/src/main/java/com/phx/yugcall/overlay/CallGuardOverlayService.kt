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
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
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
 * Foreground (microphone-type) service that hosts the floating call-protection
 * bubble.
 *
 * It never captures audio itself — it starts/stops and observes the app-wide
 * [com.phx.yugcall.detect.LiveListeningSession] (see [VoxShieldApplication]),
 * so the bubble, the in-app "Listen" screen, and shared history all agree on
 * one run instead of each owning a separate microphone session.
 */
class CallGuardOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var lifecycleOwner: OverlayLifecycleOwner
    private lateinit var callStateMonitor: CallStateMonitor
    private var overlayView: ComposeView? = null

    private val expanded = mutableStateOf(true)
    private val liveState = mutableStateOf(LiveState())
    private val inCall = mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        callStateMonitor = CallStateMonitor(this)

        val notification = buildNotification()
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

        addOverlayView()

        val app = application as VoxShieldApplication
        serviceScope.launch { app.liveSession.state.collect { liveState.value = it } }
        serviceScope.launch { callStateMonitor.flow().collect { inCall.value = it } }

        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as VoxShieldApplication
        if (liveState.value.isListening) app.liveSession.stop(ScanSource.OVERLAY)
        removeOverlayView()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun addOverlayView() {
        lifecycleOwner = OverlayLifecycleOwner()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: touches on the call/dialer UI outside our small
            // window must keep working. LAYOUT_NO_LIMITS lets the bubble be
            // dragged to the very edges of the screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 220
        }

        val app = application as VoxShieldApplication
        val view = ComposeView(this).apply {
            lifecycleOwner.attachTo(this)
            setContent {
                YugCallTheme {
                    OverlayBubble(
                        expanded = expanded.value,
                        liveState = liveState.value,
                        inCall = inCall.value,
                        onToggleExpanded = { expanded.value = !expanded.value },
                        onStartStop = {
                            if (liveState.value.isListening) {
                                app.liveSession.stop(ScanSource.OVERLAY)
                            } else {
                                startListeningWithPermissionCheck(app)
                            }
                        },
                        onDismiss = { stopSelf() },
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            runCatching { windowManager.updateViewLayout(this, params) }
                        },
                    )
                }
            }
        }
        overlayView = view
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager.addView(view, params)
    }

    /**
     * [LiveListeningSession.start] is `@RequiresPermission(RECORD_AUDIO)`, and
     * the setup flow that starts this service already guarantees the
     * permission is granted before the service can ever be started (a Service
     * cannot itself show a runtime permission dialog). This documents that
     * invariant at the one call site instead of scattering `@Suppress` calls.
     *
     * The only way this check can fail is the user revoking the permission
     * from system Settings while the overlay is already running — handled by
     * explaining why the feature stopped rather than the bubble just vanishing.
     */
    private fun startListeningWithPermissionCheck(app: VoxShieldApplication) {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            @Suppress("MissingPermission")
            app.liveSession.start()
        } else {
            android.widget.Toast.makeText(
                this,
                "Microphone permission was revoked — turning off Call Protection.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            stopSelf()
        }
    }

    private fun removeOverlayView() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        if (::lifecycleOwner.isInitialized) lifecycleOwner.destroy()
    }

    private fun buildNotification(): Notification {
        // Notification channels have existed since API 26; minSdk is 29, so
        // this always runs — no version guard needed.
        val channel = NotificationChannel(
            CHANNEL_ID, "Call protection", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the floating call-protection indicator is active." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CallGuardOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VoxShield call protection is active")
            .setContentText("Tap the floating shield to check the current audio.")
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "call_protection"
        const val ACTION_STOP = "com.phx.yugcall.overlay.ACTION_STOP"

        private val _isRunning = MutableStateFlow(false)

        /** Whether the overlay service is currently up — the UI's source of
         * truth for whether Call Protection shows as enabled. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallGuardOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallGuardOverlayService::class.java))
        }
    }
}
