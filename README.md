# Stream Platform — Android app for the `stream` core

Android publisher app that embeds the [Rust streaming core]. This is a
**separate repository from the core** by design:

| Repo | Contains | Contract |
| --- | --- | --- |
| `stream` (core) | Dependency-free Rust core + `stream-ffi` (UniFFI facade) | Frozen API 1.0; bindings generated, never committed |
| `stream-platform` (this) | Android app, capture/encode glue | Consumes the core's Kotlin bindings + shared library |

## How the two repos fit together

```text
core/stream-ffi ──generate-bindings.sh──▶ Kotlin (.kt)
        │  cargo build --target aarch64-linux-android
        └──▶ libstream_ffi.so  ──▶ bundled into the APK via jniLibs
core/src ── (ingest server, for loopback testing) ──▶ host test harness
```

The core publishes **no Gradle artifact** yet; the platform repo pins a core
commit and builds the shared library itself (see `docs/core-integration.md`).

## Repo layout

- `android/` — Android publisher app (Gradle + Kotlin, CameraX + MediaCodec)
- `docs/` — how to build/link the core, capture design notes
- `PLAN.md` — step-by-step roadmap for the Android app (the living spec)

## Current status

The core (engine, RTMP publish, reconnect, FFI facade, YouTube-compatible
ingest) is done and frozen at 1.0.0. This repo is at **Phase 0** of its
`PLAN.md`: scaffolded, no app code yet.
