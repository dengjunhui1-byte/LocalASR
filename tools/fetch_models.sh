#!/usr/bin/env bash
# Downloads sherpa-onnx ASR models into each engine module's assets tree.
#
# Layout: <module>/src/main/assets/asr/<engineId>/models/<dir>/
# Run from repo root: ./tools/fetch_models.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.model-cache"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

mkdir -p "$CACHE"

fetch() {
  local name="$1"
  local archive="$CACHE/$name.tar.bz2"
  if [ -s "$archive" ]; then
    echo "==> cached $name"
  else
    echo "==> downloading $name"
    curl -fL -C - --retry 5 --retry-delay 2 -o "$archive.part" "$BASE/$name.tar.bz2"
    mv "$archive.part" "$archive"
  fi
}

# Extract tarball and flatten the single top-level directory into dest.
extract_flat() {
  local name="$1" dest="$2"
  local tmp="$CACHE/extract-$name"
  rm -rf "$tmp"
  mkdir -p "$tmp" "$dest"
  tar -xjf "$CACHE/$name.tar.bz2" -C "$tmp"
  local inner
  inner="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
  if [ -z "$inner" ]; then
    echo "ERROR: empty archive $name" >&2
    exit 1
  fi
  rm -rf "${dest:?}/"*
  cp -a "$inner"/. "$dest/"
  rm -rf "$tmp"
  echo "==> installed $name -> $dest"
}

prune() {
  local dir="$1"
  rm -rf "$dir/test_wavs" "$dir/README.md" "$dir/MODEL_CARD" "$dir/LICENSE"
  find "$dir" \( -name '*.py' -o -name '*.sh' \) -delete 2>/dev/null || true
}

WHISPER="$ROOT/asr-whisper/src/main/assets/asr/whisper/models/sherpa-onnx-whisper-base"
PARA="$ROOT/asr-paraformer/src/main/assets/asr/paraformer/models/sherpa-onnx-paraformer-zh-small-2024-03-09"
SENSE="$ROOT/asr-sensevoice/src/main/assets/asr/sensevoice/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
PARAKEET="$ROOT/asr-parakeet/src/main/assets/asr/parakeet/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
STREAM="$ROOT/asr-streaming/src/main/assets/asr/streaming/models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile"

mkdir -p "$WHISPER" "$PARA" "$SENSE" "$PARAKEET" "$STREAM"

fetch sherpa-onnx-whisper-base
extract_flat sherpa-onnx-whisper-base "$WHISPER"
prune "$WHISPER"

fetch sherpa-onnx-paraformer-zh-small-2024-03-09
extract_flat sherpa-onnx-paraformer-zh-small-2024-03-09 "$PARA"
prune "$PARA"

fetch sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
extract_flat sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 "$SENSE"
prune "$SENSE"

fetch sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8
extract_flat sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8 "$PARAKEET"
prune "$PARAKEET"

fetch sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile
extract_flat sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile "$STREAM"
prune "$STREAM"

echo
echo "==> done. Optional: python3 tools/update_asset_sizes.py"
du -sh "$WHISPER" "$PARA" "$SENSE" "$PARAKEET" "$STREAM" 2>/dev/null || true
