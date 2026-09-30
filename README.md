# VoxShield: AI Voice Deepfake Detection & Call Protection

VoxShield is a privacy-focused Android security application that analyzes voice
communication in near real time, estimates the likelihood of synthetic or
manipulated speech, and warns users when a caller may be using an AI-generated
or cloned voice.

**Inference runs entirely on the device.** No audio leaves the phone.

---

## 📂 Project Structure

MVVM with Jetpack Compose.

```text
app/src/main/java/com/phx/yugcall/
├── VoxShieldApplication.kt  # App-wide model instance + shared live session
├── MainActivity.kt          # Compose UI: hero gauge, actions, scan history
├── MainViewModel.kt         # Coordinates the shared session + file analysis
├── RiskEngine.kt            # Calibration, thresholds, multi-window aggregation
├── audio/
│   ├── AudioFormatSpec.kt   # The 16 kHz / 64,600-sample contract
│   ├── AudioCapture.kt      # AudioRecord -> overlapping PCM windows
│   └── AudioDecoder.kt      # MediaCodec -> 16 kHz mono float PCM
├── detect/
│   ├── SpoofDetector.kt          # Detector interface, score types, padding
│   ├── OnnxSpoofDetector.kt      # ONNX Runtime session
│   ├── ExecutionBackend.kt       # XNNPACK / NNAPI / CPU selection
│   └── LiveListeningSession.kt   # Mic + model + aggregation, shared by app & overlay
├── history/
│   └── ScanHistory.kt       # App-wide scan history (LIVE / OVERLAY / FILE)
├── consent/
│   ├── Disclaimers.kt           # Canonical disclaimer copy
│   ├── ConsentStore.kt          # Tracks acceptance (SharedPreferences)
│   ├── DisclaimerScreen.kt      # First-run gate, scroll-to-unlock
│   └── OverlayConsentScreen.kt  # Second gate before Call Protection
├── overlay/
│   ├── CallGuardOverlayService.kt # Foreground service hosting the bubble
│   ├── OverlayBubble.kt           # Collapsed/expanded Compose UI
│   ├── OverlayLifecycleOwner.kt   # Lifecycle plumbing for a WindowManager view
│   ├── OverlayPermission.kt       # SYSTEM_ALERT_WINDOW helpers
│   └── CallStateMonitor.kt        # Detects an active call (AudioManager.mode)
└── ui/
    ├── theme/                # Material3 theme, dynamic colour, risk palette
    └── components/           # RiskGauge, InCallBanner

app/src/main/assets/aasist_l.onnx   # Fine-tuned AASIST-L (see docs/MODEL.md)
tools/export_aasist_onnx.py         # Exports a checkpoint to ONNX with a parity check
tools/finetune_aasist.py            # Fine-tuning + evaluation job (see docs/TRAINING.md)
```

## 📞 Call Protection (floating overlay)

A small pill-shaped indicator that floats over any other app, including the
phone app's call screen. It shows only an icon and a short status ("Tap to
check", "Listening…", "High · 97%", "Muted by Android").

**How it behaves:**
- **Arming.** The Call Protection switch arms it, and the pill appears straight
  away.
- **During a call.** When a call starts, a heads-up "Call detected"
  notification appears; tapping it opens the pill. The ongoing notification
  also has **Show** and **Stop** actions.
- **Using the pill.** Tap it to start or stop a check. Drag it anywhere. Drop it
  on the target that appears at the bottom of the screen while dragging to hide
  it. Protection stays armed, so the next call notifies again.
- **One session, one history.** The pill starts and stops the same shared
  listening session as the in-app "Listen" button, so its scans land in Recent
  Scans like any other.

**What it cannot do — by Android's design, not this app's:** capture the other
side of a live cellular call directly. Android silences microphone input from
every third-party app, this one included, the instant `MODE_IN_CALL` or
`MODE_IN_COMMUNICATION` is active — there is no permission that lifts this
([source](https://developer.android.com/media/platform/sharing-audio-input)).
**Speakerphone does not get around this on the same phone.** Measured on a
Pixel 6a during a speakerphone call: the system's recording monitor reported
this app's capture as `silenced:true` for the whole call. So the phone taking
the call can't check it. What works:

- VoxShield on a **second phone** held near the call's speaker. That phone
  isn't in a call, so it isn't silenced.
- **Upload**, for recordings and voicemails afterwards.

The app reads `AudioRecordingConfiguration.isClientSilenced()` and shows "Muted
by Android" instead of an endless "Listening…". Video and internet calling
apps hold the microphone too and haven't been tested.

**Setup requires two separate consent screens, then two permissions:**
1. The general disclaimer (`DisclaimerScreen`), required before anything else.
2. `OverlayConsentScreen` — specific to this feature, covering call-recording
   consent law and the `SYSTEM_ALERT_WINDOW` permission's own risk profile.
3. `RECORD_AUDIO`, if not already granted.
4. `SYSTEM_ALERT_WINDOW`, granted only through Settings (there is no runtime
   dialog for it).

A persistent low-priority notification stays visible for as long as the
service can access the microphone, with a one-tap Stop action.

## 🔬 How it works

1. `AudioCapture` opens `AudioRecord` at 16 kHz mono PCM, preferring the
   `UNPROCESSED` source so AGC and noise suppression do not erase the artefacts
   the model looks for.
2. Samples accumulate into 64,600-sample (~4.04 s) windows, emitted once per
   second. Windows below an RMS silence gate are skipped rather than scored.
3. `OnnxSpoofDetector` runs AASIST-L over each window and returns two logits.
4. `RiskEngine` converts the bona fide margin to a probability through a
   calibration step, then aggregates the last ~15 windows at the 90th percentile
   so a single noisy window cannot escalate a whole call.
5. The UI shows the risk band, how many windows backed it, and — while the model
   is uncalibrated — explicitly declines to render a percentage.

**Read [`docs/MODEL.md`](docs/MODEL.md) before trusting any number this app
produces.** The bundled model is AASIST-L fine-tuned for real-world audio: 9.2%
EER on held-out In-the-Wild speakers, down from 41% for the pretrained
checkpoint. It has never been measured on phone-microphone audio, its
calibration reflects its dev split's class balance, and the risk thresholds are
still placeholders.

## 📚 Docs

| Doc | What's in it |
|---|---|
| [`docs/PRESENTATION.md`](docs/PRESENTATION.md) | Presenter's brief: talk track, live demo script, likely questions |
| [`docs/MODEL.md`](docs/MODEL.md) | The bundled model, calibration, latency, the Pixel 6a NPU question |
| [`docs/TRAINING.md`](docs/TRAINING.md) | How the model is fine-tuned on Kaggle and shipped |
| [`docs/EXPERIMENTS.md`](docs/EXPERIMENTS.md) | Every run and measurement, with results |
| [`docs/ASVSPOOF5.md`](docs/ASVSPOOF5.md) | Notes on the ASVspoof 5 paper and what it changes here |

## 🗺️ Roadmap

### Phase 1: Foundation — done
- [x] Compose UI, permissions, scan history.
- [x] Streaming 16 kHz PCM capture (`AudioRecord`).
- [x] File decode and analysis (`MediaCodec`).
- [x] On-device ONNX inference with pretrained AASIST-L.
- [x] Silence gating, multi-window aggregation, honest "unavailable" state.

### Phase 2: Make the number mean something — in progress
- [x] Fit Platt calibration on a held-out validation split.
- [ ] Derive risk thresholds from a target false-positive rate.
- [x] Report EER on a corpus that was never trained on (held-out In-the-Wild speakers).
- [ ] Measure on phone-microphone recordings from the target device.
- [ ] Replace the linear resampler in `AudioDecoder` with windowed-sinc.

### Phase 3: Robustness
- [x] Fine-tune with music/noise, reverb and telephone (8 kHz + µ-law) augmentation.
- [ ] Real codec augmentation (Opus, AMR-NB, Speex) and ASVspoof 5 training data.
- [ ] Test fake voices with background noise (suspected "noise means real" shortcut).
- [ ] On-device latency and battery benchmarks per SoC tier.

### Phase 4: Call integration
- [x] Foreground service with `microphone` type — the Call Protection overlay.
- [x] Floating draggable indicator, shared live session, in-call honesty banner.
- [ ] `CallScreeningService` for caller-ID-based screening (note: gives number, never audio).
- [ ] "Trusted contact" verification workflow.

### Phase 5: Optimization
- [x] On-device benchmark across execution backends and thread counts.
- [ ] int8 quantization — currently blocked by a shape inconsistency inside
      AASIST's attention subgraph; needs graph surgery. See `docs/MODEL.md`.
- [ ] Evaluate a conv-only architecture (log-mel + MobileNetV3) if offload ever
      becomes necessary. On Tensor G1 the TPU is not reachable at all.

## ⚠️ Platform constraints worth knowing up front

- **You cannot capture call audio on a stock device.** `VOICE_CALL` /
  `VOICE_DOWNLINK` require the privileged `CAPTURE_AUDIO_OUTPUT` permission.
  `CallScreeningService` gives you the caller's number, not their voice, and
  the microphone is muted for every third-party app during a call — even on
  speakerphone (measured on a Pixel 6a). The only route for a normal app is a
  second device listening to the call's speaker, which means the far end
  arrives re-recorded through a room.
- **The Pixel 6a's TPU cannot be used.** Google's Tensor SDK covers Tensor
  G5/G6 (Pixel 10) only; ONNX Runtime has no Edge TPU provider; and NNAPI — the
  one theoretical route — is deprecated as of Android 15, needs int8, and does
  not cover AASIST's graph attention. The app runs on CPU and measures itself.

## ⚠️ Legal: recording consent

Many jurisdictions require **every** party's consent before a call can be
recorded or analysed — often called "two-party" or "all-party" consent — and
the rules vary by state and country. **You are responsible for knowing and
following the law that applies to you and the other party** before enabling
Call Protection. `OverlayConsentScreen` states this and requires an explicit
checkbox before the feature can be turned on; it does not and cannot make the
feature legal to use in a given situation.

## 🔒 Privacy

- Audio is captured, scored and discarded in memory. Nothing is written to disk
  and nothing is transmitted.
- There is no network code in the app. `INTERNET` is not declared at all.
- The floating overlay only ever renders VoxShield's own risk indicator. It
  does not read, intercept, or overlay content from any other app.

## 🛠️ Building

```bash
./gradlew :app:assembleDebug            # per-ABI APKs
./gradlew :app:testDebugUnitTest        # unit tests (JVM)
./gradlew :app:connectedDebugAndroidTest # on-device inference benchmark
adb logcat -s VoxShieldBenchmark        # ...and its results
```

Requires JDK 21 and the Android SDK. The build uses AGP 9's built-in Kotlin
support, so the `org.jetbrains.kotlin.android` plugin is deliberately **not**
applied — adding it will break the build.

## 📄 Third-party

The bundled model derives from [clovaai/aasist](https://github.com/clovaai/aasist),
MIT licensed, © NAVER Corp. See `docs/MODEL.md`.
