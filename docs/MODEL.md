# Detection model

## What is bundled

`app/src/main/assets/aasist_l.onnx` is **AASIST-L fine-tuned for real-world
audio** (run of 2026-09-30), starting from the pretrained checkpoint in
[clovaai/aasist](https://github.com/clovaai/aasist) (MIT, © NAVER Corp.).
How it's trained: [TRAINING.md](TRAINING.md). Results: [EXPERIMENTS.md](EXPERIMENTS.md).

| | |
|---|---|
| Parameters | 85,306 (architecture unchanged from AASIST-L) |
| Asset size | ~1.15 MB (ONNX, fp32, opset 20) |
| sha256 | `a0e3ac5c2e60d3c86c091b0781a9091a1a8e0f0b06cc4e5350cc12f3ede9ebfd` |
| Input | `waveform`, float32 `[1, 64600]`, 16 kHz mono, range [-1, 1] |
| Output | `logits`, float32 `[1, 2]` — index 0 spoof, index 1 bona fide |
| Shapes | fully static; one window per call |
| Calibration | `Calibration.BUNDLED_MODEL` (fitted, see below) |

Error rates, before and after fine-tuning, on data never used in training
(from [EXPERIMENTS.md](EXPERIMENTS.md)):

| Test set | Pretrained AASIST-L | Bundled (fine-tuned) |
|---|---:|---:|
| In-the-Wild, unseen speakers — EER | 41.33% | **9.21%** |
| ASVspoof 2019 LA eval — EER | **1.14%** | 3.30% |
| Real voice + music — false-alarm rate | 6.25% | **0.95%** |

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
    --weights best.pth \
    --out app/src/main/assets/aasist_l.onnx
```

`best.pth` comes from the fine-tuning job ([TRAINING.md](TRAINING.md)).
Leave out `--weights` to export the original pretrained checkpoint; if you do,
also set the calibration back to `Calibration.UNFITTED`.

The script exports, folds external data back into a single file, and then
verifies ONNX Runtime against PyTorch on random, silent and tonal input. It
fails the build if the two diverge by more than 1e-4; the observed delta is
~1e-6.

## What the accuracy numbers do not mean

**The pretrained checkpoint's published 0.99% EER is in-domain.** It is measured on the ASVspoof2019 LA evaluation
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
   The microphone is muted for third-party apps during a call too, even on
   speakerphone — measured on a Pixel 6a ([EXPERIMENTS.md](EXPERIMENTS.md)). So
   on a stock device the far end can only be captured by a *second* device near
   the call's speaker, which means the model sees the far end through a
   loudspeaker, a room and a microphone. Training needs room-impulse-response / replay augmentation to
   survive that, or the detector will be scoring the room instead of the voice.

Measured, gap 1 was even worse than expected: the pretrained checkpoint scores
41% EER on held-out In-the-Wild speakers, close to a coin flip. Fine-tuning
brought that to 9.2%. Gap 2 is still unmeasured, because no public test set
here is phone-microphone audio.

A quick illustration from the export-time parity check, on synthetic input
neither model has seen:

| Input | Spoof softmax, pretrained | Spoof softmax, bundled |
|---|---:|---:|
| White noise | 0.0005 | 0.0006 |
| Silence | 0.4959 | 0.3371 |
| 440 Hz tone | 1.0000 | 0.2378 |

The pretrained model called a pure tone certainly fake, consistent with the
field report of music triggering false alarms. Both models call white noise
genuine. That isn't an export bug; it's what out-of-distribution input does
to this class of model, and it's why the app gates on an RMS silence
threshold. It's also consistent with the "noise means real" shortcut suspected
in the fine-tuned model ([EXPERIMENTS.md](EXPERIMENTS.md#open-question-the-noise-shortcut)).

## Calibration status: **fitted, for the bundled model only**

`Calibration.BUNDLED_MODEL` in `RiskEngine.kt` holds a Platt calibration fitted
by logistic regression on the fine-tuning run's pooled dev split (7,605 clips):

```
p(bona fide) = sigmoid(1.6442840 · cmScore + 0.3187831)
```

The app therefore shows a percentage again. Two caveats:

- **The probability reflects the dev split's class balance** (about a third
  bona fide), not the real-world rate of synthetic callers.
- **It belongs to this one model file.** Replacing `aasist_l.onnx` without
  refitting silently makes the percentages wrong. Revert to
  `Calibration.UNFITTED` (which renders "—") rather than keep stale
  coefficients.

`RiskThresholds.PLACEHOLDER` (0.40 / 0.70) is still inherited from the original
mock, not derived from data. Real boundaries come from sweeping a validation
set for a target false-positive rate — and because a false HIGH tells someone
their family member is an impersonator, the tolerable false-positive rate is
very low.

## Measured latency

ONNX Runtime, CPU execution provider, 2 intra-op threads, one 64,600-sample
window (4.038 s of audio):

| Host | Median | p95 | Real-time factor |
|---|---|---|---|
| x86_64 desktop | 111 ms | 329 ms | 0.028x |
| Pixel 6a (Tensor G1, Android 17 beta) | 302 ms | 313 ms | 0.075x |

### Pixel 6a, full sweep

From `OnDeviceBenchmarkTest`, 20 iterations after 3 warm-up runs. Budget is
1000 ms (one window per second).

| Backend | Threads | Median | p95 |
|---|---|---|---|
| **CPU** | **2** | **302 ms** | **313 ms** |
| CPU | 1 | 489 ms | 498 ms |
| CPU | 4 | 544 ms | 933 ms |
| XNNPACK | 4 | 740 ms | 1001 ms |
| XNNPACK | 2 | 877 ms | 1233 ms |
| XNNPACK | 1 | 1117 ms | 1730 ms |
| NNAPI | 4 | 1567 ms | 2183 ms |
| NNAPI | 2 | 2107 ms | 2356 ms |
| NNAPI | 1 | 3647 ms | 5683 ms |

**Plain CPU at 2 threads is the default** — about 3x faster than XNNPACK, the
previous default, whose p95 overran the budget. The likely cause is that
XNNPACK covers only part of AASIST's graph, so the partition hand-offs plus a
second thread pool cost more than its kernels save; that is an inference, not
a measurement. Four threads is slower than two because Tensor G1 has only two
big cores. NNAPI is over budget at every thread count, as expected.

## Measuring on the target device

A desktop number decides nothing. Run the benchmark on the actual handset:

```bash
./gradlew :app:connectedDebugAndroidTest
adb logcat -s VoxShieldBenchmark
```

`OnDeviceBenchmarkTest` sweeps every [`ExecutionBackend`] (XNNPACK, NNAPI, plain
CPU) at 1, 2 and 4 intra-op threads, reports median and p95 per configuration,
and fails if nothing fits inside the 1000 ms hop. The app also shows the live
median in its status card (`aasist_l.onnx · CPU x2 · 302 ms/window`), so a
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

The app runs ONNX Runtime on plain CPU kernels (measured fastest). The NPU path
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
