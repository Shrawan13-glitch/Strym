# Strym — Android app for `strym-core`

Android live-streaming publisher app that embeds the [strym-core] via its frozen
UniFFI facade. This is a **separate repository** from the core; the core is
consumed read-only as a pinned git dependency.

| Repo | Contains | Contract |
| --- | --- | --- |
| `strym-core` | Dependency-free Rust core + `stream-ffi` (UniFFI facade) | Frozen API 1.0; bindings generated, never committed |
| `Strym` (this) | Android app, capture/encode glue | Pins a core rev; Kotlin bindings **committed** |

## How the two repos fit together

```text
strym-core/stream-ffi ──generate-bindings.sh──▶ Kotlin bindings (committed)
        │  cargo-ndk (CI, android targets)
        └──▶ libstream_ffi.so  ──▶ jniLibs/ (built in CI, git-ignored)
              ──▶ assembleDebug ──▶ APK artifact ──▶ ./scripts/install.sh (adb)
```

The core publishes **no Gradle artifact**; CI pins a core commit, builds the
shared library itself, and produces an installable APK every run.

## Build & install (CI-first)

Everything builds on GitHub Actions; nothing but source is committed.

```sh
# Install the latest CI-built APK on a connected device
cd android && ./scripts/install.sh

# Or install a specific CI run
./scripts/install.sh <run-id>
```

Local dev tasks (need NDK + cargo-ndk installed):

```sh
cd android
make native        # arm64-v8a debug .so → app/src/main/jniLibs
make apk           # ./gradlew assembleDebug testDebugUnitTest
```

Regenerate the committed bindings only when you bump the core rev in
`rust/Cargo.toml`:

```sh
cd android && make bindings   # then commit the diff
```

CI enforces this: the `bindings-check` workflow diff-verifies the committed
bindings whenever the rev/scripts change, and `ci.yml` self-prunes stale caches
after every run.

## Repo layout

- `android/` — Gradle app (Kotlin, CameraX + MediaCodec), `rust/` bridge crate,
  `scripts/`, `Makefile`
- `docs/` — how to build/link the core
- `PLAN.md` — step-by-step roadmap (the living spec)

## Current status

Core API frozen at 1.0.0; bindings committed for rev `7e79c9c`; Gradle scaffold
+ CI (native lib, APK, unit tests, cache pruning) in place. This repo is in
**Phase A** of `PLAN.md`: native pipeline wired, capture/encode not yet landed.
