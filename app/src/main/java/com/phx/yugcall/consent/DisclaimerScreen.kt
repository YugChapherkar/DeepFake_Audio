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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen, non-skippable gate shown before any analysis feature is
 * reachable. "Continue" stays disabled until the whole list has been scrolled
 * past, so acceptance requires having seen every point rather than a
 * reflexive tap on first launch.
 */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val hasScrolledToEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 24
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Before you start",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "VoxShield estimates whether audio may be an AI voice. Please read " +
                "what that does and does not mean.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(Disclaimers.generalPoints) { point -> DisclaimerBullet(point) }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Spacer(Modifier.height(16.dp))
        if (!hasScrolledToEnd) {
            Text(
                "Scroll to read everything before continuing.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
        Button(
            onClick = onAccept,
            enabled = hasScrolledToEnd,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Text("I understand, continue")
        }
    }
}

@Composable
internal fun DisclaimerBullet(text: String) {
    Row {
        Text("•  ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
