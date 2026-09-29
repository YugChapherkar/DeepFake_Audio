# VoxShield: AI Voice Deepfake Detection & Call Protection

VoxShield is a privacy-first Android security application that analyzes voice communication in near real time, estimates the likelihood of synthetic or manipulated speech, and warns users when a caller or recording may be using an AI-generated or cloned voice.

**Inference runs 100% on-device.** Audio is analyzed locally in memory and never leaves the phone.

---

## 🌟 Key Features

- **100% On-Device AI Inference**: Uses the lightweight **AASIST-L** model (`aasist_l.onnx`, ~1.16 MB) running via ONNX Runtime on CPU/XNNPACK. Zero data is sent to external servers.
- **Real-Time Audio Analysis**: Captures 16 kHz mono PCM audio in 4.04-second overlapping windows (64,600 samples) emitted once per second.
- **Silence Gating**: Automatically skips silent/ambient noise windows using RMS thresholding to preserve battery and compute resources.
- **Audio File Analysis**: Built-in `MediaCodec` decoder allows users to upload and evaluate recorded call files or voice notes (WAV, MP3, M4A, etc.).
- **Multi-Window Risk Engine**: Aggregates predictions across multiple time windows using 90th percentile scoring to eliminate false positives from single noisy windows.
- **Privacy-First Architecture**: Audio is scored in volatile memory and discarded immediately. No cloud transmission, no disk logging.

---

## 📂 Project Structure

The project follows an MVVM (Model-View-ViewModel) architecture built with Jetpack Compose.

```text
app/
├── src/main/
│   ├── assets/
│   │   └── aasist_l.onnx           # Pretrained AASIST-L countermeasure model (~1.16 MB)
│   └── java/com/phx/yugcall/
│       ├── MainActivity.kt         # Compose UI: Status card, Level meter, Results, Scan history
│       ├── MainViewModel.kt        # State management, Audio capture loop, File decode bridge
│       ├── RiskEngine.kt           # Score calibration, Thresholds, Multi-window aggregation
│       ├── audio/
│       │   ├── AudioFormatSpec.kt  # 16 kHz / 64,600-sample window contract
│       │   ├── AudioCapture.kt     # AudioRecord streaming -> Overlapping PCM windows
│       │   └── AudioDecoder.kt     # MediaCodec -> 16 kHz mono float PCM decoder
│       ├── detect/
│       │   ├── SpoofDetector.kt    # Detector interface, Result models, Padding utilities
│       │   ├── OnnxSpoofDetector.kt# ONNX Runtime session & inference execution
│       │   └── ExecutionBackend.kt # CPU, XNNPACK, and NNAPI backend selectors
│       └── ui/theme/               # Material3 theme, Typography, and Risk color palettes
├── src/androidTest/
│   └── java/com/phx/yugcall/detect/
│       └── OnDeviceBenchmarkTest.kt# On-device latency and throughput test harness
├── docs/
│   └── MODEL.md                    # Deep dive into model architecture, export, & limits
└── tools/
    └── export_aasist_onnx.py       # PyTorch to ONNX export script for AASIST-L
```

---

## 🧠 The AI Model: AASIST-L

VoxShield uses **AASIST-L** (*Audio Anti-Spoofing using Integrated Spectro-Temporal Graph Attention Networks*):

| Metric / Property | Value |
| :--- | :--- |
| **Parameters** | 85,306 (~1.16 MB fp32 ONNX) |
| **Input Contract** | 64,600 float32 samples (4.038 s at 16 kHz Mono) |
| **Output** | `[logits_spoof, logits_bonafide]` |
| **Execution Provider** | CPU / XNNPACK multi-threading |
| **Inference Time** | ~200-400 ms per window on mobile CPUs |

For model details, calibration status, and benchmark logs, see [`docs/MODEL.md`](docs/MODEL.md).

---

## 🗺️ Step-by-Step Project Roadmap

### Phase 1: Foundation & On-Device ML (Current State)
- [x] Modern Jetpack Compose Material3 UI.
- [x] Real-time 16 kHz PCM streaming (`AudioRecord` with `UNPROCESSED` source).
- [x] File decoding pipeline (`MediaCodec` -> 16 kHz mono float PCM).
- [x] On-device ONNX inference engine (`AASIST-L`).
- [x] Silence gating & 90th-percentile multi-window risk aggregation.
- [x] On-device benchmark test suite (`OnDeviceBenchmarkTest`).

### Phase 2: Score Calibration & Dataset Fine-Tuning (Next Up)
- [ ] Fit Platt calibration on held-out validation data for accurate probability rendering.
- [ ] Train with RawBoost and codec augmentations (AMR-NB, Opus, µ-law) for real telephony noise.
- [ ] Implement room-impulse-response (RIR) augmentation for speakerphone re-recording scenarios.

### Phase 3: Hybrid Backend Pipeline (Optional Extension)
- [ ] **FastAPI Inference Service**: Build a cloud service for running heavier models (WavLM, XLS-R).
- [ ] **Local-First Routing**: Fast screening on-device; cloud verification for low-confidence samples.
- [ ] **WebSocket Streaming**: Binary WS protocol for real-time remote inference.

### Phase 4: Call Hooks & Verification
- [ ] **Foreground Service**: Background microphone monitoring with proper notification state.
- [ ] **CallScreeningService**: Caller-ID screening integration.
- [ ] **Trusted Contacts**: Family identity verification workflow.

---

## 🛠️ Optional Backend Specification (For Server Developers)

If expanding VoxShield with an optional cloud inference backend:

### Stack
- **Framework:** FastAPI (Python 3.10+)
- **Backbone Models:** Wav2Vec 2.0, HuBERT, or WavLM (Fine-tuned on ASVspoof 2021).
- **Target Hardware:** NVIDIA T4 / A10G GPU.

### Key Endpoints
1. `POST /v1/analyze`: Upload `multipart/form-data` audio file -> Resample to 16kHz mono -> Return `synthetic_probability` & `risk_level`.
2. `WS /v1/stream`: Binary WebSocket endpoint -> Accept raw 16kHz PCM chunks -> Return real-time score updates every 500ms.

---

## 🛠️ Building & Running

### Prerequisites
- JDK 21
- Android SDK 35+
- Android Device or Emulator (API 29+)

### Commands
```bash
./gradlew :app:assembleDebug             # Build debug APK
./gradlew :app:testDebugUnitTest         # Run JVM unit tests
./gradlew :app:connectedDebugAndroidTest # Run on-device inference benchmark
```

---

## 📄 License & Attribution
- Pretrained AASIST model weights derived from [clovaai/aasist](https://github.com/clovaai/aasist) (MIT License, © NAVER Corp.).
