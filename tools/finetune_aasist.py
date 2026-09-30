#!/usr/bin/env python3
"""Fine-tune AASIST-L for real-world phone audio, and measure whether it helped.

The pretrained AASIST-L checkpoint was trained on ASVspoof 2019 LA: clean,
studio-quality speech with no music, room, or phone in the path. On a Pixel 6a
it flagged a real voice with music playing behind it as synthetic. This script
attacks that failure directly:

  * Training data: ASVspoof 2019 LA plus the In-the-Wild dataset (real public
    figures, real recording conditions).
  * Augmentation, applied identically to bona fide and spoof so that "sounds
    augmented" can never become a shortcut for "is fake":
      - background music / noise from MUSAN at 0-20 dB SNR
      - synthetic room reverberation (RT60 0.2-0.9 s)
      - telephone band-limiting (8 kHz) plus 8-bit mu-law quantisation
      - random gain
  * Evaluation on data the model never trains on:
      - ASVspoof 2019 LA eval (attack systems A07-A19, unseen in training)
      - In-the-Wild, held out by *speaker*, so no voice appears in both
      - "real voice + music": held-out bona fide clips mixed with held-out
        MUSAN music. Its false-alarm rate is the number that answers "does it
        still call my voice fake when music is playing?"
  * Every metric is computed for the original pretrained weights too, so the
    report says whether fine-tuning helped instead of just printing a number.
  * Fits Platt calibration on the dev split, giving the app coefficients for
    `Calibration(fitted = true)`.

Outputs (to --out): best.pth, metrics.json, train_log.txt.

Designed for a Kaggle GPU kernel with these datasets attached:
  awsaf49/asvpoof-2019-dataset
  abdallamohamed312/in-the-wild-audio-deepfake
  dogrose/musan-dataset
plus a small dataset holding AASIST.py, AASIST-L.conf and AASIST-L.pth from
https://github.com/clovaai/aasist (MIT, (c) NAVER Corp.).

Set VOXSHIELD_SMOKE=1 to run a few tiny steps (used to test on CPU).
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import math
import os
import random
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
import torch.nn as nn
from scipy.signal import fftconvolve, resample_poly
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler

SR = 16_000
NB_SAMP = 64_600          # AASIST training crop (~4.04 s)
BONAFIDE, SPOOF = 1, 0    # AASIST label convention (aasist/data_utils.py)
SMOKE = os.environ.get("VOXSHIELD_SMOKE") == "1"

LOG_LINES: list[str] = []


def log(msg: str) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    LOG_LINES.append(line)


# --------------------------------------------------------------------------
# Locating inputs
# --------------------------------------------------------------------------

def find_dir(root: Path, name: str, max_depth: int = 5, accept=lambda p: True) -> Path:
    """Breadth-first search for a directory called `name` (that also satisfies
    `accept`), depth-limited so it never walks the hundreds of thousands of
    audio files inside datasets."""
    frontier = [root]
    for _ in range(max_depth + 1):
        nxt = []
        for d in frontier:
            try:
                entries = list(os.scandir(d))
            except OSError:
                continue
            for e in entries:
                if e.is_dir(follow_symlinks=False) or e.is_dir():
                    p = Path(e.path)
                    if e.name == name and accept(p):
                        return p
                    # Skip leaf audio folders; they only contain files.
                    if e.name not in ("flac", "wav", "fake", "real"):
                        nxt.append(p)
        frontier = nxt
    raise SystemExit(f"could not find a directory named {name!r} under {root}")


def find_file(root: Path, name: str, max_depth: int = 4) -> Path:
    frontier = [root]
    for _ in range(max_depth + 1):
        nxt = []
        for d in frontier:
            try:
                entries = list(os.scandir(d))
            except OSError:
                continue
            for e in entries:
                if e.is_file() and e.name == name:
                    return Path(e.path)
                if e.is_dir() and e.name not in ("flac", "wav", "fake", "real"):
                    nxt.append(Path(e.path))
        frontier = nxt
    raise SystemExit(f"could not find a file named {name!r} under {root}")


# --------------------------------------------------------------------------
# Data
# --------------------------------------------------------------------------

def load_audio(path: str, start: int = 0, frames: int = -1) -> np.ndarray:
    x, sr = sf.read(path, start=start, frames=frames, dtype="float32", always_2d=False)
    if x.ndim > 1:
        x = x.mean(axis=1)
    if sr != SR:
        g = math.gcd(sr, SR)
        x = resample_poly(x, SR // g, sr // g).astype(np.float32)
    return x


def tile_to(x: np.ndarray, n: int) -> np.ndarray:
    """Same as aasist/data_utils.pad: repeat short clips, never zero-fill."""
    if len(x) >= n:
        return x[:n]
    reps = n // max(len(x), 1) + 1
    return np.tile(x, reps)[:n]


def random_crop(x: np.ndarray, n: int, rng: np.random.Generator) -> np.ndarray:
    if len(x) > n:
        s = int(rng.integers(0, len(x) - n))
        return x[s:s + n]
    return tile_to(x, n)


def parse_asvspoof(la_root: Path, split: str) -> list[tuple[str, int, str]]:
    proto_dir = la_root / "ASVspoof2019_LA_cm_protocols"
    fname = {"train": "ASVspoof2019.LA.cm.train.trn.txt",
             "dev": "ASVspoof2019.LA.cm.dev.trl.txt",
             "eval": "ASVspoof2019.LA.cm.eval.trl.txt"}[split]
    audio_dir = la_root / f"ASVspoof2019_LA_{split}" / "flac"
    items = []
    for line in (proto_dir / fname).read_text().splitlines():
        parts = line.split()
        if len(parts) < 5:
            continue
        _, utt, _, attack, key = parts[:5]
        label = BONAFIDE if key == "bonafide" else SPOOF
        items.append((str(audio_dir / f"{utt}.flac"), label, "la"))
    return items


def parse_itw(itw_root: Path, meta: Path, seed: int) -> dict[str, list[tuple[str, int, str]]]:
    """Splits In-the-Wild by speaker (~70/10/20 of clips) so no voice appears in
    both training and testing."""
    rows = list(csv.DictReader(meta.open()))
    by_speaker: dict[str, list[tuple[str, int, str]]] = {}
    for r in rows:
        label = BONAFIDE if r["label"].strip().lower() in ("bona-fide", "bonafide") else SPOOF
        sub = "real" if label == BONAFIDE else "fake"
        path = itw_root / sub / r["file"]
        by_speaker.setdefault(r["speaker"], []).append((str(path), label, "itw"))

    speakers = sorted(by_speaker)
    random.Random(seed).shuffle(speakers)
    total = sum(len(v) for v in by_speaker.values())
    splits = {"train": [], "dev": [], "test": []}
    used = 0
    for spk in speakers:
        frac = used / total
        key = "train" if frac < 0.70 else ("dev" if frac < 0.80 else "test")
        splits[key].extend(by_speaker[spk])
        used += len(by_speaker[spk])
    return splits


def stratified_sample(items, n, seed):
    if n <= 0 or n >= len(items):
        return list(items)
    rng = random.Random(seed)
    bona = [i for i in items if i[1] == BONAFIDE]
    spoof = [i for i in items if i[1] == SPOOF]
    nb = round(n * len(bona) / len(items))
    return rng.sample(bona, min(nb, len(bona))) + rng.sample(spoof, min(n - nb, len(spoof)))


class BackgroundPool:
    """Random fixed-length segments from a set of long MUSAN recordings, read
    by seeking rather than decoding whole files."""

    def __init__(self, files: list[str]):
        self.files = []
        for f in files:
            try:
                info = sf.info(f)
            except RuntimeError:
                continue
            if info.frames > SR // 2:
                self.files.append((f, info.frames, info.samplerate))
        if not self.files:
            raise SystemExit("background pool is empty")

    def segment(self, n: int, rng: np.random.Generator) -> np.ndarray:
        f, frames, sr = self.files[int(rng.integers(0, len(self.files)))]
        need = int(math.ceil(n * sr / SR)) + 16
        start = int(rng.integers(0, max(frames - need, 1)))
        return tile_to(load_audio(f, start=start, frames=need), n)


def add_background(x, bg, snr_db):
    px = float(np.mean(x ** 2)) + 1e-10
    pn = float(np.mean(bg ** 2)) + 1e-10
    return x + bg * math.sqrt(px / (pn * 10 ** (snr_db / 10)))


def reverberate(x, rng):
    rt60 = float(rng.uniform(0.2, 0.9))
    n = int(rt60 * SR)
    t = np.arange(n) / SR
    h = rng.standard_normal(n).astype(np.float32) * np.exp(-6.9 * t / rt60).astype(np.float32)
    h[0] = abs(h).max() * 3.0            # direct path dominates
    h /= np.sqrt(np.sum(h ** 2)) + 1e-10
    y = fftconvolve(x, h)[: len(x)].astype(np.float32)
    return y * (np.sqrt(np.mean(x ** 2)) / (np.sqrt(np.mean(y ** 2)) + 1e-10))


def telephone(x):
    y = resample_poly(resample_poly(x, 1, 2), 2, 1)[: len(x)].astype(np.float32)
    mu = 255.0
    y = np.clip(y, -1, 1)
    q = np.sign(y) * np.log1p(mu * np.abs(y)) / np.log1p(mu)
    q = np.round((q + 1) / 2 * mu) / mu * 2 - 1
    return (np.sign(q) * ((1 + mu) ** np.abs(q) - 1) / mu).astype(np.float32)


class TrainSet(Dataset):
    def __init__(self, items, music: BackgroundPool, noise: BackgroundPool, seed: int):
        self.items, self.music, self.noise, self.seed = items, music, noise, seed

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        rng = np.random.default_rng(
            (self.seed, i, torch.utils.data.get_worker_info().id if torch.utils.data.get_worker_info() else 0,
             time.time_ns() & 0xFFFFFFFF))
        path, label, _ = self.items[i]
        try:
            x = random_crop(load_audio(path), NB_SAMP, rng)
        except Exception:
            x = np.zeros(NB_SAMP, np.float32)
        if rng.random() < 0.25:
            x = reverberate(x, rng)
        if rng.random() < 0.5 and np.mean(x ** 2) > 1e-7:
            pool = self.music if rng.random() < 0.6 else self.noise
            x = add_background(x, pool.segment(NB_SAMP, rng), float(rng.uniform(0, 20)))
        if rng.random() < 0.25:
            x = telephone(x)
        x = x * float(10 ** (rng.uniform(-6, 6) / 20))
        peak = float(np.max(np.abs(x))) if len(x) else 0.0
        if peak > 1.0:
            x = x / peak
        return torch.from_numpy(x.astype(np.float32)), label


class EvalSet(Dataset):
    """Deterministic: first 64,600 samples, tiled if short — matching both the
    reference evaluation and how the app scores a window. Optionally mixes a
    fixed held-out music segment at a fixed SNR (the music false-alarm test)."""

    def __init__(self, items, music: BackgroundPool | None = None, snr_db: float = 10.0, seed: int = 0):
        self.items, self.music, self.snr, self.seed = items, music, snr_db, seed

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        path, label, _ = self.items[i]
        try:
            x = tile_to(load_audio(path), NB_SAMP)
        except Exception:
            x = np.zeros(NB_SAMP, np.float32)
        if self.music is not None and np.mean(x ** 2) > 1e-7:
            rng = np.random.default_rng((self.seed, i))
            x = add_background(x, self.music.segment(NB_SAMP, rng), self.snr)
            peak = float(np.max(np.abs(x)))
            if peak > 1.0:
                x = x / peak
        return torch.from_numpy(x.astype(np.float32)), label


# --------------------------------------------------------------------------
# Metrics
# --------------------------------------------------------------------------

def compute_eer(scores: np.ndarray, labels: np.ndarray) -> tuple[float, float]:
    """scores: higher = more genuine (the AASIST CM score). Returns (EER, threshold)."""
    from sklearn.metrics import roc_curve
    if len(np.unique(labels)) < 2:
        return float("nan"), float("nan")
    fpr, tpr, thr = roc_curve(labels, scores, pos_label=BONAFIDE)
    fnr = 1 - tpr
    i = int(np.nanargmin(np.abs(fnr - fpr)))
    return float((fpr[i] + fnr[i]) / 2), float(thr[i])


def score(model, dataset, device, batch_size) -> tuple[np.ndarray, np.ndarray]:
    loader = DataLoader(dataset, batch_size=batch_size, shuffle=False,
                        num_workers=workers(), pin_memory=device.type == "cuda")
    model.eval()
    out_s, out_l = [], []
    with torch.no_grad():
        for x, y in loader:
            _, logits = model(x.to(device, non_blocking=True), Freq_aug=False)
            out_s.append((logits[:, BONAFIDE] - logits[:, SPOOF]).float().cpu().numpy())
            out_l.append(y.numpy())
    return np.concatenate(out_s), np.concatenate(out_l)


def fit_platt(scores, labels) -> dict:
    """p(bona fide) = sigmoid(slope * cmScore + intercept) — the exact form of
    Calibration in RiskEngine.kt."""
    from sklearn.linear_model import LogisticRegression
    lr = LogisticRegression(C=1e6, max_iter=1000)
    lr.fit(scores.reshape(-1, 1), (labels == BONAFIDE).astype(int))
    return {"slope": float(lr.coef_[0][0]), "intercept": float(lr.intercept_[0])}


def workers() -> int:
    return 0 if SMOKE else max(1, min(8, (os.cpu_count() or 2)))


def evaluate_all(model, sets, device, batch_size, dev_threshold=None) -> dict:
    res = {}
    for name, ds in sets.items():
        s, l = score(model, ds, device, batch_size)
        eer, thr = compute_eer(s, l)
        entry = {"n": int(len(l)), "eer": eer}
        if dev_threshold is not None:
            bona = l == BONAFIDE
            spoof = ~bona
            # A bona fide clip scoring below the threshold is flagged as fake.
            if bona.any():
                entry["false_alarm_rate"] = float(np.mean(s[bona] < dev_threshold))
            if spoof.any():
                entry["miss_rate"] = float(np.mean(s[spoof] >= dev_threshold))
        res[name] = entry
        log(f"  {name:<22} n={entry['n']:>6}  EER={eer * 100:6.2f}%"
            + (f"  false-alarm={entry['false_alarm_rate'] * 100:6.2f}%" if 'false_alarm_rate' in entry else "")
            + (f"  miss={entry['miss_rate'] * 100:6.2f}%" if 'miss_rate' in entry else ""))
    return res


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def load_model(src_dir: Path, weights: Path, device):
    spec = importlib.util.spec_from_file_location("aasist_model", src_dir / "AASIST.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    cfg = json.loads((src_dir / "AASIST-L.conf").read_text())["model_config"]
    model = module.Model(cfg)
    model.load_state_dict(torch.load(weights, map_location="cpu"))
    return model.to(device)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input-root", type=Path, default=Path("/kaggle/input"))
    ap.add_argument("--out", type=Path, default=Path("/kaggle/working"))
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--samples-per-epoch", type=int, default=16_000)
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--la-dev-n", type=int, default=4000)
    ap.add_argument("--la-eval-n", type=int, default=12000)
    ap.add_argument("--music-test-n", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=1234)
    args = ap.parse_args()

    if SMOKE:
        args.epochs, args.samples_per_epoch, args.batch_size = 1, 16, 4
        args.la_dev_n, args.la_eval_n, args.music_test_n = 12, 12, 8

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    args.out.mkdir(parents=True, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    log(f"device={device} {torch.cuda.get_device_name(0) if device.type == 'cuda' else ''} "
        f"workers={workers()} smoke={SMOKE}")

    # ---- inputs
    src_dir = find_file(args.input_root, "AASIST.py").parent
    la_root = find_dir(args.input_root, "ASVspoof2019_LA_cm_protocols").parent
    itw_meta = find_file(args.input_root, "meta.csv")
    itw_root = find_dir(itw_meta.parent, "release_in_the_wild")
    # MUSAN mirrors nest the corpus under a same-named folder; anchor on the
    # directory that actually holds music/ next to noise/.
    musan = find_dir(args.input_root, "music", accept=lambda p: (p.parent / "noise").is_dir()).parent
    log(f"src={src_dir}\nla={la_root}\nitw={itw_root}\nmusan={musan}")

    # ---- splits
    la_train = parse_asvspoof(la_root, "train")
    la_dev = stratified_sample(parse_asvspoof(la_root, "dev"), args.la_dev_n, args.seed)
    la_eval = stratified_sample(parse_asvspoof(la_root, "eval"), args.la_eval_n, args.seed)
    itw = parse_itw(itw_root, itw_meta, args.seed)
    if SMOKE:
        la_train = stratified_sample(la_train, 16, 1)
        for k in itw:
            itw[k] = stratified_sample(itw[k], 12, 1)

    # MUSAN split by file: training augmentation never sees the music used for
    # the music false-alarm test.
    def wavs(sub):
        root = musan / sub
        return sorted(str(p) for p in root.rglob("*.wav"))
    music_files, noise_files = wavs("music"), wavs("noise")
    rng = random.Random(args.seed)
    rng.shuffle(music_files)
    cut = int(len(music_files) * 0.8)
    music_train = BackgroundPool(music_files[:cut])
    music_test = BackgroundPool(music_files[cut:])
    noise_pool = BackgroundPool(noise_files)

    # Fail loudly on a path mismatch. The loaders fall back to silence on a
    # decode error, so a wrong folder name would otherwise train on nothing.
    every = la_train + la_dev + la_eval + itw["train"] + itw["dev"] + itw["test"]
    missing = [p for p, _, _ in every if not os.path.exists(p)]
    if missing:
        raise SystemExit(f"{len(missing)} of {len(every)} audio files missing, e.g. {missing[:3]}")

    train_items = la_train + itw["train"]
    for name, items in [("LA train", la_train), ("ITW train", itw["train"]), ("LA dev", la_dev),
                        ("ITW dev", itw["dev"]), ("LA eval", la_eval), ("ITW test", itw["test"])]:
        nb = sum(1 for i in items if i[1] == BONAFIDE)
        log(f"{name:<10} {len(items):>6} clips ({nb} bona fide, {len(items) - nb} spoof)")
    log(f"MUSAN music: {len(music_train.files)} train / {len(music_test.files)} held out; "
        f"noise: {len(noise_pool.files)}")

    # Balance the four (domain, label) groups so neither dataset nor class
    # dominates each epoch.
    groups = [(d, l) for _, l, d in train_items]
    counts = {g: groups.count(g) for g in set(groups)}
    weights = torch.tensor([1.0 / counts[g] for g in groups], dtype=torch.double)
    sampler = WeightedRandomSampler(weights, num_samples=args.samples_per_epoch, replacement=True)
    train_loader = DataLoader(TrainSet(train_items, music_train, noise_pool, args.seed),
                              batch_size=args.batch_size, sampler=sampler, num_workers=workers(),
                              pin_memory=device.type == "cuda", drop_last=True,
                              persistent_workers=workers() > 0)

    dev_sets = {"LA dev": EvalSet(la_dev), "ITW dev": EvalSet(itw["dev"])}
    bona_test = [i for i in la_eval + itw["test"] if i[1] == BONAFIDE]
    bona_test = random.Random(args.seed).sample(bona_test, min(args.music_test_n, len(bona_test)))
    test_sets = {
        "LA eval": EvalSet(la_eval),
        "ITW test": EvalSet(itw["test"]),
        "real voice clean": EvalSet(bona_test),
        "real voice + music": EvalSet(bona_test, music=music_test, snr_db=10.0, seed=args.seed),
    }

    weights_path = src_dir / "AASIST-L.pth"
    metrics: dict = {"config": {k: str(v) for k, v in vars(args).items()}}

    # ---- baseline: the checkpoint the app ships today
    log("=== baseline (pretrained AASIST-L, as shipped) ===")
    base = load_model(src_dir, weights_path, device)
    s, l = score(base, torch.utils.data.ConcatDataset(list(dev_sets.values())), device, args.batch_size)
    base_eer, base_thr = compute_eer(s, l)
    log(f"  dev pooled EER={base_eer * 100:.2f}% threshold={base_thr:.3f}")
    metrics["baseline"] = {"dev_eer": base_eer, "dev_threshold": base_thr,
                           "test": evaluate_all(base, test_sets, device, args.batch_size, base_thr)}
    del base

    # ---- fine-tune
    model = load_model(src_dir, weights_path, device)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs * len(train_loader))
    loss_fn = nn.CrossEntropyLoss()
    best, best_epoch = float("inf"), -1

    for epoch in range(1, args.epochs + 1):
        model.train()
        t0, total, n = time.time(), 0.0, 0
        for x, y in train_loader:
            x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
            _, logits = model(x, Freq_aug=True)
            loss = loss_fn(logits, y)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item() * len(y)
            n += len(y)
        log(f"epoch {epoch}/{args.epochs} loss={total / max(n, 1):.4f} ({time.time() - t0:.0f}s)")

        dev = evaluate_all(model, dev_sets, device, args.batch_size)
        # Select on the *worse* of the two domains so that gaining on
        # In-the-Wild can't hide a collapse on ASVspoof, or vice versa.
        sel = float(np.nanmax([dev["LA dev"]["eer"], dev["ITW dev"]["eer"]]))
        torch.save(model.state_dict(), args.out / "last.pth")
        if sel < best:
            best, best_epoch = sel, epoch
            torch.save(model.state_dict(), args.out / "best.pth")
            log(f"  new best (worst-domain dev EER {sel * 100:.2f}%)")
        metrics.setdefault("epochs", []).append({"epoch": epoch, "loss": total / max(n, 1), "dev": dev})
        (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2))

    # ---- final evaluation of the selected checkpoint
    log(f"=== fine-tuned (best epoch {best_epoch}) ===")
    model.load_state_dict(torch.load(args.out / "best.pth", map_location=device))
    s, l = score(model, torch.utils.data.ConcatDataset(list(dev_sets.values())), device, args.batch_size)
    ft_eer, ft_thr = compute_eer(s, l)
    log(f"  dev pooled EER={ft_eer * 100:.2f}% threshold={ft_thr:.3f}")
    metrics["finetuned"] = {"best_epoch": best_epoch, "dev_eer": ft_eer, "dev_threshold": ft_thr,
                            "calibration": fit_platt(s, l),
                            "test": evaluate_all(model, test_sets, device, args.batch_size, ft_thr)}
    log(f"  Platt calibration: {metrics['finetuned']['calibration']}")

    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2))
    (args.out / "train_log.txt").write_text("\n".join(LOG_LINES) + "\n")
    log("done")


if __name__ == "__main__":
    main()
