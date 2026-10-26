# Experiment log

What was run, what came out, and what it does and does not show. Newest
first. Every number here is copied from a log or `metrics.json`, not estimated.

- [2026-09-30 — Short phrases in a live window score as genuine](#2026-09-30--short-phrases-in-a-live-window-score-as-genuine)
- [2026-09-30 — The phone can't hear its own call, even on speakerphone](#2026-09-30--the-phone-cant-hear-its-own-call-even-on-speakerphone)
- [2026-09-30 — Fine-tuning run 1](#2026-09-30--fine-tuning-run-1)
- [2026-09-30 — Field report: real voice + music flagged as AI](#2026-09-30--field-report-real-voice--music-flagged-as-ai)
- [2026-09-30 — On-device latency benchmark](#2026-09-30--on-device-latency-benchmark)

---

## 2026-09-30 — Short phrases in a live window score as genuine

**Finding:** in live mode, short fake clips played to the phone scored as
low as about 10% spoof. The same clips score 90%+ through Upload.

**Cause:** Upload tiles a short clip to fill the 4.04 s window, the same way
training handled short clips. Live mode scores the last 4.04 s of the
microphone, so a 2-second phrase arrives surrounded by room silence. The model
never trained on that shape.

**Off-device test:** each clip from `testdata/clips/` placed in the middle of a
4.04 s window of low-level noise (σ = 0.002), then scored with the bundled
model. One window per clip, no aggregation.

| Clip | Length | Window as-is | Naive trim + tile | Conservative trim | Looped playback |
|---|---:|---:|---:|---:|---:|
| fake 2Pac | 2.7 s | 58.2% | 99.8% | **86.9%** | 93.5% |
| fake Arnold Schwarzenegger | 2.2 s | 88.2% | 100.0% | **100.0%** | 99.9% |
| fake Martin Luther King | 6.5 s | 81.6% | 98.5% | 81.6% | 99.4% |
| fake Orson Welles | 13.9 s | 16.0% | 18.2% | 16.0% | 25.3% |
| fake Alan Watts | 1.4 s | 7.8% | — | — | 13.3% |
| real 2Pac | 8.1 s | 2.7% | **40.7%** | 2.7% | 11.1% |
| real Nick Offerman | 8.3 s | 16.2% | **62.4%** | 16.2% | 8.7% |
| real Martin Luther King | 2.0 s | 0.5% | 14.5% | 3.5% | 2.3% |
| real Arnold Schwarzenegger | 1.7 s | 0.7% | — | 2.6% | 0.8% |
| real Alan Watts | 16.3 s | 0.0% | 0.1% | 0.0% | 0.2% |

For clips longer than the window, only the first 4.04 s is used, which is why
fake Orson Welles scores low here. The app aggregates many windows, and via
Upload that clip scores 97.2%.

- **Naive trimming** (trim to frames above 15% of peak, then tile) rescues the
  fakes, but pushes real voices up to 62%. It trims the quiet edges of
  continuous speech, and the tiling seam that creates reads as synthetic.
- **Conservative trimming** only acts when speech covers less than 75% of the
  window (frames above 5% of peak), keeping 200 ms either side. It rescues
  short fakes and leaves continuous speech untouched. This is what shipped, as
  `trimToSpeech` in `SpeechTrim.kt`, in the live path only; Upload is
  unchanged.
- **Looped playback** is the test-procedure alternative: play a short clip
  back to back so it fills the window.

**Caveat:** the noise here is added digitally. The real path is laptop
speaker, room, then phone mic, and hasn't been re-measured with this change.

---

## 2026-09-30 — The phone can't hear its own call, even on speakerphone

**Finding:** during a cellular call, Android feeds VoxShield silence from the
microphone, **with speakerphone on**. Earlier docs, the in-app banner and the
consent copy all suggested speakerphone as the workaround. That was wrong for
capture on the same phone, and all of them are now corrected.

**How it was measured** (Pixel 6a, Android 17 beta, live call on
speakerphone, `adb shell dumpsys audio`):

```
mAudioModeOwner: ... mMode=2          # MODE_IN_CALL
Active communication device: ... type:speaker ... name:Pixel 6a
session:7785 -- source client=VOICE_RECOGNITION -- pack:com.phx.yugcall -- silenced:true
```

The pill showed "Listening…" and never produced a score. Every window was
digital silence, so the silence gate skipped it.

**Also verified:** the overlay itself *is* drawn over Google's Phone app
during a call. Its window was stacked above `InCallActivity`, with
`Surface shown=true` and `isOnScreen=true`, so the Phone app doesn't hide
third-party overlays.

**What changed:**
- `AudioCapture` now reads `AudioRecord.activeRecordingConfiguration
  .isClientSilenced()` for every window. The UI shows "Muted by Android"
  instead of "Listening…".
- Banner and disclaimer copy now say a second phone near the speaker, or
  Upload afterwards.
- `ConsentStore` keys moved to `_v2`, because the copy people agreed to has
  changed materially.

**Still untested:** video and internet calling apps, which put the device in
`MODE_IN_COMMUNICATION` and hold the microphone themselves.

---

## 2026-09-30 — Fine-tuning run 1

**Status: shipped as the bundled model** (`app/src/main/assets/aasist_l.onnx`,
sha256 `a0e3ac5c…ebfd`) together with its fitted calibration,
`Calibration.BUNDLED_MODEL`. Shipped for on-device testing ahead of a demo, with
one open question (see [the noise shortcut](#open-question-the-noise-shortcut)).

### Why

The pretrained AASIST-L flagged a real voice with music in the background as
synthetic on a Pixel 6a. The field report below describes that failure.
The pretrained model had only ever seen studio-clean ASVspoof 2019 LA audio.

### Setup

Pipeline: [`tools/finetune_aasist.py`](../tools/finetune_aasist.py). Method and
how to rerun it: [TRAINING.md](TRAINING.md).

| | |
|---|---|
| Starting point | Pretrained AASIST-L (`AASIST-L.pth`, clovaai/aasist) |
| Hardware | Kaggle, 1× Tesla T4, 4 data-loader workers |
| Epochs | 12 × 16,000 samples, batch 32 (500 steps/epoch) |
| Optimiser | Adam, lr 5e-5, weight decay 1e-4, cosine schedule |
| Sampling | Equal weight to each (dataset, label) group |
| Selection | Epoch with the lowest *worst-domain* dev EER |
| Seed | 1234 |
| Wall time | ~3 h 35 min (13.4 min training + ~2 min dev scoring per epoch) |

The run was data-loading bound: the GPU mostly waited on four CPU cores
decoding and augmenting audio from network-mounted datasets.

### Data splits

| Split | Clips | Bona fide | Spoof | Used for |
|---|---:|---:|---:|---|
| ASVspoof 2019 LA train | 25,380 | 2,580 | 22,800 | training |
| In-the-Wild train speakers | 22,379 | 13,457 | 8,922 | training |
| ASVspoof 2019 LA dev (sample) | 4,000 | 410 | 3,590 | model selection, calibration |
| In-the-Wild dev speakers | 3,605 | 2,069 | 1,536 | model selection, calibration |
| ASVspoof 2019 LA eval (sample) | 12,000 | 1,239 | 10,761 | test only |
| In-the-Wild test speakers | 5,795 | 4,437 | 1,358 | test only |
| Real voice clean / + music | 2,000 | 2,000 | 0 | test only |

- In-the-Wild is split **by speaker** (about 70/10/20 of clips), so no voice
  appears in both training and testing.
- ASVspoof 2019 LA eval contains attack systems A07–A19, none of which appear
  in training.
- MUSAN music is split **by file**: 528 files for augmentation, 132 held out for
  the "+ music" test. The model never trained with the music it was tested on.
- "Real voice + music" takes 2,000 held-out bona fide clips from LA eval and
  In-the-Wild test, mixed with held-out music at 10 dB SNR.

### Results

EER is the operating point where false alarms and misses are equal (lower is
better). False-alarm and miss rates are measured at each model's own threshold,
fixed on the pooled dev split: `-7.425` for the baseline, `-0.473` for the
fine-tuned model.

| Test set | Metric | Pretrained (as shipped before) | Fine-tuned (epoch 11) |
|---|---|---:|---:|
| In-the-Wild, unseen speakers | EER | 41.33% | **9.21%** |
| | false alarm | 6.94% | **4.01%** |
| | miss | 84.83% | **16.72%** |
| ASVspoof 2019 LA eval | EER | **1.14%** | 3.30% |
| | false alarm | **0.00%** | 0.40% |
| | miss | 40.79% | **23.06%** |
| Real voice, clean | false alarm | 5.45% | **2.70%** |
| Real voice + music | false alarm | 6.25% | **0.95%** |
| Pooled dev | EER | 33.10% | 3.64% |

What this says:

- **The generalisation failure was real, and fine-tuning largely fixed it on
  this data.** On speakers it never heard, the pretrained model was close to a
  coin flip (41% EER); the fine-tuned model is at 9%.
- **In-domain accuracy dropped slightly** (1.1% → 3.3% EER on ASVspoof 2019).
  Expected when training on broader data, and worth the trade.
- **Music no longer triggers false alarms on this data.** A real voice with
  music behind it is flagged 0.95% of the time, down from 6.25%.

What it does **not** say:

- **None of this is phone-microphone audio.** In-the-Wild is broadcast and
  online audio; ASVspoof 2019 is studio audio. The app hears the far end
  through a loudspeaker, a room and a phone mic. Only recordings made on the
  target phone measure that.
- **The pretrained model's false-alarm numbers don't reproduce the field
  report.** Its dev-set threshold (−7.4) is extremely lenient, so it rarely
  flags anything. The app decides with the 0.40 / 0.70 bands on a
  probability, which is a different operating point.

### Per-epoch dev EER

| Epoch | Train loss | LA dev EER | ITW dev EER | |
|---:|---:|---:|---:|---|
| 1 | 1.2872 | 1.75% | 11.21% | best |
| 2 | 0.5516 | 0.99% | 9.02% | best |
| 3 | 0.4946 | 0.74% | 5.13% | best |
| 4 | 0.4608 | 0.95% | 3.44% | best |
| 5 | 0.4322 | 0.96% | 3.55% | |
| 6 | 0.4178 | 1.04% | 2.38% | best |
| 7 | 0.4088 | 0.89% | 2.96% | |
| 8 | 0.4092 | 0.92% | 2.54% | |
| 9 | 0.3898 | 1.07% | 2.83% | |
| 10 | 0.3847 | 0.99% | 2.55% | |
| 11 | 0.3897 | 0.91% | **2.33%** | **selected** |
| 12 | 0.4021 | 0.93% | 2.41% | |

In-the-Wild dev EER plateaus from epoch 6, and LA dev stays around 1%. No sign
of overfitting over 12 epochs. The gap between dev (2.3%) and test (9.2%) on
In-the-Wild comes from speaker variation: different celebrities are harder or
easier. The test number is the honest one.

### Calibration

Logistic regression of the label on the countermeasure score, over the pooled
dev split (7,605 clips, about a third bona fide):

```
p(bona fide) = sigmoid(1.6442840 · cmScore + 0.3187831)
```

Wired in as `Calibration.BUNDLED_MODEL`, which lets the app show a percentage
instead of "—". Two caveats:

- **The probability reflects the dev split's class balance** (about 33% bona
  fide), not how often real callers are actually synthetic.
- **These numbers belong to this exact model file.** Swapping the asset without
  refitting makes them wrong.

The 0.40 / 0.70 risk bands are still the original placeholders, not derived
from a target false-positive rate.

### Spot check on the app's exact pipeline

Twelve clips from held-out In-the-Wild **test** speakers, scored off-device
with the same windowing, silence gate, 90th-percentile aggregation and
calibration as the app. Scored as the 16-bit PCM WAV files installed in
`Download/VoxShieldTest` on the test phone, and kept in
[`testdata/clips/`](../testdata/clips/) (converted from the dataset's 32-bit
float WAV, which moves the scores by at most 1.2 points).

| Clip | Length | Spoof probability | Band | Correct |
|---|---:|---:|---|---|
| fake 2Pac | 2.7 s | 99.6% | HIGH | ✓ |
| fake Arnold Schwarzenegger | 2.2 s | 99.5% | HIGH | ✓ |
| fake Orson Welles | 13.9 s | 97.2% | HIGH | ✓ |
| fake Martin Luther King | 6.5 s | 90.4% | HIGH | ✓ |
| fake Alan Watts | 1.4 s | 54.6% | MEDIUM | ✓ |
| real 2Pac | 8.1 s | 3.0% | LOW | ✓ |
| real Arnold Schwarzenegger | 1.7 s | 2.3% | LOW | ✓ |
| real Nick Offerman | 8.3 s | 4.7% | LOW | ✓ |
| real Martin Luther King | 2.0 s | 8.2% | LOW | ✓ |
| real Alan Watts | 16.3 s | 85.2% | HIGH | ✗ false alarm |
| fake Nick Offerman | 1.0 s | — | no speech | too short |
| real Orson Welles | 1.1 s | — | no speech | too short |

9 of 10 scored clips correct. Twelve clips is a sanity check, not an
accuracy estimate. The one false alarm is a vintage lecture recording. Clips
under about 1.35 s (a third of a window) get skipped rather than scored.

### Open question: the noise shortcut

A real voice with music is flagged *less* often (0.95%) than the same voice
clean (2.70%). That shouldn't happen. The augmentation mixes music into real and
fake clips equally, so music alone shouldn't push a score toward "real".

The likely explanation is a dataset shortcut, the kind the
[ASVspoof 5 paper](ASVSPOOF5.md#3-shortcut-artefacts) warns about: in
In-the-Wild, bona fide clips are often noisy real-world recordings while the
fakes tend to be cleaner. If the model learned "background noise means real",
then **a cloned voice played with music behind it could pass as real**.

This hasn't been measured, because the run never scored *fake* voices with
music added. The next eval-only run adds exactly that: "spoof + music miss
rate". Until then, treat music under a fake voice as a known-unknown.

### Next

1. Eval-only run: fake voice + held-out music, at several SNRs.
2. On-device test set: your own voice (clean, with music, and played back
   through a speaker to a second phone) and synthetic voices played through a
   speaker.
3. Run 2, applying the [ASVspoof 5 notes](ASVSPOOF5.md#what-this-changes-for-voxshield):
   ASVspoof 5 training data, silence trimming before cropping, and real codec
   augmentation (Opus, AMR-NB, Speex).
4. Pre-decode the audio once instead of on every epoch. Loading, not the GPU,
   was the bottleneck.

---

## 2026-09-30 — Field report: real voice + music flagged as AI

On a Pixel 6a, the app flagged the developer's own live voice as AI-generated,
with music playing in the background. It was still running the pretrained
AASIST-L at the time.

Three causes, most to least likely:

1. **Out-of-distribution audio.** ASVspoof 2019 LA contains no music, rooms or
   phone microphones. The export check had already shown the pretrained model
   scoring a pure 440 Hz tone as 1.0000 spoof, and tonal, harmonic sound like
   music pushes in the same direction.
2. **The capture path.** A phone mic, room reverb and Android's processing, none
   of which the model had seen.
3. **Uncalibrated scores against placeholder thresholds.** Anything moderately
   shifted landed in HIGH.

This triggered fine-tuning run 1 above. After fine-tuning, the same 440 Hz
tone scores 0.2378 spoof.

---

## 2026-09-30 — On-device latency benchmark

`OnDeviceBenchmarkTest` on a Pixel 6a (Tensor G1, Android 17 beta): plain CPU
kernels at 2 threads are fastest, at **302 ms median / 313 ms p95** per 4.04 s
window. XNNPACK at 2 threads, the previous default, took 877 ms / 1233 ms,
with p95 over the 1000 ms budget. NNAPI was over budget at every thread count.
The default is now CPU. Full sweep: [MODEL.md](MODEL.md#pixel-6a-full-sweep).

The fine-tuned model has the same architecture and input/output shapes, so it
runs at the same speed.
