# Building the core for Android

The core repo (`strym-core`) is consumed **read-only** from this repo. It is
pinned as a git dependency in `android/rust/Cargo.toml` and never edited here.

## Pinning the core

```toml
[dependencies]
stream-ffi = { git = "https://github.com/Shrawan13-glitch/strym-core.git", rev = "7e79c9c" }
```

Cargo resolves it into `~/.cargo/git`. The bridge crate re-exports the
scaffolding so the cdylib carries every UniFFI symbol under `libstream_ffi.so`.

## Kotlin bindings — committed, regenerated only on core bumps

Unlike the core (which treats bindings as throwaway), **this repo commits the
generated Kotlin bindings** so normal CI never regenerates them. Regenerate only
when you bump the pinned rev:

```sh
cd android
./scripts/generate-bindings.sh   # or: make bindings
# commit the resulting app/src/main/java/uniffi/stream_ffi/stream_ffi.kt diff
```

The `bindings-check` GitHub Action diff-verifies the committed bindings against
the pinned core whenever the rev or the generator changes.

## Native library for Android

Built in CI via `scripts/build-native.sh` (cargo-ndk):

```sh
cd android
./scripts/build-native.sh release   # arm64-v8a + x86_64 → app/src/main/jniLibs/
./scripts/build-native.sh debug     # arm64-v8a only, faster
```

Local prerequisites: Rust toolchain with android targets, Android NDK 27
(`sdk.dir` in `local.properties` or `ANDROID_HOME`), and `cargo-ndk`. The
resulting `.so` is git-ignored; UniFFI's Kotlin side loads it with
`System.loadLibrary("stream_ffi")`.

> Keep the arch list minimal in debug (arm64-v8a only) to cut build time;
> CI builds both ABIs for the release APK.

## Getting the APK onto a device

```sh
cd android
./scripts/install.sh        # latest CI build → adb install → launch
./scripts/install.sh <run>  # a specific CI run
```
