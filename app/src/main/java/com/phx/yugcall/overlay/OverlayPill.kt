package com.phx.yugcall.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phx.yugcall.AnalysisOutcome
import com.phx.yugcall.RiskLevel
import com.phx.yugcall.detect.DetectorState
import com.phx.yugcall.detect.LiveState
import com.phx.yugcall.ui.theme.RiskColors
import com.phx.yugcall.ui.theme.neutralRiskColors
import com.phx.yugcall.ui.theme.riskColors

/** What the pill says, derived from the shared session state. */
private data class PillStatus(val icon: ImageVector, val text: String, val colors: RiskColors)

@Composable
private fun pillStatus(live: LiveState, inCall: Boolean): PillStatus {
    val outcome = live.outcome
    return when {
        live.detectorState is DetectorState.Loading ->
            PillStatus(Icons.Default.Shield, "Loading…", neutralRiskColors())
        live.detectorState is DetectorState.Unavailable ->
            PillStatus(Icons.Default.Warning, "Unavailable", riskColors(RiskLevel.HIGH))
        // Measured: Android mutes every third-party app for the whole of a
        // cellular call, speakerphone included. Say so instead of "Listening…".
        live.isListening && live.silencedBySystem ->
            PillStatus(Icons.Default.MicOff, "Muted by Android", riskColors(RiskLevel.MEDIUM))
        live.isListening && outcome is AnalysisOutcome.Scored ->
            PillStatus(Icons.Default.Mic, scoredLabel(outcome), riskColors(outcome.riskLevel))
        live.isListening -> PillStatus(Icons.Default.Mic, "Listening…", neutralRiskColors())
        inCall -> PillStatus(Icons.Default.MicOff, "In call · mic muted", riskColors(RiskLevel.MEDIUM))
        outcome is AnalysisOutcome.Scored ->
            PillStatus(Icons.Default.Shield, scoredLabel(outcome), riskColors(outcome.riskLevel))
        else -> PillStatus(Icons.Default.Shield, "Tap to check", neutralRiskColors())
    }
}

private fun scoredLabel(outcome: AnalysisOutcome.Scored): String {
    val level = outcome.riskLevel.name.lowercase().replaceFirstChar { it.uppercase() }
    return if (outcome.isCalibrated) "$level · ${outcome.percentLabel}" else "$level risk"
}

/**
 * The call-protection indicator: a small pill, about 44 dp tall, that shows
 * only an icon and a short status so it covers as little of the call screen as
 * possible. Tap to start or stop a check; drag to move it; drop it on the
 * dismiss target at the bottom of the screen to hide it.
 */
@Composable
fun OverlayPill(
    liveState: LiveState,
    inCall: Boolean,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val status = pillStatus(liveState, inCall)
    val container by animateColorAsState(status.colors.container, label = "pillContainer")
    val accent by animateColorAsState(status.colors.accent, label = "pillAccent")

    Surface(
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.5.dp, accent),
        shadowElevation = 6.dp,
        modifier = Modifier
            .height(44.dp)
            .semantics { contentDescription = "VoxShield: ${status.text}" }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) { detectTapGestures { onTap() } },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(status.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                status.text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = status.colors.content,
                maxLines = 1,
            )
        }
    }
}

/**
 * Drop target shown at the bottom of the screen only while the pill is being
 * dragged. Grows and turns red when the pill is close enough that releasing
 * would dismiss it, so the gesture is never a surprise.
 */
@Composable
fun DismissTarget(armed: Boolean) {
    val scale by animateFloatAsState(if (armed) 1.25f else 1f, label = "dismissScale")
    val bg by animateColorAsState(
        if (armed) Color(0xFFD32F2F) else Color(0xCC303030),
        label = "dismissColor",
    )
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = bg,
            modifier = Modifier
                .size(56.dp)
                .scale(scale),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, contentDescription = "Drop here to hide", tint = Color.White)
            }
        }
    }
}
