package com.phx.yugcall

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.io.File

data class ScanHistory(
    val id: Int,
    val date: String,
    val result: AnalysisResult
)

class MainViewModel(
    private val recorder: AudioRecorder,
    private val context: Context
): ViewModel() {

    private val riskEngine = RiskEngine()

    var isRecording by mutableStateOf(false)
        private set

    var lastAnalysisResult by mutableStateOf<AnalysisResult?>(null)
        private set

    val history = mutableStateListOf<ScanHistory>()

    private var audioFile: File? = null

    fun onRecordClick() {
        if (isRecording) {
            recorder.stop()
            isRecording = false
            simulateAnalysis()
        } else {
            File(context.cacheDir, "audio_record.m4a").also {
                recorder.start(it)
                audioFile = it
                isRecording = true
                lastAnalysisResult = null
            }
        }
    }

    private fun simulateAnalysis() {
        // Mocking a synthetic probability for demonstration
        val mockProb = (0..100).random() / 100f
        val result = riskEngine.calculateRisk(mockProb)
        lastAnalysisResult = result
        history.add(0, ScanHistory(history.size, "Just now", result))
    }
    
    fun onUploadAudio(file: File) {
        simulateAnalysis()
    }

    fun onAudioPicked(uri: android.net.Uri?) {
        uri?.let {
            simulateAnalysis()
        }
    }
}
