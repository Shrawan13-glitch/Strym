#!/usr/bin/env bash
# Generate Kotlin bindings from the pinned core into app/src/main/java.
#
# Requires a debug .so built by build-native.sh debug (the bindgen reads the
# library's embedded metadata, so any ABI works). The `uniffi-bindgen` bin in
# this crate matches the pinned core's uniffi 0.32.
set -euo pipefail
cd "$(dirname "$0")/../rust"

LIB="app/src/main/jniLibs/arm64-v8a/libstream_ffi.so"
OUT="../app/src/main/java"

# The bridge crate builds the .so; find it via cargo-ndk output convention.
LIB="$(find .. -name libstream_ffi.so -path '*arm64-v8a*' | head -1)"
[[ -n "$LIB" ]] || { echo "missing arm64-v8a .so — run ./scripts/build-native.sh debug first" >&2; exit 1; }

echo ">> generating Kotlin bindings from $LIB"
mkdir -p "$OUT"
cargo run --bin uniffi-bindgen -- generate \
  --language kotlin --no-format \
  --out-dir "$OUT" --library "$LIB"

echo ">> done: $OUT/uniffi/"
