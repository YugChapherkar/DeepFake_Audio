package com.phx.yugcall.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Consent gate shown before the call-protection overlay can be enabled for
 * the first time. Separate from [DisclaimerScreen] because this feature draws
 * over other apps and touches call-recording consent law, which is a
 * materially bigger ask than the base app.
 */
@Composable
fun OverlayConsentScreen(
    onAgree: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var acknowledgedLegal by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Call Protection",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "This adds a floating indicator over other apps, including your " +
                "phone app. Read this before turning it on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(Disclaimers.overlayPoints) { point ->
                Row {
                    Text("•  ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                    Text(point, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = acknowledgedLegal, onCheckedChange = { acknowledgedLegal = it })
            Text(
                "I understand the consent laws that apply to me, and I have the " +
                    "right to use this feature in my situation.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Not now") }
            Button(
                onClick = onAgree,
                enabled = acknowledgedLegal,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Agree & continue") }
        }
    }
}
