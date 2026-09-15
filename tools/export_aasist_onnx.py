#!/usr/bin/env python3
"""Export the pretrained AASIST-L countermeasure to ONNX for on-device inference.

AASIST-L is 85k parameters (~332 kB) and reports 0.99% EER / 0.0309 min t-DCF on
the ASVspoof2019 LA evaluation set. Those numbers are *in-domain*: the same
checkpoint degrades sharply on in-the-wild and codec-varied audio, so treat the
exported model as a working baseline to measure against, not as a shippable
detector. See docs/MODEL.md.

Upstream: https://github.com/clovaai/aasist (MIT, (c) NAVER Corp.)

Usage:
    python tools/export_aasist_onnx.py \
        --aasist-repo /path/to/aasist \
        --out app/src/main/assets/aasist_l.onnx
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import numpy as np
import torch
import torch.nn as nn

# The AASIST checkpoint is trained on 64,600-sample (~4.04 s) 16 kHz crops.
NB_SAMP = 64_600
SAMPLE_RATE = 16_000

# Class index convention, from aasist/data_utils.py:
#   d_meta[key] = 1 if label == "bonafide" else 0
# so logits[..., 0] is spoof and logits[..., 1] is bona fide. main.py scores with
# batch_out[:, 1], i.e. higher = more genuine.
SPOOF_INDEX = 0
BONAFIDE_INDEX = 1


class LogitsOnly(nn.Module):
    """Drops the hidden-state output so the graph has a single 2-logit output."""

    def __init__(self, model: nn.Module) -> None:
        super().__init__()
        self.model = model

    def forward(self, waveform: torch.Tensor) -> torch.Tensor:
        # Freq_aug is a training-time augmentation; it must be off for inference.
        _, logits = self.model(waveform, Freq_aug=False)
        return logits


def build_model(repo: pathlib.Path, config_path: pathlib.Path, weights: pathlib.Path):
    sys.path.insert(0, str(repo))
    config = json.loads(config_path.read_text())
    model_config = config["model_config"]

    if model_config["nb_samp"] != NB_SAMP:
        raise SystemExit(
            f"config nb_samp={model_config['nb_samp']} but this exporter assumes {NB_SAMP}"
        )

    module = __import__(f"models.{model_config['architecture']}", fromlist=["Model"])
    model = module.Model(model_config)
    state = torch.load(weights, map_location="cpu")
    model.load_state_dict(state)
    model.eval()

    params = sum(p.numel() for p in model.parameters())
    print(f"loaded {model_config['architecture']} from {weights.name}: {params:,} parameters")
    return model


def export(model: nn.Module, out: pathlib.Path) -> None:
    import onnx

    wrapper = LogitsOnly(model).eval()
    dummy = torch.randn(1, NB_SAMP, dtype=torch.float32)

    out.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        wrapper,
        (dummy,),
        str(out),
        input_names=["waveform"],
        output_names=["logits"],
        # Everything is static: [1, 64600] in, [1, 2] out.
        #
        # The sample axis has to be fixed because the model is length-sensitive
        # and scores are only comparable at the length it was trained on. Batch
        # is fixed at 1 because the exporter pins the *output* batch to 1
        # regardless — declaring a dynamic input batch against a fixed output
        # produces an incoherent graph that downstream tooling trips over.
        dynamo=True,
    )

    # The exporter may spill initialisers to a sidecar .onnx.data file. Android
    # assets are easier to ship and verify as a single blob, so fold the weights
    # back inline and drop the sidecar.
    sidecar = out.with_suffix(out.suffix + ".data")
    proto = onnx.load(str(out))
    onnx.save_model(proto, str(out), save_as_external_data=False)
    sidecar.unlink(missing_ok=True)

    opset = next((o.version for o in proto.opset_import if o.domain in ("", "ai.onnx")), None)
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} kB, opset {opset})")


def verify(model: nn.Module, out: pathlib.Path, tolerance: float) -> None:
    """Confirms ONNX Runtime reproduces PyTorch on random and silent input."""
    import onnxruntime as ort

    session = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    wrapper = LogitsOnly(model).eval()

    cases = {
        "random": torch.randn(1, NB_SAMP, dtype=torch.float32),
        "silence": torch.zeros(1, NB_SAMP, dtype=torch.float32),
        "tone": torch.sin(
            2 * np.pi * 440 * torch.arange(NB_SAMP, dtype=torch.float32) / SAMPLE_RATE
        ).unsqueeze(0),
    }

    worst = 0.0
    for name, x in cases.items():
        with torch.no_grad():
            expected = wrapper(x).numpy()
        actual = session.run(["logits"], {"waveform": x.numpy()})[0]
        delta = float(np.abs(expected - actual).max())
        worst = max(worst, delta)
        spoof_p = float(torch.softmax(torch.from_numpy(actual), dim=-1)[0, SPOOF_INDEX])
        print(f"  {name:<8} max|torch-ort|={delta:.3e}  spoof_prob={spoof_p:.4f}")

    if worst > tolerance:
        raise SystemExit(f"parity check FAILED: max delta {worst:.3e} > {tolerance:.3e}")
    print(f"parity OK (max delta {worst:.3e})")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--aasist-repo", type=pathlib.Path, required=True)
    ap.add_argument("--config", type=pathlib.Path, default=None,
                    help="defaults to <repo>/config/AASIST-L.conf")
    ap.add_argument("--weights", type=pathlib.Path, default=None,
                    help="defaults to <repo>/models/weights/AASIST-L.pth")
    ap.add_argument("--out", type=pathlib.Path,
                    default=pathlib.Path("app/src/main/assets/aasist_l.onnx"))
    ap.add_argument("--tolerance", type=float, default=1e-4)
    args = ap.parse_args()

    repo = args.aasist_repo.resolve()
    config = args.config or repo / "config" / "AASIST-L.conf"
    weights = args.weights or repo / "models" / "weights" / "AASIST-L.pth"
    for path in (repo, config, weights):
        if not path.exists():
            raise SystemExit(f"missing: {path}")

    torch.manual_seed(0)
    model = build_model(repo, config, weights)
    export(model, args.out)
    verify(model, args.out, args.tolerance)


if __name__ == "__main__":
    main()
