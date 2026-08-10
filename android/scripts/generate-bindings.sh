#!/usr/bin/env bash
# Regenerate the Kotlin bindings into the committed location.
#
# The bindings are COMMITTED (see PLAN.md): they change only when the pinned
# core rev changes. Run this manually after bumping the rev in rust/Cargo.toml,
# then commit the diff. CI verifies they are up to date via .github/workflows/
# bindings-check.yml instead of regenerating on every build.
#
# Requires a debug build of the pinned core's library.
set -euo pipefail
cd "$(dirname "$0")/../rust"

LIB="target/debug/libstream_ffi.so"
OUT="$(cd .. && pwd)/app/src/main/java"

[[ -f "$LIB" ]] || {
  echo "missing $LIB — run: cargo build" >&2
  exit 1
}

echo ">> regenerating Kotlin bindings from pinned core"
cargo run --bin uniffi-bindgen -- generate \
  --language kotlin --no-format \
  --out-dir "$OUT" --library "$LIB"

echo ">> done: $OUT/uniffi/stream_ffi/stream_ffi.kt"
