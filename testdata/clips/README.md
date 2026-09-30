# Test clips

Twelve short clips, six real and six AI-generated, for trying VoxShield by hand
and for the live demo. File names say which is which: `real_<speaker>_<id>.wav`
or `fake_<speaker>_<id>.wav`.

## Source

Taken from the **In-the-Wild** audio deepfake dataset (N. Müller et al., "Does
Audio Deepfake Detection Generalize?", Interspeech 2022), via the Kaggle
mirror `abdallamohamed312/in-the-wild-audio-deepfake`, which lists its licence
as Apache-2.0. `<id>` is the clip's file number in that dataset.

All six speakers are from the fine-tuning run's **held-out test split**, so the
bundled model never trained on any of these voices. The split is the one
`tools/finetune_aasist.py` computes with seed 1234. Converted from the
dataset's 32-bit float WAV to 16 kHz mono 16-bit PCM, which changes the model's
scores by at most 1.2 points.

## Expected results

Scored off-device with the app's exact pipeline (windowing, silence gate,
90th-percentile aggregation, calibration) and the bundled model:

| Clip | Length | Spoof probability | Band |
|---|---:|---:|---|
| `fake_2Pac_18213.wav` | 2.7 s | 99.6% | HIGH ✓ |
| `fake_Arnold_Schwarzenegger_2213.wav` | 2.2 s | 99.5% | HIGH ✓ |
| `fake_Orson_Welles_7820.wav` | 13.9 s | 97.2% | HIGH ✓ |
| `fake_Martin_Luther_King_20609.wav` | 6.5 s | 90.4% | HIGH ✓ |
| `fake_Alan_Watts_3989.wav` | 1.4 s | 54.6% | MEDIUM ✓ |
| `real_2Pac_31651.wav` | 8.1 s | 3.0% | LOW ✓ |
| `real_Arnold_Schwarzenegger_20268.wav` | 1.7 s | 2.3% | LOW ✓ |
| `real_Nick_Offerman_20348.wav` | 8.3 s | 4.7% | LOW ✓ |
| `real_Martin_Luther_King_5815.wav` | 2.0 s | 8.2% | LOW ✓ |
| `real_Alan_Watts_3146.wav` | 16.3 s | 85.2% | HIGH ✗ false alarm |
| `fake_Nick_Offerman_30344.wav` | 1.0 s | — | too short to score |
| `real_Orson_Welles_10864.wav` | 1.1 s | — | too short to score |

Twelve clips are a sanity check, not an accuracy measurement. See
[docs/EXPERIMENTS.md](../../docs/EXPERIMENTS.md) for the real numbers.

## Using them

- **In the app:** push them to the phone, then pick one with **Upload**.

  ```bash
  adb shell mkdir -p /sdcard/Download/VoxShieldTest
  adb push testdata/clips/. /sdcard/Download/VoxShieldTest/
  ```

- **Live, through the microphone:** play a clip from the laptop next to the
  phone and tap **Listen**. The first score needs about 4 seconds of audio, so
  use the longer clips: `fake_Orson_Welles`, `fake_Martin_Luther_King`,
  `real_2Pac`, `real_Nick_Offerman`.

  ```bash
  paplay testdata/clips/fake_Martin_Luther_King_20609.wav
  ```

- **For a demo:** skip `real_Alan_Watts_3146.wav` (the one false alarm) and the
  two clips of about 1 second.
