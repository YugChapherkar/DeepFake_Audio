# Training the detector

How the bundled model is produced from the public AASIST-L checkpoint, and how
to rerun or change it. Results of each run go in
[EXPERIMENTS.md](EXPERIMENTS.md).

## Pipeline at a glance

```
clovaai/aasist AASIST-L.pth
        │
        ▼
tools/finetune_aasist.py          (Kaggle GPU, ~3.5 h on a T4)
  ├─ scores the starting checkpoint on every test set (baseline)
  ├─ fine-tunes 12 epochs with augmentation
  ├─ keeps the epoch with the best worst-domain dev EER → best.pth
  ├─ scores best.pth on the same test sets
  └─ fits Platt calibration on the dev split → metrics.json
        │
        ▼
tools/export_aasist_onnx.py --weights best.pth
  └─ ONNX export + PyTorch parity check → aasist_l.onnx
        │
        ▼
app/src/main/assets/aasist_l.onnx  +  Calibration.BUNDLED_MODEL in RiskEngine.kt
```

## Data

All three datasets are public and attached to the Kaggle job directly, so
nothing gets downloaded locally.

| Dataset | Kaggle source | Role |
|---|---|---|
| ASVspoof 2019 LA | `awsaf49/asvpoof-2019-dataset` | train / dev / eval (LA only) |
| In-the-Wild | `abdallamohamed312/in-the-wild-audio-deepfake` | train / dev / test, split by speaker |
| MUSAN | `dogrose/musan-dataset` | music and noise for augmentation, plus the held-out music test |

In-the-Wild ships `meta.csv` (`file,speaker,label`, labels `bona-fide` /
`spoof`) with audio under `release_in_the_wild/{real,fake}/`. It has 31,779 clips
from 54 speakers. The speaker split is deterministic given the seed: speakers
are shuffled with `random.Random(seed)` and assigned to train / dev / test until
70% / 80% of all clips are covered.

The script finds each dataset by searching `--input-root` for a known
directory, depth-limited so it never walks the audio folders:

- **ASVspoof:** the directory containing `ASVspoof2019_LA_cm_protocols`
- **In-the-Wild:** `meta.csv`, with `release_in_the_wild/` beside it
- **MUSAN:** a `music/` directory that has a `noise/` directory next to it.
  MUSAN mirrors nest the corpus under a same-named folder, so matching on the
  name `musan` alone picks the wrong one.

On Kaggle, inputs currently mount at `/kaggle/input/datasets/<owner>/<slug>/`.

Before training, the script checks that every listed audio file exists and
aborts if any are missing. The loaders fall back to silence on a decode error,
so without that check a path mismatch would silently train on nothing.

## Augmentation

Applied on the fly to each 64,600-sample training crop, **identically for bona
fide and spoof**. That symmetry is the point: if augmentation touched only one
class, "sounds augmented" would become a shortcut for the label.

| Step | Probability | Parameters |
|---|---:|---|
| Room reverberation | 25% | Synthetic RIR: exponentially decaying noise, RT60 0.2–0.9 s, dominant direct path, energy-matched to the input |
| Background | 50% | MUSAN music (60%) or noise (40%), SNR 0–20 dB, skipped on near-silent crops |
| Telephone channel | 25% | 16 → 8 → 16 kHz band-limit, then 8-bit µ-law quantisation |
| Gain | always | ±6 dB, then peak-normalised only if it would clip |

Crops are random (clips are tiled if shorter than 4.04 s), matching the
reference AASIST training loader. Dev and test clips instead use the first
64,600 samples, which matches both the reference evaluation and how the app
scores a window.

## Sampling and selection

- **Balanced sampling:** each epoch draws 16,000 samples with replacement,
  giving each of the four (dataset, label) groups equal total weight. Otherwise
  ASVspoof's 9:1 spoof imbalance, and its sheer size, would dominate.
- **Model selection:** keep the epoch with the lowest `max(LA dev EER, ITW dev
  EER)`. Selecting on the pooled or average EER would let a gain on one domain
  hide a collapse on the other.
- **Training detail:** AASIST's own `Freq_aug` sinc-filter masking is on during
  training and off for every evaluation.

## Test sets

Everything reported is on data that took no part in training or selection:

- **ASVspoof 2019 LA eval** — a 12,000-clip stratified sample, attacks A07–A19
- **In-the-Wild test speakers** — all their clips
- **Real voice clean / + music** — 2,000 bona fide clips drawn from the two
  above; the "+ music" copy mixes in held-out MUSAN music at 10 dB SNR

The pretrained checkpoint is scored on the same sets first, so every report is
a before/after comparison.

## Running it

### Local smoke test (CPU, a few minutes)

```bash
python -m venv .venv && . .venv/bin/activate
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install numpy scipy soundfile scikit-learn
VOXSHIELD_SMOKE=1 python tools/finetune_aasist.py --input-root <fixture-dir> --out /tmp/smoke
```

`VOXSHIELD_SMOKE=1` shrinks everything to a handful of clips, one epoch and no
worker processes. `<fixture-dir>` needs the same layout as the Kaggle inputs,
with a few audio files per folder plus the three AASIST source files. The
fixture used during development was plain random noise. Its numbers mean
nothing; the run only proves the pipeline executes end to end. This smoke test
caught a MUSAN-lookup bug that would have crashed the first Kaggle run.

### On Kaggle

1. **Upload the model source as a private dataset.** Include `AASIST.py`,
   `AASIST-L.conf` and `AASIST-L.pth` from clovaai/aasist, plus its `LICENSE`.
   The job runs without internet, so it can't clone the repo itself.

   ```bash
   kaggle datasets create -p <dir-with-those-files-and-dataset-metadata.json>
   ```

2. **Push the script as a GPU kernel**, with this `kernel-metadata.json` beside
   `finetune_aasist.py`:

   ```json
   {
     "id": "<your-kaggle-username>/voxshield-aasist-finetune",
     "title": "voxshield-aasist-finetune",
     "code_file": "finetune_aasist.py",
     "language": "python",
     "kernel_type": "script",
     "is_private": true,
     "enable_gpu": true,
     "enable_internet": false,
     "dataset_sources": [
       "<your-kaggle-username>/voxshield-aasist-src",
       "awsaf49/asvpoof-2019-dataset",
       "abdallamohamed312/in-the-wild-audio-deepfake",
       "dogrose/musan-dataset"
     ]
   }
   ```

   ```bash
   kaggle kernels push -p <kernel-dir>
   kaggle kernels status <your-kaggle-username>/voxshield-aasist-finetune
   ```

3. **Collect outputs** when the status reads `COMPLETE`. Kaggle releases output
   files only after the job ends; while it runs, the live log is visible only on
   the kernel's web page.

   ```bash
   kaggle kernels output <your-kaggle-username>/voxshield-aasist-finetune -p out/
   ```

   Outputs:
   - `best.pth` — the selected epoch
   - `last.pth` — the final epoch
   - `metrics.json` — config, baseline and fine-tuned results, per-epoch dev
     scores, calibration
   - `train_log.txt`

   `metrics.json` and `last.pth` are rewritten after every epoch, so a job cut
   off by Kaggle's 12-hour limit still leaves something usable.

### Shipping a result

```bash
python tools/export_aasist_onnx.py \
    --aasist-repo /path/to/aasist \
    --weights out/best.pth \
    --out app/src/main/assets/aasist_l.onnx
```

The export must print `parity OK`. Then copy `finetuned.calibration.slope` and
`.intercept` from `metrics.json` into `Calibration.BUNDLED_MODEL` in
`RiskEngine.kt`. **The calibration belongs to one exact model file:** ship them
together or not at all. Record the run in [EXPERIMENTS.md](EXPERIMENTS.md).

## Known limits of this pipeline

- **Slow data loading.** About 1,400 clips per minute on 4 CPU cores. Every
  epoch re-decodes audio from network storage; caching decoded audio once would
  make runs several times faster.
- **Crude codec simulation.** Band-limiting plus µ-law is only a stand-in for
  a real phone line. Real Opus / AMR / Speex encoding (via ffmpeg) is the next
  step. See [ASVSPOOF5.md](ASVSPOOF5.md).
- **Silence isn't trimmed** before cropping, so leading and trailing silence
  can still act as a shortcut.
- **Linear resampling.** 22.05 kHz / 44.1 kHz sources are resampled with
  `resample_poly`, which is fine here. On-device file upload still uses a
  linear resampler (see `AudioDecoder.kt`).
