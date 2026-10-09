#!/bin/bash
# Vendor LibretroDroid (pinned commit) + apply Atari TV patches + prune.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# LibretroDroid is too large to vendor file-by-file into git (1600+ files,
# mostly the bundled Oboe checkout). Instead this script reproduces the
# exact vendored tree used for Atari TV releases:
#   1. shallow-clone Swordfish90/LibretroDroid at the pinned commit
#   2. apply tools/libretrodroid-atari-tv.patch (4 small build fixes)
#   3. prune directories the app build never touches
#
# Usage: tools/vendor_libretrodroid.sh
# Output: vendor/LibretroDroid/ (git-ignored; reproducible)
set -euo pipefail

PROJ_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="8835c3098514390a271e36983957f7bb5f40abf1"
VENDOR_DIR="$PROJ_ROOT/vendor/LibretroDroid"
export https_proxy="${https_proxy:-http://127.0.0.1:3129}"
export http_proxy="${http_proxy:-http://127.0.0.1:3129}"

if [ -d "$VENDOR_DIR" ]; then
  echo "== vendor/LibretroDroid already exists; remove it to re-vendor"
  exit 0
fi

echo "== cloning LibretroDroid @ $PIN"
mkdir -p "$PROJ_ROOT/vendor"
git clone --depth 1 https://github.com/Swordfish90/LibretroDroid.git "$VENDOR_DIR"
cd "$VENDOR_DIR"
git fetch --depth 1 origin "$PIN"
git checkout "$PIN"

echo "== applying Atari TV patches"
git apply "$PROJ_ROOT/tools/libretrodroid-atari-tv.patch"

echo "== pruning unneeded directories"
# Sample app (ships other cores' .so files we never use)
rm -rf app
# Oboe extras: the CMake build only needs include/ + src/
rm -rf libretrodroid/src/main/cpp/oboe/apps \
       libretrodroid/src/main/cpp/oboe/docs \
       libretrodroid/src/main/cpp/oboe/samples \
       libretrodroid/src/main/cpp/oboe/tests \
       libretrodroid/src/main/cpp/oboe/prefab
# Nested git metadata: the tree commits as regular files
rm -rf .git

echo "== vendored: $VENDOR_DIR"
git -C "$PROJ_ROOT" rev-parse HEAD >/dev/null 2>&1 || true
