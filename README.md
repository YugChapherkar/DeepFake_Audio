# VoxShield: AI Voice Deepfake Detection & Call Protection

VoxShield is a privacy-focused Android security application that analyzes voice communication in near real time, estimates the likelihood of synthetic or manipulated speech, and warns users when a caller may be using an AI-generated or cloned voice.

---

## 📂 Project Structure (Android)

The application follows an MVVM (Model-View-ViewModel) architecture using Jetpack Compose.

```text
app/src/main/java/com/phx/yugcall/
├── MainActivity.kt      # Main UI Entry point (VoxShield Home, Analysis Cards)
├── MainViewModel.kt     # State management, History tracking, API/Recorder bridge
├── AudioRecorder.kt     # MediaRecorder implementation for mic capture
├── RiskEngine.kt        # Logic for categorizing synthetic probability scores
└── ui/theme/            # Material3 Theme, Typography, and Color definitions
```

---

## 🗺️ Step-by-Step Project Roadmap

### Phase 1: Foundation (Current State)
- [x] Basic UI with Jetpack Compose.
- [x] Local Audio Recording & File Picking.
- [x] Simulated Risk Engine & Scan History.
- [x] Permissions handling (Mic/Storage).

### Phase 2: Backend Integration (Next Up)
- [ ] Connect Android app to external API using Retrofit.
- [ ] Implement live WebSocket streaming for real-time analysis.
- [ ] Replace mock results with real ML inference data.

### Phase 3: Advanced Detection
- [ ] Implement WebRTC for controlled VoIP calling protection.
- [ ] Research and implement `CallScreeningService` for native call hooks.
- [ ] Add "Trusted Contact" verification workflow.

### Phase 4: Optimization & Privacy
- [ ] Move model inference to TFLite/ONNX for on-device processing.
- [ ] Implement end-to-end encryption for any data sent to the cloud.

---

## 🛠️ Backend Handoff: Requirements for Development

To the Backend Developer: Please build a service with the following specifications.

### 1. Environment
- **Framework:** FastAPI (Python 3.10+)
- **ML backbone:** PyTorch or ONNX Runtime.
- **Backbone Models:** Wav2Vec 2.0, HuBERT, or wavLM fine-tuned for deepfake detection.

### 2. Mandatory Endpoints

#### `POST /v1/analyze` (File-based)
Used for non-real-time file analysis.
- **Request:** `multipart/form-data` with key `audio_file`.
- **Logic:**
  1. Resample to 16kHz Mono.
  2. Run through Deepfake Detection Model.
- **Response Schema:**
```json
{
  "synthetic_probability": 0.85,
  "risk_level": "HIGH",
  "confidence": 0.90
}
```

#### `WS /v1/stream` (Real-time)
WebSocket endpoint for live microphone/call analysis.
- **Protocol:** Binary WebSocket.
- **Expected Input:** Binary chunks of raw audio (PCM 16-bit, 16kHz).
- **Processing:** Perform rolling inference on 2.5-second windows.
- **Output:** Stream JSON packets back to Android every 500ms with updated risk scores.

### 3. Audio Specs
The model **must** be trained/configured for:
- Sample Rate: 16,000 Hz
- Channels: Mono
- Format: PCM 16-bit or AAC/Opus in container.

---

## 🔗 How to Connect Android to Backend

### Step 1: Base URL Configuration
Update the `BASE_URL` in the Android networking layer (using Retrofit) to point to your hosted backend (e.g., `https://api.voxshield.com/`).

### Step 2: Request Model
The Android app uses **Retrofit** and **OkHttp**. Ensure the backend supports `Multipart` requests for file uploads and `Application/Json` for history/auth.

### Step 3: WebSocket Connection
Android will use `OkHttp.newWebSocket()` to connect to the `/v1/stream` endpoint.
- **Sending:** The app will send `ByteString` chunks of audio.
- **Receiving:** The app expects a JSON string containing the `synthetic_probability`.

### Step 4: Security
- The backend should implement **Bearer Token (JWT)** authentication.
- Android will include this token in the `Authorization` header for all requests.

---

## 🔒 Privacy Policy for Developers
- **Data Retention:** Never store raw audio files on the server longer than the duration of the inference.
- **Logging:** Do not log metadata that can identify a specific conversation or user voice.
