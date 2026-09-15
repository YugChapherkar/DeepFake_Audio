# Detection model

## What is bundled

`app/src/main/assets/aasist_l.onnx` — the pretrained **AASIST-L** countermeasure
exported from [clovaai/aasist](https://github.com/clovaai/aasist) (MIT, © NAVER Corp.).

| | |
|---|---|
| Parameters | 85,306 |
| Asset size | ~1.16 MB (ONNX, fp32, opset 20) |
| Input | `waveform`, float32 `[1, 64600]`, 16 kHz mono, range [-1, 1] |
| Output | `logits`, float32 `[1, 2]` — index 0 spoof, index 1 bona fide |
| Shapes | fully static; one window per call |
| Published accuracy | 0.99% EER / 0.0309 min t-DCF, ASVspoof2019 LA eval |

The class order comes from `aasist/data_utils.py`, which assigns `1` to
`bonafide` and `0` to `spoof`. `main.py` scores with `batch_out[:, 1]`, so the
countermeasure score is the **bona fide** logit and higher means more genuine.
`WindowScore.cmScore` reproduces that convention as `bonafide - spoof`.

## Regenerating the asset

```bash
git clone --depth 1 https://github.com/clovaai/aasist.git /tmp/aasist
python -m venv .venv && . .venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install onnx onnxruntime onnxscript

python tools/export_aasist_onnx.py \
    --aasist-repo /tmp/aasist \
    --out app/src/main/assets/aasist_l.onnx
```

The script exports, folds external data back into a single file, and then
verifies ONNX Runtime against PyTorch on random, silent and tonal input. It
fails the build if the two diverge by more than 1e-4; the observed delta is
~1e-6.

## What the accuracy number does not mean

**0.99% EER is in-domain.** It is measured on the ASVspoof2019 LA evaluation
set, which shares its recording conditions and its synthesis systems with the
training set. It is not the accuracy you will see on a real call, and treating
it as such is the single most likely way this project ships something that does
not work.

Two known gaps, both large:

1. **Unseen attacks and unseen recording conditions.** Countermeasures trained
   on ASVspoof2019 LA degrade sharply on in-the-wild audio and on the
   codec-varied ASVspoof2021 DF set. Plan on fine-tuning against a union of
   corpora plus RawBoost and codec augmentation (AMR-NB, Opus, µ-law), and
   report EER on a corpus that was never trained on.

2. **Acoustic re-recording.** `VOICE_CALL` / `VOICE_DOWNLINK` need the
   privileged `CAPTURE_AUDIO_OUTPUT` permission and are unavailable to normal
   apps, and `CallScreeningService` exposes the caller's number but never audio.
   On a stock device the far end can only be captured over speakerphone — which
   means the model sees the far end through a loudspeaker, a room and a
   microphone. Training needs room-impulse-response / replay augmentation to
   survive that, or the detector will be scoring the room instead of the voice.

A quick illustration from the export-time parity check, on synthetic input the
model has never seen:

| Input | Spoof softmax |
|---|---|
| White noise | 0.0005 |
| Silence | 0.4959 |
| 440 Hz tone | 1.0000 |

White noise reads as almost certainly genuine. That is not a bug in the export
— it is what out-of-distribution input does to this class of model, and it is
why the app gates on an RMS silence threshold and why the score is not shown as
a probability.

## Calibration status: **not calibrated**

`Calibration.UNFITTED` is the identity transform, so the "probability" is just
the model's softmax. A softmax over a cross-entropy head is systematically
overconfident and encodes no prior over how often callers are actually
synthetic. The UI therefore renders `—` instead of a percentage while
`isCalibrated` is false, and says so on the card.

To fit it: collect CM scores over a held-out validation split, run logistic
regression of the label on `cmScore`, and pass the resulting slope/intercept
into `RiskEngine` with `fitted = true`.

`RiskThresholds.PLACEHOLDER` (0.40 / 0.70) is likewise inherited from the
original mock and is not derived from data. Real boundaries come from sweeping
the validation set for a target false-positive rate — and because a false HIGH
tells someone their family member is an impersonator, the tolerable FPR is very
low.

## Measured latency

ONNX Runtime, CPU execution provider, 2 intra-op threads, one 64,600-sample
window (4.038 s of audio):

| Host | Median | p95 | Real-time factor |
|---|---|---|---|
| x86_64 desktop | 111 ms | 329 ms | 0.028x |

**This is a desktop number and is not a phone number.** Treat it as an order of
magnitude only. A mid-range Android SoC should be expected to land somewhere
around 3-5x slower, i.e. roughly 300-600 ms per window, against a 1 s hop. That
fits, but the headroom is perhaps 2-3x rather than the 100x the parameter count
suggests — AASIST's graph attention costs far more than its weight count
implies. **Benchmark on the actual target tier before treating the CPU path as
settled**; `OnnxSpoofDetector` is the only place that needs to change.

## Measuring on the target device

A desktop number decides nothing. Run the benchmark on the actual handset:

```bash
./gradlew :app:connectedDebugAndroidTest
adb logcat -s VoxShieldBenchmark
```

`OnDeviceBenchmarkTest` sweeps every [`ExecutionBackend`] (XNNPACK, NNAPI, plain
CPU) at 1, 2 and 4 intra-op threads, reports median and p95 per configuration,
and fails if nothing fits inside the 1000 ms hop. The app also shows the live
median in its status card (`aasist_l.onnx · XNNPACK x2 · 287 ms/window`), so a
demo doubles as a measurement.

## Target hardware: Pixel 6a (Google Tensor G1)

**The Tensor TPU is not reachable for this model.** Three independent blocks,
none of which is a scoping choice:

1. **The Google Tensor SDK covers Tensor G5/G6 only** — the Pixel 10 family.
   There is no G1/G2/G3 support, so Pixel 6/7/8 cannot use the LiteRT TPU path
   at all. The SDK is also still beta and gated behind a signup.
2. **ONNX Runtime ships no Edge TPU execution provider.** The Android AAR
   exposes exactly three: NNAPI, XNNPACK and QNN (Qualcomm only). Nothing
   targets Tensor.
3. **NNAPI, the one theoretical route, is a dead end here.** Deprecated in
   Android 15, it needs a fully int8 model to reach the Edge TPU, and its
   operator set does not cover AASIST's heterogeneous graph attention. The graph
   would be partitioned across NNAPI and CPU, which typically costs more than
   it saves.

`ExecutionBackend.NNAPI` exists so point 3 can be measured rather than believed.
If it wins on your device, the default should change.

### int8 quantization is also blocked, for now

`onnxruntime.quantization.quantize_dynamic` fails on the exported graph with
`[ShapeInferenceError] Inferred shape and existing shape differ in dimension 0:
(160) vs (2)`, originating inside the graph-attention subgraph. Top-level shape
inference succeeds, so this is a genuine inconsistency in the attention ops, not
an artefact of the export configuration. Quantizing AASIST needs graph surgery
or a different export path — it is not a quick win.

This is the same property from three directions: AASIST's graph attention is
awkward for quantizers, for NPU partitioners, and for vendor compilers alike.
If latency ever becomes the binding constraint, changing architecture (log-mel
plus a conv-only backbone such as MobileNetV3) buys more than fighting AASIST
through a delegate.

## Why CPU and not the NPU

The app runs ONNX Runtime on CPU with XNNPACK where available. The NPU path
costs more than it returns today:

- NNAPI was deprecated in Android 15, so the obvious delegate is a dead end.
- The LiteRT NPU delegate currently ships for Qualcomm; Pixel and Samsung are
  listed as coming soon. Shipping it means maintaining both paths anyway.
- AASIST's heterogeneous graph-attention blocks are exactly the ops that fall
  back to CPU inside a vendor compiler, so the speedup would be partial at best.

Revisit when there is a measured latency or battery number that justifies it.
If continuous background scoring becomes the product, the right move is probably
to change architecture — log-mel plus a conv-only backbone such as MobileNetV3
quantizes and maps cleanly to every vendor NPU — rather than to force AASIST
through a delegate.
