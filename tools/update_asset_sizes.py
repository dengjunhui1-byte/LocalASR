#!/usr/bin/env python3
"""Write measured asset folder sizes back into each engine's config.json sizeBytes."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = [
    ("whisper", "asr-whisper"),
    ("paraformer", "asr-paraformer"),
    ("sensevoice", "asr-sensevoice"),
    ("parakeet", "asr-parakeet"),
    ("streaming", "asr-streaming"),
    ("qwenasr", "asr-qwenasr"),
]


def dir_size(path: Path) -> int:
    if not path.is_dir():
        return 0
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())


def main() -> int:
    for engine_id, module in MODULES:
        config_path = ROOT / module / "src/main/assets/asr" / engine_id / "config.json"
        if not config_path.is_file():
            print(f"skip missing {config_path}", file=sys.stderr)
            continue
        data = json.loads(config_path.read_text(encoding="utf-8"))
        for model in data.get("models", []):
            model_dir = (
                ROOT
                / module
                / "src/main/assets/asr"
                / engine_id
                / "models"
                / model["dir"]
            )
            size = dir_size(model_dir)
            if size > 0:
                model["sizeBytes"] = size
                print(f"{engine_id}/{model['id']}: {size} bytes")
        config_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
