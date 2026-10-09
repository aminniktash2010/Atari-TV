#!/bin/bash
# Build the Stella libretro core (arm64-v8a, 16KB-aligned) for Atari TV.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage: tools/build_core.sh
# Output: app/src/main/jniLibs/arm64-v8a/stella_libretro.so
# Gate: every LOAD segment must show "Align 0x4000" (llvm-readelf -l).
set -euo pipefail

PROJ_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_PIN="ba52c43b9eda950eb0c0eec69cda9b17dee8c39b"
CORE_DIR="$PROJ_ROOT/core-build/stella2023"
NDK="${ANDROID_NDK_HOME:-$HOME/workspace/android-sdk/ndk/28.2.13676358}"
export https_proxy="${https_proxy:-http://127.0.0.1:3129}"
export http_proxy="${http_proxy:-http://127.0.0.1:3129}"

if [ ! -d "$CORE_DIR/.git" ]; then
  echo "== cloning libretro/stella2023 @ $CORE_PIN"
  git clone --no-checkout https://github.com/libretro/stella2023.git "$CORE_DIR"
fi
cd "$CORE_DIR"
git fetch --depth 1 origin "$CORE_PIN" 2>/dev/null || true
git checkout "$CORE_PIN"
git rev-parse HEAD > "$PROJ_ROOT/core-build/CORE_VERSION.txt"
echo "== core: $(cat "$PROJ_ROOT/core-build/CORE_VERSION.txt")"

echo "== ndk-build (arm64-v8a)"
cd src/os/libretro/jni
# NOTE: APP_CPPFLAGS and APP_STL are passed on the command line (not taken from
# Application.mk) because ndk-build r28 ignores the Application.mk assignments
# (`APP_CPPFLAGS := -fexceptions` -> "cannot use 'throw' with exceptions
# disabled"; `APP_STL := c++_static` -> undefined std::__ndk1 symbols at link).
"$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
  APP_ABI=arm64-v8a APP_STL=c++_static APP_CPPFLAGS=-fexceptions -j"$(nproc)"

SO="libs/arm64-v8a/libretro.so"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
echo "== 16KB-alignment gate"
"$READELF" -l "$SO" | grep LOAD
BAD=$("$READELF" -l "$SO" | grep -c "LOAD.*0x1000" || true)
if [ "${BAD:-0}" != "0" ]; then
  echo "!! 4KB-aligned LOAD segment found; rebuilding with max-page-size=16384"
  "$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
    APP_ABI=arm64-v8a APP_STL=c++_static APP_CPPFLAGS=-fexceptions -j"$(nproc)" \
    APP_LDFLAGS="-Wl,-z,max-page-size=16384"
  BAD=$("$READELF" -l "$SO" | grep -c "LOAD.*0x1000" || true)
  [ "${BAD:-0}" = "0" ] || { echo "FATAL: still not 16KB-aligned"; exit 1; }
fi
echo "== alignment OK (all LOAD segments 0x4000)"

DEST="$PROJ_ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"
cp "$SO" "$DEST/stella_libretro.so"
echo "== installed $DEST/stella_libretro.so"
ls -la "$DEST/stella_libretro.so"
# The app reads the core commit from assets for save-state meta.json.
mkdir -p "$PROJ_ROOT/app/src/main/assets"
cp "$PROJ_ROOT/core-build/CORE_VERSION.txt" "$PROJ_ROOT/app/src/main/assets/core_version.txt"
echo "== core version asset: $(cat "$PROJ_ROOT/app/src/main/assets/core_version.txt")"
