#!/usr/bin/env bash
# Cross-compile the core's UniFFI facade for Android ABIs into jniLibs/.
#
# Usage: ./build-native.sh [release|debug]
#   release (default): arm64-v8a + x86_64, --release
#   debug:             arm64-v8a only, fast turnaround
#
# Prereqs: rustup targets (aarch64-linux-android, x86_64-linux-android),
#          cargo-ndk, Android NDK via local.properties (sdk.dir) or ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/../rust"

MODE="${1:-release}"
OUT="$(cd .. && pwd)/app/src/main/jniLibs"

if [[ "$MODE" == "release" ]]; then
  NDK_TARGETS=(-t arm64-v8a -t x86_64)
  CARGO_FLAGS=(--release)
else
  NDK_TARGETS=(-t arm64-v8a)
  CARGO_FLAGS=()
fi

echo ">> cargo-ndk build ($MODE)"
cargo ndk "${NDK_TARGETS[@]}" -o "$OUT" build -p stream_android_bridge "${CARGO_FLAGS[@]}"

echo ">> artifacts:"
find "$OUT" -name 'libstream_ffi.so' -exec ls -lh {} +
