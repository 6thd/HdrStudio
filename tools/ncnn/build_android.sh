#!/usr/bin/env bash
# Rebuild ncnn 20250503 for arm64-v8a + Vulkan with the same NDK/CMake the app uses.
# The APK links the upstream android-vulkan package of that tag when NCNN_DIR points at it.
# This script is the from-source path. It does not change weights or enable INT8.
set -euo pipefail
TAG=20250503
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME}/ndk/27.2.12479018}"
SRC="${1:-$PWD/ncnn-$TAG}"
OUT="${2:-$PWD/ncnn-$TAG-android-arm64}"
echo "NDK=$NDK"
"$NDK/ndk-build" --version 2>/dev/null || true
cmake --version | head -1
cmake -S "$SRC" -B "$OUT" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DANDROID_STL=c++_static \
  -DNCNN_VULKAN=ON \
  -DNCNN_BUILD_TOOLS=OFF \
  -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_TESTS=OFF \
  -DNCNN_BUILD_BENCHMARK=OFF \
  -DNCNN_INT8=OFF \
  -DNCNN_BF16=OFF
cmake --build "$OUT" --config Release -j
echo "built $OUT"
echo "point NCNN_DIR at the directory that contains ncnnConfig.cmake"
