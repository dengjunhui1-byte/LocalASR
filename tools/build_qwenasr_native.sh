#!/usr/bin/env bash
# Cross-compiles MNN master (static, LLM + audio) for arm64-v8a and links libqwenasr_jni.so.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MNN_SRC="${MNN_SRC:-$ROOT/.mnn-build/MNN}"
BUILD_DIR="${BUILD_DIR:-$ROOT/.mnn-build/android-arm64}"
JNI_BUILD="${JNI_BUILD:-$ROOT/.mnn-build/qwenasr-jni}"
DEST="$ROOT/asr-qwenasr/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK:-${ANDROID_HOME:-}/ndk/25.1.8937393}"
CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-$(command -v ninja || true)}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

if [ ! -f "$MNN_SRC/CMakeLists.txt" ]; then
  echo "!! MNN source missing at $MNN_SRC" >&2
  echo "   git clone --depth 1 https://github.com/alibaba/MNN.git $MNN_SRC" >&2
  exit 1
fi
if [ ! -d "$NDK" ]; then
  echo "!! ANDROID_NDK not found: $NDK" >&2
  echo "   export ANDROID_NDK=\$ANDROID_HOME/ndk/<version>" >&2
  exit 1
fi
if [ -z "$NINJA" ] || [ ! -x "$NINJA" ]; then
  echo "!! ninja is required" >&2
  exit 1
fi

ENGINE_CMAKE="$MNN_SRC/transformers/llm/engine/CMakeLists.txt"
if ! grep -q "skip apply_template on Android" "$ENGINE_CMAKE"; then
  python3 - "$ENGINE_CMAKE" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
text = p.read_text()
old = """# apply_template demo (uses jinja)
add_executable(apply_template ${CMAKE_CURRENT_LIST_DIR}/demo/apply_template.cpp)
target_link_libraries(apply_template ${LLM_DEPS})
"""
new = """# apply_template demo (uses jinja)
# skip apply_template on Android — it is a host CLI and breaks the static cross build.
if (NOT CMAKE_SYSTEM_NAME MATCHES "^Android")
add_executable(apply_template ${CMAKE_CURRENT_LIST_DIR}/demo/apply_template.cpp)
target_link_libraries(apply_template ${LLM_DEPS})
endif()
"""
if old not in text:
    raise SystemExit("engine CMakeLists.txt does not match expected apply_template block")
p.write_text(text.replace(old, new, 1))
print("patched", p)
PY
fi

mkdir -p "$BUILD_DIR"
if [ -f "$BUILD_DIR/CMakeCache.txt" ] && grep -q 'MNN_ARM82:BOOL=ON' "$BUILD_DIR/CMakeCache.txt"; then
  echo "==> dropping stale CMake cache (ARM82 was ON)"
  rm -f "$BUILD_DIR/CMakeCache.txt"
fi
cd "$BUILD_DIR"

mnn_cmake_args=(
  -G Ninja
  "$MNN_SRC"
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_MAKE_PROGRAM="$NINJA"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_STL=c++_static
  -DANDROID_NATIVE_API_LEVEL=android-28
  -DMNN_BUILD_FOR_ANDROID_COMMAND=true
  -DMNN_USE_LOGCAT=true
  -DMNN_USE_SSE=OFF
  -DMNN_BUILD_SHARED_LIBS=OFF
  -DMNN_SEP_BUILD=OFF
  -DMNN_BUILD_TEST=OFF
  -DMNN_BUILD_BENCHMARK=OFF
  -DMNN_BUILD_CONVERTER=OFF
  -DMNN_BUILD_TOOLS=OFF
  -DMNN_OPENCL=ON
  -DMNN_ARM82=OFF
  -DMNN_KLEIDIAI=ON
  -DMNN_LOW_MEMORY=ON
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON
  -DMNN_BUILD_LLM=ON
  -DMNN_BUILD_AUDIO=ON
  -DMNN_BUILD_LLM_OMNI=OFF
  -DMNN_BUILD_OPENCV=OFF
  -DMNN_IMGCODECS=OFF
  -DMNN_BUILD_DIFFUSION=OFF
  -DMNN_LLM_BUILD_DEMO=OFF
  -DLLM_SUPPORT_HTTP_RESOURCE=OFF
  -DNATIVE_LIBRARY_OUTPUT=.
  -DNATIVE_INCLUDE_OUTPUT=.
)
"$CMAKE" "${mnn_cmake_args[@]}"
"$CMAKE" --build "$BUILD_DIR" --target MNN -- -j"$JOBS"

if [ ! -f "$BUILD_DIR/libMNN.a" ]; then
  echo "!! libMNN.a was not produced" >&2
  find "$BUILD_DIR" -name "libMNN*" | head
  exit 1
fi

mkdir -p "$JNI_BUILD" "$DEST"
cd "$JNI_BUILD"
"$CMAKE" -G Ninja "$ROOT/asr-qwenasr/src/main/cpp" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_STL=c++_static \
  -DANDROID_NATIVE_API_LEVEL=android-28 \
  -DMNN_SOURCE_DIR="$MNN_SRC" \
  -DMNN_BUILD_DIR="$BUILD_DIR"
"$CMAKE" --build "$JNI_BUILD" --target qwenasr_jni -- -j"$JOBS"
cp -f "$JNI_BUILD/libqwenasr_jni.so" "$DEST/libqwenasr_jni.so"
STRIP="$(find "$NDK/toolchains/llvm/prebuilt" -name llvm-strip -type f 2>/dev/null | head -1)"
if [ -n "$STRIP" ]; then
  "$STRIP" --strip-unneeded "$DEST/libqwenasr_jni.so"
fi
ls -lh "$DEST/libqwenasr_jni.so"
file "$DEST/libqwenasr_jni.so"
