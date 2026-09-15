package com.phx.yugcall.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phx.yugcall.RiskLevel
import com.phx.yugcall.ui.theme.riskColors

/**
 * The one honest thing this app can say about a live call: it cannot hear the
 * other side directly. Shown by both the in-app screen and the overlay
 * whenever [CallStateMonitor] reports an active call, so scoring silence never
 * gets mistaken for "the other person sounds fine."
 */
@Composable
fun InCallBanner(modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = riskColors(RiskLevel.MEDIUM)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.container, RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .padding(if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(if (compact) 16.dp else 20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "In a call — Android blocks direct call audio for every app, this " +
                "one included. Switch to speakerphone so the mic can pick up " +
                "the other person's voice.",
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            color = colors.content,
        )
    }
}
