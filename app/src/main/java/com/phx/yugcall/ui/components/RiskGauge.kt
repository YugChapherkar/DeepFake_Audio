package com.phx.yugcall.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phx.yugcall.ui.theme.ScoreReadout

/**
 * A circular gauge, 270° swept from -225° to 45° (Material's canonical arc
 * angle, matching the system volume/brightness sliders users already know).
 *
 * @param fraction 0f..1f, or null when there is nothing to show yet (loading,
 *   no evidence, uncalibrated). Null renders an empty track with a dash rather
 *   than guessing a value — see the caller in AnalysisResultCard.
 * @param label short line under the number, e.g. "spoof score" or "loading".
 * @param centerText overrides the numeric readout, e.g. "—" when uncalibrated.
 */
@Composable
fun RiskGauge(
    fraction: Float?,
    trackColor: Color,
    progressColor: Color,
    label: String,
    modifier: Modifier = Modifier,
    centerText: String? = null,
    size: androidx.compose.ui.unit.Dp = 176.dp,
) {
    val animated by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "riskGaugeFraction",
    )

    val display = centerText ?: fraction?.let { "${(it * 100).toInt()}%" } ?: "—"
    val a11yLabel = "$label: ${fraction?.let { "${(it * 100).toInt()} percent" } ?: "no score available"}"

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = a11yLabel },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokeWidth = this.size.minDimension * 0.09f
            val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            val topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2)
            val sweepTotal = 270f
            val startAngle = 135f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            if (fraction != null) {
                drawArc(
                    color = progressColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * animated.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }

        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = display, style = ScoreReadout, color = progressColor)
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Smallest gauge diameter that keeps the readout legible; used in compact contexts. */
val RiskGaugeCompactSize = 96.dp
