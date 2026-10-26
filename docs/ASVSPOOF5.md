# Notes: the ASVspoof 5 paper

> X. Wang et al., "ASVspoof 5: Design, collection and validation of resources for
> spoofing, deepfake, and adversarial attack detection using crowdsourced
> speech," *Computer Speech & Language* 95 (2026) 101825.
> doi:[10.1016/j.csl.2025.101825](https://doi.org/10.1016/j.csl.2025.101825).
> Open access, CC BY 4.0. A local copy is at `docs/paper.pdf`.

A **dataset paper**, not a model paper: it introduces the ASVspoof 5 corpus and
validates it with baseline systems. It doesn't propose a better detector.
What it offers VoxShield is better training and test data, a list of traps,
and a realistic picture of how our architecture holds up.

## Key points

### 1. It sets realistic expectations for AASIST

The paper's countermeasure baselines are RawNet2 (B01) and full **AASIST**
(B02), a larger sibling of our AASIST-L. Trained from scratch on ASVspoof 5
(Table 5, Table D.7), B02 scores:

| ASVspoof 5 set | B02 (AASIST) pooled EER |
|---|---:|
| Development | 17.83% |
| Evaluation, no codec | 25.28% |
| Evaluation, with codecs | 30.61% |
| Evaluation, pooled | 29.12% |

Compare 0.83% on ASVspoof 2019 LA, the figure AASIST is usually quoted with.
On realistic data, this architecture family is far from reliable. Our own
fine-tuning result (9.2% EER on held-out In-the-Wild speakers,
[EXPERIMENTS.md](EXPERIMENTS.md)) is on easier data than this and shouldn't be
read as "solved".

### 2. The corpus fits VoxShield's problem much better

| | ASVspoof 2019 LA (what we train on) | ASVspoof 5 |
|---|---|---|
| Source | VCTK: studio, hemi-anechoic chamber | MLS English: LibriVox volunteers at home on their own gear |
| Speakers | ~100 | ~2,000 in the main sets (about 4,700 in MLS overall) |
| Attacks | A01–A19 | 32 (A01–A32), crowdsourced from TTS/VC experts |
| Modern zero-shot TTS | no | yes, incl. YourTTS, XTTS, ToucanTTS, ZMM-TTS |
| Adversarial attacks | no | 7, first time in the series |
| Codecs | no | eval set: Opus, AMR, Speex, Encodec, MP3, M4A, MP3+Encodec at 16 kHz; Opus, AMR, Speex at 8 kHz |
| Real calling pipelines | no | condition C11, see below |

**Condition C11** is the closest public stand-in for phone calls. Audio is run
through the measured response of eight end-to-end calling pipelines (Appendix
A, Table A.6):

- Microsoft Teams on a PC, with and without noise cancellation
- Smartphones — Poco Phone 4 5G, Redmi Note 8 Pro, Samsung Galaxy A12 and
  Galaxy S23 Ultra — injected over Bluetooth or an analog cable, into a call
  centre over the public phone network

Sizes (Section 2.2):

- **Training:** 400 speakers, about 19k bona fide and 164k spoofed utterances,
  8 attacks (A01–A08, all zero-shot TTS)
- **Development:** about 31k bona fide (target plus non-target) and 110k
  spoofed utterances, attacks A09–A16
- **Evaluation:** about 139k bona fide and 542k spoofed utterances, 16 attacks
  (A17–A32)

Every partition is speaker-disjoint from the others. The **training set gets no
codecs** on purpose, leaving augmentation to users — which is exactly what
`finetune_aasist.py` does.

### 3. Shortcut artefacts

A **shortcut artefact** is a cue unrelated to whether speech is synthetic that a
detector learns anyway, because the dataset happens to correlate it with the
label (Section 4.2). The paper names five:

- **Peak amplitude:** several TTS systems normalise output peaks to exactly
  1.0 (A17, A25).
- **Leading silence duration**
- **Trailing silence duration**
- **Total utterance duration:** set by the TTS acoustic model, not the speaker
- **Average energy**

A detector that leans on these scores well in the lab and "may degrade
catastrophically" in deployment. ASVspoof 2019-trained systems are known to
lean on leading and trailing silence (Müller et al., 2021, "Speech is silver,
silence is golden"). ASVspoof 5 post-processes its dev and eval sets to remove
these cues, but not its training set, to push researchers toward robust
features.

**Why this matters here:**

- **The pretrained model.** It was trained on ASVspoof 2019, so it plausibly
  relies on silence at clip boundaries. The app's live windows are cut from
  continuous mic audio and have no such boundaries. This is our inference, not
  something the paper tests, but it fits the field report.
- **Our fine-tuned model.** It shows a new suspected shortcut, background noise
  meaning "real", inherited from In-the-Wild. See
  [the open question](EXPERIMENTS.md#open-question-the-noise-shortcut).
- **What the training pipeline covers.** Random gain covers energy, random
  crops partly cover duration, but **silence isn't trimmed** yet.

### 4. Old techniques and adversarial filters are the hardest attacks

- **Unit selection (A12, A19).** These legacy concatenative systems build
  speech from snippets of real recordings. The paper finds them easy to spot
  by perceptual quality (A12's MOS EER is 1.82%), yet they push both CM
  baselines to **60–79% EER**. That's worse than chance: the detectors rate the
  fakes as more genuine than real speech (Table 5, A12: 78.86% / 78.90%; A19:
  63.75% / 59.99%). The paper stresses that the most effective attacks aren't
  necessarily the newest.
- **Malafide (A18, A20, A23).** Adversarial filters optimised against an
  **AASIST** CM. For B01, A18 raises EER from A17's 22.58% to 57.64%; for B02,
  from 16.44% to 50.02%.

For a security product, this means anyone who knows we ship AASIST can
optimise against it. Surrogate-model tuning of attacks (Section 6, Fig. 8)
shows attackers do this in practice.

### 5. Thresholds remain an open problem

EER and min a-DCF both use an **oracle threshold**, chosen with the test labels.
The paper states that thresholds have to be fixed in advance in deployment, and
that doing so for new attacks and coded audio is unsolved (Section 7.4).
VoxShield's `RiskThresholds.PLACEHOLDER` (0.40 / 0.70) is exactly that
unsolved problem.

### 6. A hint worth following

Attacks with low perceptual quality (low MOS EER) can still be hard for CMs:
unit selection and the adversarial attacks. The authors suggest that combining
quality estimates with conventional CMs "may lead to more generalisable and
reliable detection techniques" (Section 7.5).

## What this changes for VoxShield

In priority order:

1. **Train on ASVspoof 5 as well.** Its training set is the best-matched public
   data for home and phone recording conditions and modern cloning.
2. **Evaluate on the ASVspoof 5 eval set, split by codec,** and report C11
   separately as the phone-call number.
3. **Trim leading and trailing silence** before random cropping, and
   randomise peak level. This matches the paper's own post-processing: peak to
   1.0, trimming, random 4–10 s crops.
4. **Replace µ-law with real codecs** in augmentation: Opus, AMR-NB and Speex at
   8 kHz, the conditions C08–C10 test.
5. **Test with fake voices plus background noise,** to check the shortcut
   suspected in run 1.
6. **Reconsider the architecture** once a phone-condition eval exists. The
   paper doesn't compare architectures (full AASIST sits near 29% EER here, so
   there's headroom), so that call needs the challenge-results paper it
   announces as "in preparation".

## Getting the data

- **Official:** Zenodo, [doi:10.5281/zenodo.14498691](https://doi.org/10.5281/zenodo.14498691).
  Attack-generation protocols are withheld for ethical reasons (Section 2.3).
- **Kaggle community mirrors** (as of 2026-09-30, not yet checked for
  completeness or protocol files):
  - `trungkinenguyn/asvspoof5-t` — 37 GB, apparently the training set
  - `trungkinenguyn/asvspoof5-d` — 20 GB, apparently the development set
  - `aniket202411001/asvspoof5-flac` — 22 GB

  Before relying on a mirror, check that the protocol and label files are
  present and that clip counts match Section 2.2.
