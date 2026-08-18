package com.phx.yugcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.phx.yugcall.ui.theme.YugCallTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(
                    AndroidAudioRecorder(applicationContext),
                    applicationContext
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YugCallTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VoxShieldHome(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun VoxShieldHome(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isProtectionOn by remember { mutableStateOf(true) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.onAudioPicked(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "VoxShield",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Protection Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (viewModel.isRecording) Color(0xFFFFF9C4) 
                                else if (isProtectionOn) Color(0xFFE8F5E9) 
                                else Color(0xFFFFEBEE)
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (viewModel.isRecording) "Recording..." else "Protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (viewModel.isRecording) "Analyzing audio chunks" 
                                else if (isProtectionOn) "ON" 
                                else "OFF",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (viewModel.isRecording) Color(0xFFFBC02D)
                                else if (isProtectionOn) Color(0xFF2E7D32) 
                                else Color(0xFFC62828)
                    )
                }
                Icon(
                    imageVector = if (viewModel.isRecording) Icons.Default.Mic else Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (viewModel.isRecording) Color(0xFFFBC02D)
                           else if (isProtectionOn) Color(0xFF2E7D32) 
                           else Color(0xFFC62828),
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Analysis Result
        viewModel.lastAnalysisResult?.let { result ->
            AnalysisResultCard(result)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MainActionButton(
                text = if (viewModel.isRecording) "Stop" else "Record",
                icon = Icons.Default.Mic,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (hasMicPermission) {
                        viewModel.onRecordClick()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
            MainActionButton(
                text = "Upload",
                icon = Icons.Default.Upload,
                modifier = Modifier.weight(1f),
                onClick = {
                    audioPickerLauncher.launch("audio/*")
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // History Preview
        Text(
            text = "Recent Scans",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(viewModel.history.size) { index ->
                HistoryItem(viewModel.history[index])
            }
        }
    }
}

@Composable
fun MainActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = text)
        }
    }
}

@Composable
fun HistoryItem(item: ScanHistory) {
    val color = when (item.result.riskLevel) {
        RiskLevel.LOW -> Color(0xFF2E7D32)
        RiskLevel.MEDIUM -> Color(0xFFFBC02D)
        RiskLevel.HIGH -> Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = color
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = "Scan #${item.id} • ${item.date}", fontWeight = FontWeight.Bold)
                Text(
                    text = "Risk: ${item.result.riskLevel.name} • ${(item.result.syntheticProbability * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }
}

@Composable
fun AnalysisResultCard(result: AnalysisResult) {
    val color = when (result.riskLevel) {
        RiskLevel.LOW -> Color(0xFF2E7D32)
        RiskLevel.MEDIUM -> Color(0xFFFBC02D)
        RiskLevel.HIGH -> Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Analysis Result",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Risk Level:", fontWeight = FontWeight.Medium)
                Text(text = result.riskLevel.name, fontWeight = FontWeight.Bold, color = color)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Synthetic Probability:", fontWeight = FontWeight.Medium)
                Text(text = "${(result.syntheticProbability * 100).toInt()}%")
            }
            if (result.riskLevel == RiskLevel.HIGH) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ Possible AI-generated voice detected. Verify caller independently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VoxShieldHomePreview() {
    YugCallTheme {
        // Mock recorder and context for preview
        val context = LocalContext.current
        val viewModel = MainViewModel(object : AudioRecorder {
            override fun start(outputFile: java.io.File) {}
            override fun stop() {}
        }, context)
        VoxShieldHome(viewModel = viewModel)
    }
}
