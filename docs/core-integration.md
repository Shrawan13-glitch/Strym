# Building the core for Android (and iOS)

The core repo is consumed **read-only** from this repo. It is pinned as a git
dependency (or vendored submodule) and never edited here.

## Pinning the core

Declare the dependency in `android/`'s Rust build as a git dependency:

```toml
# Cargo.toml (android/rust/ — see PLAN.md Phase A)
[dependencies]
stream-ffi = { git = "<core-repo-url>", tag = "v1.0.0" }
```

Cargo resolves it into `~/.cargo/git`. Everything below assumes the checked-out
core is at `$CORE` (a cargo git checkout, submodule, or `git clone` for local
iteration).

## Kotlin bindings

Generated, never committed:

```sh
# Inside the core repo at the pinned commit:
cargo build -p stream-ffi
cargo run -p stream-ffi --bin uniffi-bindgen -- generate \
  --language kotlin --no-format \
  --out-dir <this-repo>/android/app/src/main/java \
  --library target/debug/libstream_ffi.so
```

Output: `uniffi/stream_ffi.kt` (+ friends) under `java/`, ready for
`kotlinSourceSets` to pick up.

## Native library for Android

Requires: Rust toolchain, the Android NDK, and `cargo-ndk`:

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi \
  x86_64-linux-android i686-linux-android
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o app/src/main/jniLibs build -p stream-ffi --release
```

Produces `libstream_ffi.so` per ABI under `jniLibs/`. UniFFI's Kotlin side
loads it with `System.loadLibrary("stream_ffi")`.

> Keep the arch list minimal in debug (arm64-v8a only) to cut build time;
> expand for release.

## iOS notes (deferred)

Same bindgen flow, `--language swift`, plus `cargo-lipo`/XCFramework assembly.
Tracked in `PLAN.md` Phase Z.
