# VoxShield — presenter's brief

Everything needed to present VoxShield: a talk track, a live demo script,
the numbers, what we took from the research, the limitations to state up
front, and answers to likely questions. Every figure here comes from
[EXPERIMENTS.md](EXPERIMENTS.md), [MODEL.md](MODEL.md) or
[ASVSPOOF5.md](ASVSPOOF5.md).

---

## The one-line pitch

> VoxShield is an Android app that checks, entirely on the phone, whether a
> voice is likely to be AI-generated — and it's honest about what it can and
> can't hear.

---

## Talk track (about 7 minutes)

### 1. The problem (45 s)

- Voice cloning no longer needs hours of someone's audio. The ASVspoof 5
  paper's hardest setting clones a voice from about 10–30 seconds of it,
  using *zero-shot* systems that need no retraining per victim.
- The scam is simple: a call that sounds like a family member or your boss,
  asking for money or a code, urgently.
- Good clones are hard to judge by ear. In the paper, a speech-quality model
  can barely separate some cloned voices from real ones: its error rate for
  YourTTS is about 56%, around coin-flip level. Detection needs to be automatic
  and instant, and it has to work without uploading anyone's private
  conversation.

### 2. What we built (1 min)

- **On-device detection.** A neural network scores audio for signs of
  synthesis, entirely on the phone. The app doesn't even request internet
  permission, so "no audio leaves the phone" can be checked in the manifest.
- **Two ways in:**
  - **Listen:** scores what the microphone hears, live, once a second.
  - **Upload:** scores an audio file, such as a voicemail or a forwarded
    voice note.
- **Call Protection:** a small pill that floats over any app, including the
  call screen. When a call starts it sends a notification; tap it to open the
  pill, and drag the pill to the bottom of the screen to put it away.
- **A clear verdict:** a risk gauge (LOW / MEDIUM / HIGH), a percentage, how
  much audio it was based on, and "verify the caller independently" on a HIGH
  result.

### 3. The model (1.5 min)

- **AASIST-L**, a published anti-spoofing network that reads the raw
  waveform and uses graph attention across time and frequency. 85,306
  parameters, a 1.15 MB file.
- **Fast enough for live use.** On a Pixel 6a it scores a 4-second window in
  **302 ms**, about 13× faster than real time. We measured every option:
  plain CPU beat XNNPACK by nearly 3×, and the NPU route was slowest of all.
- **We fine-tuned it ourselves**, because the off-the-shelf model failed our
  first real test (next section).

### 4. What went wrong, and how we fixed it (1.5 min)

This is the most convincing part of the story. Tell it straight.

- **The failure.** The published model scores 0.99% error on its benchmark.
  On the phone, it called my own voice AI because music was playing in the
  background.
- **The diagnosis.** That benchmark is studio-clean audio with no music, rooms
  or phones. Measured on real-world speakers it had never heard, the
  published model was at **41% error**, close to a coin flip.
- **The fix.** We fine-tuned it on real-world recordings (the In-the-Wild
  dataset) alongside the original data. During training we mixed in music,
  noise, room echo and phone-line distortion, equally for real and fake
  voices, so "sounds noisy" can't become the answer.
- **The discipline.** We tested only on speakers and music the model never
  saw in training, and scored the old model on the same tests for a fair
  comparison.

| On data never used in training | Before | After |
|---|---:|---:|
| Error rate, unseen real-world speakers | 41.3% | **9.2%** |
| Real voice + music wrongly flagged as AI | 6.3% | **1.0%** |
| Real voice (clean) wrongly flagged as AI | 5.5% | **2.7%** |
| Error rate, original lab benchmark | **1.1%** | 3.3% |

Say the last row out loud: we traded a little lab accuracy for a lot of
real-world accuracy, on purpose.

### 5. Grounded in current research (1 min)

We based the next steps on **ASVspoof 5** (Wang et al., *Computer Speech &
Language*, 2026), the latest edition of the main benchmark in this field. See
the [section below](#what-we-took-from-the-asvspoof-5-paper).

### 6. Limits (45 s)

State these before a reviewer asks. It builds more credibility than any
number. [Script below](#limitations-to-state-up-front).

### 7. What's next (30 s)

1. Measure on recordings from the phone itself.
2. Retrain with ASVspoof 5 data, real phone codecs, and silence trimming.
3. Test fake voices with background noise, to check the one suspected
   weakness in our own model.

---

## Live demo script

### Before you start

- Phone charged, Do Not Disturb on, brightness up, app already open.
- The disclaimer is already accepted, so you don't have to scroll it on
  stage. Mention it exists.
- Test clips are in **Downloads → VoxShieldTest**. They come from speakers the
  model never trained on. File names start with `real_` or `fake_`.
- A laptop or second phone with a speaker, for the live test. If a teammate
  has an Android phone, install the app on it too: a second phone is the only
  way to check a live call (see Limitations).
- **Rehearse the whole script at least once tonight on this phone.** The live
  mic path had not been validated on the device when this was written.
- Record a backup video tonight: `adb shell screenrecord /sdcard/demo.mp4`,
  stop with Ctrl+C, then `adb pull /sdcard/demo.mp4`. If anything fails live,
  switch to the video without apologising.

### The run (about 3 minutes)

1. **Upload a fake.** Tap Upload → `fake_Orson_Welles_7820.wav`. Expect
   **HIGH, ~97%**. Point at the verify-independently warning.
2. **Upload a real voice.** Tap Upload → `real_2Pac_31651.wav`. Expect
   **LOW, ~3%**.
3. **Live.** Play `fake_Martin_Luther_King_20609.wav` (6.5 s) from the laptop
   speaker next to the phone and tap Listen. The first score needs about
   4 seconds of audio. Then speak yourself and watch it drop.
4. **Point at the status card.** It shows the real per-window inference time,
   measured on this phone.
5. **Call Protection.** Turn on the switch and the pill appears. Open another
   app to show that it floats over everything, drag it around, then drag it
   onto the ✕ target at the bottom to hide it. Optionally place a call: a
   "Call detected" notification pops up, and tapping it brings the pill back
   over the call screen.
6. **If you show it during a call, say this out loud:** "Android mutes every
   app's microphone during a call, even on speakerphone. The pill tells you so
   — it says *Muted by Android* instead of pretending. To check a live caller,
   run it on a second phone next to the speaker."

Expected scores for steps 1 and 2 come from running the app's exact scoring
pipeline off-device ([EXPERIMENTS.md](EXPERIMENTS.md#spot-check-on-the-apps-exact-pipeline)).

### Don't do these on stage

- **Don't use `real_Alan_Watts_3146.wav`.** It's the one known false alarm
  (a vintage lecture recording, scored 85% fake).
- **Don't use the ~1-second clips** (`fake_Nick_Offerman_30344.wav`,
  `real_Orson_Welles_10864.wav`). They're too short to score and show "no
  speech detected".
- **Don't claim it hears a live phone call.** It can't; see below.
- **Don't play a fake voice over music** unless you've tried it first. That's
  the suspected weak spot.

---

## The model, in more depth

| | |
|---|---|
| Architecture | AASIST-L: sinc-filter front end on the raw waveform, a residual encoder, then heterogeneous graph attention across spectral and temporal features |
| Size | 85,306 parameters, 1.15 MB ONNX file |
| Input | 4.04 s of 16 kHz mono audio (64,600 samples) per window |
| Runtime | ONNX Runtime, plain CPU, 2 threads |
| Latency (Pixel 6a) | 302 ms median, 313 ms p95, against a 1,000 ms budget |
| Training | Fine-tuned 12 epochs on a Kaggle T4, about 3.5 hours ([TRAINING.md](TRAINING.md)) |
| Data | ASVspoof 2019 LA, In-the-Wild, MUSAN music/noise |
| Calibration | Platt scaling fitted on held-out data, so the percentage means something |

**How a verdict is formed:**
- The app scores 4-second windows, one per second. It skips silence, so it
  never scores near-empty audio.
- It combines the last ~15 windows at the **90th percentile**: one odd second
  can't raise an alarm, but a sustained run of suspicious audio does.

**Why not the phone's NPU?** We checked; there are three blockers.
- Google's Tensor NPU toolkit supports only the Pixel 10 family.
- ONNX Runtime has no path to the Tensor NPU.
- The one legacy route, NNAPI, was deprecated in Android 15, and it measured
  5–12× *slower* than the CPU on this phone.

The CPU already runs 3× inside the time budget.

---

## What we took from the ASVspoof 5 paper

> Wang et al., "ASVspoof 5: Design, collection and validation of resources for
> spoofing, deepfake, and adversarial attack detection using crowdsourced
> speech," *Computer Speech & Language* 95 (2026) 101825. Full notes:
> [ASVSPOOF5.md](ASVSPOOF5.md).

### What we adopted

1. **Realistic expectations.** In the paper, full AASIST trained on realistic
   data scores **29% error** on the ASVspoof 5 eval set. The famous
   sub-1% numbers come from studio data. That's why we treat our own 9% as
   progress, not a finish line.
2. **Do your own augmentation.** The ASVspoof 5 training set deliberately has
   no codecs applied, leaving augmentation to the user. We augment with music,
   noise, reverb and phone-line distortion.
3. **Watch for shortcut artefacts.** Detectors can learn cues unrelated to
   fakeness — loudness peaks, silence length, clip duration. We randomise gain
   and crop position, apply augmentation equally to both classes, and are
   checking whether our model learned "background noise means real".
4. **Evaluation discipline.** Speaker-disjoint splits and attacks unseen in
   training. We hold out entire speakers and entire music files.
5. **Honest thresholds.** The paper states that choosing a decision threshold
   in advance is unsolved. We say the same about our 0.40 / 0.70 bands.
6. **A codec target list** for the next run: Opus, AMR and Speex at 8 kHz,
   plus the paper's real calling-pipeline condition (C11) as our phone-call
   benchmark.

### Where the paper falls short for our use case

The paper is a benchmark for the research community. Measured against a live,
on-phone call-protection app, it has gaps:

- **No acoustic replay.** Condition C11 simulates the phone *channel* by
  applying measured calling-pipeline responses. It doesn't cover what our app
  actually faces: the other person's voice played through a loudspeaker,
  across a room, into a phone mic.
- **Whole utterances only.** Its clips are 4–10 s utterances and the baselines
  take a fixed 4 s input. Streaming, second-by-second decisions on continuous
  audio aren't studied.
- **No on-device constraints.** Latency, battery and model size aren't
  considered at all. Those are our main engineering constraints.
- **No architecture comparison.** Only two baselines (RawNet2 and AASIST) are
  reported, so it doesn't tell us which model to use. The challenge-results
  paper that would is still "in preparation".
- **English only.** ASVspoof 5 is built from the English portion of
  Multilingual LibriSpeech.
- **Thresholds left open.** Headline numbers use oracle thresholds chosen with
  the answers in hand. A deployed app can't.

### Gaps it exposes in the field, and what they mean for us

- **Old techniques beat modern detectors.** Legacy unit-selection synthesis
  (A12, A19) pushes detectors to 60–79% error, worse than chance, even though
  it's easy to spot by perceptual quality. We don't train against that kind
  of attack yet.
- **Attacks aimed at our model family already exist.** The Malafide adversarial
  filters were optimised against AASIST and push its error from about 16% to
  50%. A determined attacker who knows the model can target it.
- **Codecs hurt everyone.** Every baseline gets worse with compression, which is
  why real codec augmentation is next on our list.

---

## Limitations to state up front

1. **The phone on the call can't check that call — no app can.** Android
   mutes the microphone for every third-party app for the whole call, **even on
   speakerphone**. We measured this on our Pixel 6a. The system reported our
   capture as `silenced` during a speakerphone call. It works on a **second
   phone** listening to the call's speaker, and on voicemails and recordings
   through Upload. The app reads that silencing directly and shows "Muted by
   Android" instead of pretending.
2. **It hasn't been measured on phone-mic audio yet.** The 9.2% figure comes
   from real-world broadcast and online audio, not from a phone listening to a
   speaker. That measurement is our next step.
3. **The risk bands are provisional.** The percentage is calibrated; the
   LOW / MEDIUM / HIGH cut-offs aren't yet tuned to a target false-alarm rate.
4. **One suspected weakness in our own model.** Real voices with music got
   *fewer* false alarms than clean ones, which hints the model may treat
   background noise as a sign of "real". A fake voice over music could slip
   through. We found this ourselves and the test is designed; it hasn't run
   yet.
5. **It's a signal, not proof.** The app always tells you to verify
   independently. It isn't evidence and isn't a lie detector.

---

## Likely questions

**"Does it work during a normal phone call?"**
Not on the phone taking the call, and no third-party app can do that: Android
mutes every app's microphone for the whole call. We checked speakerphone too;
it's muted there as well. Run VoxShield on a second phone next to the call's
speaker, or check the recording afterwards. The app detects the muting and
says so on screen, so users aren't misled.

**"How accurate is it?"**
9.2% equal error rate on real-world speakers it never trained on, down from
41% for the published model. On a phone microphone we haven't measured yet;
that's the next step, and we won't quote a number we don't have.

**"Why not send the audio to a big model in the cloud?"**
Privacy, latency, and it works offline. Uploading someone's private call to a
server is a harder thing to justify than running an 85K-parameter model
locally. The app doesn't even request internet access.

**"Why this model and not a bigger one?"**
It fits the budget: 302 ms per 4-second window on a 2022 mid-range phone. Much
bigger speech models generalise better but are too heavy for live scoring on
this hardware. With a proper phone-condition test set, we'd evaluate stronger
models next.

**"Couldn't an attacker beat it?"**
Yes, and the research shows how: adversarial filters built specifically
against AASIST exist. No detector is a guarantee. That's why the app's advice
is always to verify independently, and why we plan to retrain on newer attacks.

**"What about false alarms on real people?"**
2.7% on clean real voices and 1.0% with music, at the threshold we tuned on
held-out data, on test data. On the phone we've seen one false alarm in our
spot check, on a vintage lecture recording. A false HIGH is costly — it tells
someone their relative is an impersonator — so tuning thresholds for a very
low false-alarm rate is on the roadmap.

**"Is recording calls legal?"**
It depends on where you and the other person are. Many places require
everyone's consent. The Call Protection feature has its own consent screen
stating this, and it requires an explicit acknowledgement before it can be
turned on.

**"Why is the lab score worse after fine-tuning?"**
It went from 1.1% to 3.3% on the studio benchmark while real-world error went
from 41% to 9%. A model that's perfect in the lab and a coin flip in the real
world is the worse model for an app.

---

## Pre-demo checklist

- [ ] Phone reconnected: `adb connect <ip>:<port>` (the port changes after
      sleep or Wi-Fi changes)
- [ ] Latest build installed: `./gradlew :app:installDebug`
- [ ] App opened once and both disclaimers re-accepted (their text changed, so
      earlier acceptances were reset — don't let this happen on stage)
- [ ] Call Protection switched on, notification permission allowed
- [ ] `Downloads/VoxShieldTest` still has the 12 clips
- [ ] Full demo script rehearsed on the phone, including the live Listen step
- [ ] Tried a fake clip with music behind it, to know in advance how it behaves
- [ ] Backup screen recording saved
- [ ] Do Not Disturb on, charger nearby
