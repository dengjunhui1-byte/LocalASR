#!/usr/bin/env bash
# Downloads the community Qwen3-ASR-0.6B int8 MNN pack into asr-qwenasr assets (optional offline dev).
#
# Pack mirrors hb-dev/Qwen3-ASR-0.6B-INT8-MNN (int8 llmexport of Qwen/Qwen3-ASR-0.6B).
# ModelScope twin: huangzhengxiang/Qwen3-ASR-0.6B-INT8-MNN
#
# Usage:
#   ./tools/fetch_qwenasr_models.sh
#   ./tools/fetch_qwenasr_models.sh /path/to/existing/pack
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/asr-qwenasr/src/main/assets/asr/qwenasr/models/qwen3-asr-0.6b-int8-mnn"
SRC="${1:-}"

HF_BASE="https://huggingface.co/hb-dev/Qwen3-ASR-0.6B-INT8-MNN/resolve/main"

FILES=(
  config.json
  llm_config.json
  tokenizer.txt
  llm.mnn
  llm.mnn.weight
  llm.mnn.json
  embeddings_bf16.bin
  audio.mnn
  audio.mnn.weight
)

download_one() {
  local name="$1"
  local url="$HF_BASE/$name"
  echo "==> $name"
  curl -fL --retry 3 --continue-at - -o "$DEST/$name" "$url"
}

if [ -n "$SRC" ]; then
  mkdir -p "$DEST"
  for f in "${FILES[@]}"; do
    cp -f "$SRC/$f" "$DEST/$f"
  done
else
  mkdir -p "$DEST"
  for f in "${FILES[@]}"; do
    download_one "$f"
  done
fi

echo
echo "==> staged $(du -sh "$DEST" | cut -f1) under assets"
echo "Run: python3 tools/update_asset_sizes.py"
echo "Optional: ./tools/build_qwenasr_native.sh  # produces libqwenasr_jni.so"
