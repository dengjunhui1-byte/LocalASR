#!/usr/bin/env python3
"""Export Qwen/Qwen3-ASR-0.6B to an MNN pack via upstream llmexport.py (requires PyTorch + deps)."""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MNN = ROOT / ".mnn-build" / "MNN"
EXPORT = DEFAULT_MNN / "transformers" / "llm" / "export" / "llmexport.py"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--path",
        required=True,
        help="Local directory with Qwen/Qwen3-ASR-0.6B weights (HF clone or modelscope download)",
    )
    parser.add_argument(
        "--dst",
        default=str(ROOT / "QwenASR" / "Qwen3-ASR-0.6B-MNN"),
        help="Output directory for llm.mnn + audio.mnn pack",
    )
    parser.add_argument("--quant-bit", type=int, default=8, choices=[4, 8])
    parser.add_argument("--mnn-src", default=str(DEFAULT_MNN))
    args = parser.parse_args()

    export = Path(args.mnn_src) / "transformers" / "llm" / "export" / "llmexport.py"
    if not export.is_file():
        print(f"Missing {export}; clone MNN master to {args.mnn_src}", file=sys.stderr)
        return 1

    cmd = [
        sys.executable,
        str(export),
        "--path",
        args.path,
        "--dst_path",
        args.dst,
        "--export",
        "mnn",
        "--quant_bit",
        str(args.quant_bit),
        "--quant_block",
        "0",
    ]
    print("Running:", " ".join(cmd))
    return subprocess.call(cmd, cwd=export.parent)


if __name__ == "__main__":
    raise SystemExit(main())
