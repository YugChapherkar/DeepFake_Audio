package com.phx.yugcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phx.yugcall.audio.AudioFormatSpec
import com.phx.yugcall.consent.ConsentStore
import com.phx.yugcall.consent.DisclaimerBullet
import com.phx.yugcall.consent.DisclaimerScreen
import com.phx.yugcall.consent.Disclaimers
import com.phx.yugcall.consent.OverlayConsentScreen
import com.phx.yugcall.detect.DetectorState
import com.phx.yugcall.history.ScanRecord
import com.phx.yugcall.overlay.CallGuardOverlayService
import com.phx.yugcall.overlay.OverlayPermission
import com.phx.yugcall.ui.components.InCallBanner
import com.phx.yugcall.ui.components.RiskGauge
import com.phx.yugcall.ui.theme.YugCallTheme
import com.phx.yugcall.ui.theme.neutralRiskColors
import com.phx.yugcall.ui.theme.riskColors
import java.text.DateFormat
import java.util.Date

private enum class AppScreen { DISCLAIMER, HOME, OVERLAY_CONSENT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YugCallTheme { VoxShieldApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoxShieldApp() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val consentStore = remember { ConsentStore(context) }
    var screen by rememberSaveable {
        mutableStateOf(if (consentStore.hasAcceptedDisclaimer) AppScreen.HOME else AppScreen.DISCLAIMER)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingMicGrantAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        val action = pendingMicGrantAction
        pendingMicGrantAction = null
        if (granted) action?.invoke()
    }

    var awaitingOverlayPermission by rememberSaveable { mutableStateOf(false) }
    val overlaySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* handled on ON_RESUME below — Settings returns via the back stack, not a result code */ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingOverlayPermission) {
                awaitingOverlayPermission = false
                if (OverlayPermission.isGranted(context)) {
                    CallGuardOverlayService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun proceedPastOverlayConsent() {
        if (!OverlayPermission.isGranted(context)) {
            awaitingOverlayPermission = true
            overlaySettingsLauncher.launch(OverlayPermission.requestIntent(context))
        } else {
            CallGuardOverlayService.start(context)
        }
    }

    // The foreground-service notification is the always-visible "the mic is
    // available" indicator the overlay consent screen promises. Since Android 13
    // it is hidden unless POST_NOTIFICATIONS is granted, so ask for it here.
    // A refusal doesn't block the feature — the service still runs — but it is
    // asked every time Call Protection is switched on, never silently skipped.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { proceedPastOverlayConsent() }

    fun proceedAfterMic() {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            proceedPastOverlayConsent()
        }
    }

    fun beginOverlaySetup() {
        if (!consentStore.hasAcceptedOverlayConsent) {
            screen = AppScreen.OVERLAY_CONSENT
            return
        }
        if (!hasMicPermission) {
            pendingMicGrantAction = { proceedAfterMic() }
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            proceedAfterMic()
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> viewModel.analyseFile(uri) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overlayRunning by CallGuardOverlayService.isRunning.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (screen) {
            AppScreen.DISCLAIMER -> DisclaimerScreen(
                onAccept = { consentStore.hasAcceptedDisclaimer = true; screen = AppScreen.HOME },
                modifier = Modifier.padding(padding),
            )

            AppScreen.OVERLAY_CONSENT -> OverlayConsentScreen(
                onAgree = {
                    consentStore.hasAcceptedOverlayConsent = true
                    screen = AppScreen.HOME
                    beginOverlaySetup()
                },
                onCancel = { screen = AppScreen.HOME },
                modifier = Modifier.padding(padding),
            )

            AppScreen.HOME -> VoxShieldHome(
                state = state,
                overlayRunning = overlayRunning,
                onToggleListen = {
                    if (!hasMicPermission) {
                        pendingMicGrantAction = { viewModel.toggleListening() }
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        viewModel.toggleListening()
                    }
                },
                onUpload = { audioPickerLauncher.launch("audio/*") },
                onToggleOverlay = {
                    if (overlayRunning) CallGuardOverlayService.stop(context) else beginOverlaySetup()
                },
                onShowAbout = { showAbout = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showAbout) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showAbout = false }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(
                    "Limitations & disclaimers",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Disclaimers.generalPoints.forEach { point ->
                    DisclaimerBullet(point)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun VoxShieldHome(
    state: ScreenState,
    overlayRunning: Boolean,
    onToggleListen: () -> Unit,
    onUpload: () -> Unit,
    onToggleOverlay: () -> Unit,
    onShowAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "VoxShield",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onShowAbout) {
                Icon(Icons.Default.Info, contentDescription = "Limitations & disclaimers")
            }
        }

        Spacer(Modifier.height(8.dp))

        (state.live.detectorState as? DetectorState.Unavailable)?.let { unavailable ->
            DetectorUnavailableCard(unavailable.reason)
            Spacer(Modifier.height(16.dp))
        }

        if (state.inCall) {
            InCallBanner()
            Spacer(Modifier.height(16.dp))
        }

        HeroCard(state)

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MainActionButton(
                text = if (state.live.isListening) "Stop" else "Listen",
                icon = Icons.Default.Mic,
                enabled = state.detectorReady && !state.isAnalysing,
                modifier = Modifier.weight(1f),
                onClick = onToggleListen,
            )
            MainActionButton(
                text = "Upload",
                icon = Icons.Default.Upload,
                enabled = state.detectorReady && !state.live.isListening && !state.isAnalysing,
                modifier = Modifier.weight(1f),
                onClick = onUpload,
            )
        }

        Spacer(Modifier.height(20.dp))

        CallProtectionCard(overlayRunning = overlayRunning, onToggle = onToggleOverlay)

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Recent Scans",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 8.dp),
        )

        if (state.history.isEmpty()) {
            Text(
                text = "Nothing analysed yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(vertical = 8.dp),
            )
        }

        // weight(1f) is load-bearing: without it this list is the last child of
        // a non-scrollable Column and collapses to zero height on short screens.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(state.history, key = { it.id }) { record -> HistoryItem(record) }
        }
    }
}

@Composable
private fun HeroCard(state: ScreenState) {
    val outcome = state.displayedOutcome as? AnalysisOutcome.Scored
    val colors = when {
        state.live.isListening || state.isAnalysing -> neutralRiskColors()
        outcome != null -> riskColors(outcome.riskLevel)
        state.detectorReady -> riskColors(RiskLevel.LOW)
        else -> riskColors(RiskLevel.HIGH)
    }

    val ready = state.live.detectorState as? DetectorState.Ready
    val latencySuffix = state.live.medianInferenceMillis?.let { " · ${it.toInt()} ms/window" }.orEmpty()

    val subtitle = when {
        state.live.detectorState is DetectorState.Loading -> "Preparing on-device inference"
        state.isAnalysing -> "Decoding and scoring audio$latencySuffix"
        state.live.isListening ->
            "${"%.1f".format(AudioFormatSpec.WINDOW_DURATION_SECONDS)} s window every second$latencySuffix"
        ready != null -> "${ready.modelName} · ${ready.backend.label} x${ready.threads}$latencySuffix"
        else -> "No model loaded"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    state.live.detectorState is DetectorState.Loading || state.isAnalysing ->
                        CircularProgressIndicator(modifier = Modifier.size(176.dp).padding(48.dp), color = colors.accent)
                    outcome != null -> RiskGauge(
                        fraction = if (outcome.isCalibrated) outcome.spoofProbability else null,
                        trackColor = colors.track,
                        progressColor = colors.accent,
                        label = if (outcome.isCalibrated) "spoof score" else "risk: ${outcome.riskLevel.name}",
                    )
                    else -> RiskGauge(
                        fraction = null,
                        trackColor = colors.track,
                        progressColor = colors.accent,
                        label = when {
                            state.live.isListening && state.live.silencedBySystem -> "mic muted by Android"
                            state.live.isListening -> "listening…"
                            else -> "idle"
                        },
                        centerText = when {
                            state.live.isListening && state.live.silencedBySystem -> "—"
                            state.detectorReady -> "Ready"
                            else -> "—"
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.content,
            )

            if (outcome != null && !outcome.isCalibrated) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Score is uncalibrated — the model's raw output is not a " +
                        "probability, so no percentage is shown. The risk band above is " +
                        "still meaningful.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.content,
                )
            }

            if (outcome?.riskLevel == RiskLevel.HIGH) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚠ Possible AI-generated voice. Verify the caller " +
                        "independently — call back on a known number.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.content,
                )
            }
        }
    }
}

@Composable
private fun CallProtectionCard(overlayRunning: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Call Protection", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (overlayRunning) "On — you'll get a notification when a call starts"
                    else "A small floating pill you can open during calls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = overlayRunning, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun DetectorUnavailableCard(reason: String) {
    val colors = riskColors(RiskLevel.HIGH)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Detector unavailable", fontWeight = FontWeight.Bold, color = colors.content)
                Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.content)
            }
        }
    }
}

@Composable
fun MainActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(88.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text(text = text)
        }
    }
}

@Composable
fun HistoryItem(record: ScanRecord) {
    val colors = riskColors(record.outcome.riskLevel)
    val time = remember(record.timestampMillis) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.timestampMillis))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, contentDescription = null, tint = colors.accent)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "${record.source.name.lowercase().replaceFirstChar { it.uppercase() }} • $time",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Risk: ${record.outcome.riskLevel.name} • " +
                        "${record.outcome.windowsScored} window(s) • ${record.outcome.percentLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accent,
                )
            }
        }
    }
}
