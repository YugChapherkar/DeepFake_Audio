# VoxShield: AI Voice Deepfake Detection & Call Protection

VoxShield is an Android-based AI security application designed to detect AI-generated or manipulated human speech. It helps protect users from voice cloning scams and impersonation by providing a probabilistic risk assessment of audio samples.

## 🚀 Vision
Build a privacy-conscious security layer that determines whether speech is likely authentic or synthetically generated in near real-time.

## ✨ MVP Features (Implemented)
- **Voice Analysis Dashboard**: A Jetpack Compose-based UI for monitoring protection status.
- **Audio Recording**: Integrated `MediaRecorder` to capture live audio chunks for analysis.
- **Audio Upload**: Support for selecting and analyzing existing audio files from the device.
- **Risk Engine**: Classification logic that categorizes voice samples into LOW, MEDIUM, or HIGH risk.
- **Scan History**: A persistent (session-based) history of all analyzed audio samples with risk breakdowns.
- **Permission Management**: Clean handling of microphone and notification permissions.

## 🛠 Tech Stack
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Concurrency**: Kotlin Coroutines & Flow
- **Dependency Management**: Gradle Version Catalog (toml)
- **Audio**: Android MediaRecorder API
- **Architecture**: MVVM with ViewModel and State

## 📅 Roadmap
1.  **Backend Integration**: Connect to a FastAPI/PyTorch server for deep learning-based inference.
2.  **Real-Time Streaming**: Implement WebSockets for live, chunk-based analysis.
3.  **WebRTC VoIP**: Create a controlled calling environment for real-time protection testing.
4.  **On-Device AI**: Research TFLite/ONNX models for offline, privacy-first detection.
5.  **Native Call Integration**: Explore `CallScreeningService` for native cellular call protection.

## 🛠 Setup & Installation
1.  Clone the repository.
2.  Open the project in **Android Studio**.
3.  Sync Gradle dependencies.
4.  Run the `app` module on an Android device (API 29+).

## 🔒 Privacy
VoxShield is built with privacy in mind. Current implementation focuses on local processing and temporary caching. Future backend integration will follow a "process-and-delete" policy for audio data.
