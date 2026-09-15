package com.phx.yugcall.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phx.yugcall.AnalysisOutcome
import com.phx.yugcall.detect.DetectorState
import com.phx.yugcall.detect.LiveState
import com.phx.yugcall.ui.components.InCallBanner
import com.phx.yugcall.ui.components.RiskGauge
import com.phx.yugcall.ui.theme.neutralRiskColors
import com.phx.yugcall.ui.theme.riskColors

/**
 * The floating call-protection indicator: a small draggable circle when
 * collapsed, expanding on tap into a compact status panel. Both states share
 * the same drag handler so the whole thing can be repositioned anywhere on
 * screen, over any app.
 */
@Composable
fun OverlayBubble(
    expanded: Boolean,
    liveState: LiveState,
    inCall: Boolean,
    onToggleExpanded: () -> Unit,
    onStartStop: () -> Unit,
    onDismiss: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    if (expanded) {
        ExpandedPanel(liveState, inCall, onToggleExpanded, onStartStop, onDismiss, onDrag)
    } else {
        CollapsedBubble(liveState, onToggleExpanded, onDrag)
    }
}

@Composable
private fun CollapsedBubble(
    liveState: LiveState,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val outcome = liveState.outcome as? AnalysisOutcome.Scored
    val colors = when {
        !liveState.isListening -> neutralRiskColors()
        outcome != null -> riskColors(outcome.riskLevel)
        else -> neutralRiskColors()
    }

    Surface(
        shape = CircleShape,
        color = colors.container,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(2.dp, colors.accent),
        modifier = Modifier
            .size(60.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            }
            .pointerInput(Unit) {
                detectTapGestures { onTap() }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (liveState.isListening) Icons.Default.Mic else Icons.Default.Shield,
                contentDescription = "VoxShield call protection",
                tint = colors.accent,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun ExpandedPanel(
    liveState: LiveState,
    inCall: Boolean,
    onCollapse: () -> Unit,
    onStartStop: () -> Unit,
    onDismiss: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val outcome = liveState.outcome as? AnalysisOutcome.Scored
    val colors = when {
        outcome != null && liveState.isListening -> riskColors(outcome.riskLevel)
        else -> neutralRiskColors()
    }

    Card(
        modifier = Modifier
            .width(240.dp)
            .shadow(12.dp, RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("VoxShield", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onCollapse, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Collapse", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (inCall) {
                InCallBanner(compact = true)
                Spacer(Modifier.height(12.dp))
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    liveState.detectorState is DetectorState.Loading -> RiskGauge(
                        fraction = null,
                        trackColor = colors.track,
                        progressColor = colors.accent,
                        label = "loading model",
                        centerText = "…",
                        size = 120.dp,
                    )
                    outcome != null -> RiskGauge(
                        fraction = if (outcome.isCalibrated) outcome.spoofProbability else null,
                        trackColor = colors.track,
                        progressColor = colors.accent,
                        label = if (outcome.isCalibrated) "spoof score" else "risk: ${outcome.riskLevel.name}",
                        size = 120.dp,
                    )
                    else -> RiskGauge(
                        fraction = null,
                        trackColor = colors.track,
                        progressColor = colors.accent,
                        label = if (liveState.isListening) "listening…" else "idle",
                        size = 120.dp,
                    )
                }
            }

            liveState.medianInferenceMillis?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${it.toInt()} ms/window",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onStartStop,
                enabled = liveState.detectorReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = if (liveState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (liveState.isListening) "Stop" else "Check this audio")
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Turn off overlay")
            }
        }
    }
}
