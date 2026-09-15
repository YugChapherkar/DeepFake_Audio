package com.phx.yugcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phx.yugcall.audio.AudioFormatSpec
import com.phx.yugcall.detect.DetectorState
import com.phx.yugcall.ui.theme.YugCallTheme
import com.phx.yugcall.ui.theme.neutralRiskColors
import com.phx.yugcall.ui.theme.riskColors
import java.text.DateFormat
import java.util.Date
import kotlin.math.log10

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> { MainViewModel.factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YugCallTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    VoxShieldHome(
                        viewModel = viewModel,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun VoxShieldHome(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        // Start immediately on grant instead of making the user tap twice.
        if (granted) viewModel.startListening()
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> viewModel.analyseFile(uri) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "VoxShield",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        (state.detectorState as? DetectorState.Unavailable)?.let { unavailable ->
            DetectorUnavailableCard(unavailable.reason)
            Spacer(Modifier.height(16.dp))
        }

        StatusCard(state)

        Spacer(Modifier.height(16.dp))

        when (val outcome = state.liveOutcome) {
            is AnalysisOutcome.Scored -> {
                AnalysisResultCard(outcome)
                Spacer(Modifier.height(16.dp))
            }
            is AnalysisOutcome.NoSpeech -> {
                InfoCard("No speech detected — every window was below the silence threshold.")
                Spacer(Modifier.height(16.dp))
            }
            is AnalysisOutcome.Failed -> {
                InfoCard("Analysis failed: ${outcome.reason}")
                Spacer(Modifier.height(16.dp))
            }
            null -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MainActionButton(
                text = if (state.isListening) "Stop" else "Listen",
                icon = Icons.Default.Mic,
                enabled = state.detectorReady && !state.isAnalysing,
                modifier = Modifier.weight(1f),
                onClick = {
                    when {
                        state.isListening -> viewModel.stopListening()
                        hasMicPermission -> viewModel.startListening()
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
            MainActionButton(
                text = "Upload",
                icon = Icons.Default.Upload,
                enabled = state.detectorReady && !state.isListening && !state.isAnalysing,
                modifier = Modifier.weight(1f),
                onClick = { audioPickerLauncher.launch("audio/*") },
            )
        }

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

        // weight(1f) is load-bearing: without it this list is the last child of a
        // non-scrollable Column and collapses to zero height on short screens.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.history, key = { it.id }) { record -> HistoryItem(record) }
        }
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val colors = when {
        state.isListening || state.isAnalysing -> neutralRiskColors()
        state.detectorReady -> riskColors(RiskLevel.LOW)
        else -> riskColors(RiskLevel.HIGH)
    }

    val title = when {
        state.isAnalysing -> "Analysing file"
        state.isListening -> "Listening"
        state.detectorReady -> "Ready"
        else -> "Unavailable"
    }

    val subtitle = when {
        state.isAnalysing -> "Decoding and scoring audio"
        state.isListening -> "Scoring a ${"%.1f".format(AudioFormatSpec.WINDOW_DURATION_SECONDS)} s " +
            "window every second"
        state.detectorReady -> (state.detectorState as DetectorState.Ready).modelName
        else -> "No model loaded"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.content,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.content,
                    )
                }
                if (state.isAnalysing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = colors.accent,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isListening) Icons.Default.Mic else Icons.Default.Shield,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            if (state.isListening) {
                Spacer(Modifier.height(12.dp))
                InputLevelMeter(state.inputLevel, colors.accent)
            }
        }
    }
}

/** Log-scaled so ordinary speech fills a useful part of the bar. */
@Composable
private fun InputLevelMeter(rms: Float, tint: androidx.compose.ui.graphics.Color) {
    val db = if (rms <= 1e-6f) -60f else (20f * log10(rms)).coerceIn(-60f, 0f)
    val fraction = (db + 60f) / 60f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "Input level",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = tint,
        )
    }
}

@Composable
private fun DetectorUnavailableCard(reason: String) {
    val colors = riskColors(RiskLevel.HIGH)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Detector unavailable",
                    fontWeight = FontWeight.Bold,
                    color = colors.content,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.content,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    val colors = neutralRiskColors()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
        )
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
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.height(8.dp))
            Text(text = text)
        }
    }
}

@Composable
fun AnalysisResultCard(outcome: AnalysisOutcome.Scored) {
    val colors = riskColors(outcome.riskLevel)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container),
        border = BorderStroke(1.dp, colors.accent),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Analysis Result",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.content,
            )
            Spacer(Modifier.height(8.dp))

            LabelledRow("Risk level", outcome.riskLevel.name, colors.content, bold = true)
            LabelledRow("Synthetic score", outcome.percentLabel, colors.content)
            LabelledRow(
                "Evidence",
                "${outcome.windowsScored} window(s) scored" +
                    if (outcome.windowsSkipped > 0) ", ${outcome.windowsSkipped} silent" else "",
                colors.content,
            )

            if (!outcome.isCalibrated) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Score is uncalibrated — the model's raw output is not a probability, " +
                        "so no percentage is shown. Ranking between scans is still meaningful.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.content,
                )
            }

            if (outcome.riskLevel == RiskLevel.HIGH) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚠ Possible AI-generated voice. Verify the caller independently — " +
                        "call back on a known number.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.content,
                )
            }
        }
    }
}

@Composable
private fun LabelledRow(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    bold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontWeight = FontWeight.Medium, color = color)
        Text(
            text = value,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
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
