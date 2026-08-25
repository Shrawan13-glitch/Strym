# `Strym` PLAN — Android live-streaming app

Roadmap for the Android publisher app that embeds the `strym-core` via its
frozen UniFFI facade. This is a **separate repo** from the core; the core is
consumed read-only as a pinned git dependency (`android/rust/Cargo.toml`).

State: **Phases A–D implemented; device verification outstanding.** Phase B
verified on device; Phase C pipeline + Phase D audio are built and CI-compiled,
but the Phase C/D exit criteria (host ingest + ffprobe) and the Phase D sync
test still need a device run.

---

## Build model (CI-first)

- **Bindings are committed.** `app/src/main/java/uniffi/stream_ffi/stream_ffi.kt`
  is checked in and only regenerated when the pinned core rev changes
  (`./scripts/generate-bindings.sh`). Normal CI never regenerates them; a
  `bindings-check` workflow diff-verifies them only when the rev/scripts change.
- **Native lib is built in CI.** `cargo-ndk` cross-compiles `libstream_ffi.so`
  (arm64-v8a + x86_64, release) into `jniLibs/` — git-ignored, produced per run.
- **APK is built in CI**, uploaded as a `strym-debug-apk` artifact; install on a
  device with `./scripts/install.sh` (downloads the artifact + `adb install`).
- **Caches are automatic and self-pruning.** Rust + Gradle caches key off
  `Cargo.lock`/build inputs; CI deletes all but the freshest few cache entries
  after each run (see `ci.yml` "Prune stale caches").

## Architecture (one page)

```text
┌──────────────────────────── Android app ───────────────────────────┐
│                                                                     │
│  CameraX ─▶ SurfaceProvider ─▶ MediaCodec(H.264, surface)           │
│                                   │  AVCC output                    │
│                                   ▼                                 │
│                              NalUnit.avccToAnnexB() ──▶ push_video  │
│                                                                     │
│  AudioRecord ─▶ MediaCodec(AAC, byte-buffer)                        │
│                                   │  raw AAC + ASC                  │
│                                   ▼                                 │
│                              push_audio  /  configure_codecs(ASC)   │
│                                                                     │
│  StreamController (single coordinator)                              │
│    ├─ owns StreamSession (lives in foreground Service)              │
│    ├─ reference clock: SystemClock.elapsedRealtimeNanos             │
│    ├─ StreamListener ─▶ StateFlow ─▶ Compose UI                     │
│    └─ stats(drop_ratio, lag) ─▶ UI + LogSink ─▶ Logcat              │
└─────────────────────────────────────────────────────────────────────┘
```

### Engineering rules (non-negotiable)

- **Zero-copy video.** The camera fills the codec's input *surface* directly
  (CameraX `SurfaceProvider`). No `ImageProxy` copies, no GL round-trip.
- **Encoder output is AVCC (length-prefixed).** Convert to Annex-B with a
  small NAL walker — never `Arrays.copyOfRange` per NAL.
- **Push never blocks.** `push_video`/`push_audio` return immediately; the
  core's bounded buffer drops oldest under pressure. Watch
  `stats.drop_ratio` instead of throttling.
- **One clock.** `pts_ms = (elapsedRealtimeNanos - originNanos) / 1_000_000`.
  Video uses the encoder's surface timestamps, audio is stamped at capture,
  the core rebases its origin (`resync()` is available for big jumps).
- **Low latency by construction.** `MAX_B_FRAMES=0`, 2 s IDR interval, CBR,
  AAC 20 ms frames, `KEY_REQUEST_SYNC_FRAME` on reconnect.
- **Keyframe discipline.** The core cuts to the live edge at keyframes; always
  request an IDR when the session reports `Reconnecting`.
- **No UI work off the main thread.** Listener callbacks arrive on the core's
  worker thread; hop to UI via `StateFlow`.
- **Bindings are committed, never regenerated in CI.** Regenerate only when the
  pinned core rev changes; the `bindings-check` workflow guards drift.

### Module map

```text
android/
├── rust/                    # thin crate pinning stream-ffi (git dep) → libstream_ffi.so
│   ├── Cargo.toml / Cargo.lock   # pinned core rev
│   ├── src/bin/uniffi_bindgen.rs # bindgen matching pinned core's uniffi 0.32
│   └── examples/ingest.rs        # host-side RTMP ingest (e2e + dev)
├── scripts/
│   ├── build-native.sh      # cargo-ndk → jniLibs
│   ├── generate-bindings.sh # uniffi-bindgen → java/uniffi (commit the result)
│   ├── ingest-server.sh     # run the core's RTMP server on host (e2e + dev)
│   └── install.sh           # fetch CI-built APK + adb install to a device
├── Makefile                 # native / bindings / apk / test / install
├── gradle/                  # wrapper + libs.versions.toml
├── app/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/strym/app/     # app code: session/settings/service/ui
│   ├── src/main/java/uniffi/            # COMMITTED Kotlin bindings (regenerate on core bump)
│   ├── src/test/                        # JVM unit tests (controller, mapping, formatting)
│   ├── src/androidTest/                 # instrumented lifecycle test (make android-test)
│   ├── src/main/jniLibs/<abi>/          # built by CI, git-ignored
│   └── proguard-rules.pro
└── gradlew / gradlew.bat
```

Toolchain: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0, Compose (BOM 2024.12),
CameraX 1.4, JNA (UniFFI runtime), minSdk 26, targetSdk 35, NDK 27,
`cargo-ndk`, pinned core rev `7e79c9c`.

---

## Phase A — Native build pipeline

Goal: an APK that loads `libstream_ffi.so`, constructs a `StreamSession`, and
drives its full lifecycle from Kotlin.

Done:

1. **Pin the core.** `rust/Cargo.toml` → `stream-ffi` git dep at rev `7e79c9c`;
   `[lib] name = "stream_ffi"` re-exports the scaffolding so the cdylib carries
   every UniFFI symbol under `libstream_ffi.so`. `Cargo.lock` committed.
2. **`scripts/build-native.sh`.** `cargo ndk -t arm64-v8a -t x86_64 -o
   app/src/main/jniLibs build -p stream_ffi --release` (debug: arm64 only).
3. **Bindings generated once and committed** (`generate-bindings.sh`, bindgen
   pinned to the core's uniffi 0.32).
4. **Gradle scaffold.** Wrapper, version catalog, `app` module with
   `jniLibs` + `java/uniffi` source sets, JNA dependency, R8 rules. JNA is pulled
   as `net.java.dev.jna:jna:5.14.0@aar` (the AAR bundles `libjnidispatch.so` per
   ABI — the plain JAR lacks Android natives and crashes at startup).
5. **CI-first.** `ci.yml` builds `.so` (cargo-ndk) + APK, runs unit tests,
   uploads the APK artifact, and self-prunes caches; `bindings-check.yml`
   diff-verifies committed bindings when the core rev changes.
6. **Smoke app.** `MainActivity` builds a `StreamSession` on the device and
   exercises `configure_codecs`/`push`/`stop`; instrumented test (Phase B
   scaffold) asserts the lifecycle.
7. **Device verification.** APK from CI installed on a phone (Mi A1, arm64-v8a)
   via adb; the smoke app runs the session lifecycle
   (`IDLE → CONNECTING → IDLE` on a dead loopback) without crashing, proving the
   `.so`, JNA dispatch lib, and bindings work on hardware. Added the missing
   `INTERNET` permission along the way.

Deferred to Phase B: the instrumented lifecycle test (`connectedDebugAndroidTest`)
— the smoke app already proves the lifecycle manually on the connected phone.

Exit criteria: CI builds an installable APK; `./scripts/install.sh` puts it on a
device; the smoke app reports the session state machine without crashing.

---

## Phase B — App shell & session UX ✅ (done)

Goal: a runnable app that manages the session and renders its state honestly.

Done:

1. **Foreground service.** `service/StreamService.kt` (type `camera|microphone`)
   owns the session; UI binds to it; notification reflects the current phase.
   POST_NOTIFICATIONS is asked opportunistically at go-live (never blocks the
   stream).
2. **Permissions flow.** `ui/permissions/PermissionGate.kt` gates CAMERA +
   RECORD_AUDIO with rationale states (re-check on resume, permanently denied
   → deep-link into system settings). No network/location asks.
3. **Settings screen.** `settings/SettingsRepository.kt` persists via DataStore
   (URL/app/masked stream key, 720p30 / 1080p30 presets, bitrate slider,
   `LatencyMode` segmented control, audio toggle).
4. **Live screen.** `ui/live/LiveScreen.kt`: CameraX preview (plain preview for
   now — Phase C swaps in the codec input surface), state badge, 1 Hz stats row
   (`bitrate_out_bps`, `drop_ratio`, `buffer_lag_ms`, `rtt_ms`, uptime),
   Start/Stop/Retry controls honoring session state.
5. **StreamController.** `session/StreamController.kt` maps
   `on_state_changed`/`on_stats` onto one `StateFlow<UiState>`; owns the
   reference clock (`elapsedRealtimeNanos` origin, `nowMs()` for Phase C/D
   PTS); converts `InvalidConfig`/`InvalidState`/`Engine` into user strings.
   Sits behind a `SessionGateway` seam (`RealSessionFactory` in production)
   so the state machine is JVM-unit-testable without the `.so`.
6. **Error surfacing.** Core error text shown verbatim with Retry; finite
   reconnect budget (8 attempts) makes `Exhausted` reachable → explicit
   "Try again" / "Give up"; failed initial connect shows the error +
   retry/give-up without a live badge.
7. **Tests.** JVM: controller state machine (10 cases), config mapping,
   stat formatting. Instrumented: `SessionLifecycleTest` — the deferred Phase A
   lifecycle test plus eager invalid-config rejection, run via `make
   android-test` on a device. CI runs the JVM suite with the APK build.

Exit criteria:

- [x] Unit tests green in CI; APK builds (verified by CI).
- [x] End-to-end state machine visible in UI against a dead endpoint
      (connect fails → Idle + error → retry) — device validation.
- [x] Stats tick every second while trying — device validation
      (the instrumented lifecycle test asserts stats ticks against a dead
      endpoint).
- [x] Invalid config is rejected with a user-readable error before any
      connect attempt (device validation).
- [x] Session reaches the live badge on a valid endpoint (device validation).

---

## Phase C — Video pipeline (implemented; device verification outstanding)

Goal: camera frames reach the network as H.264.

Done:

- [x] **Encoder surface via CameraX.** `VideoEncoder` creates `MediaCodec`
      (H.264, `COLOR_FormatSurface`, CBR, 2 s IDR, High profile with graceful
      fallback); `EncoderCapabilities` + `EncoderSizeSelector` clamp to the
      largest supported resolution ≤ preset; `CameraStreamer` implements a
      `Preview.SurfaceProvider` whose surface *is* `codec.createInputSurface()`
      → zero-copy.
- [x] **Codec config.** `AvcDecoderConfig.fromCsd` builds the
      `AVCDecoderConfigurationRecord` (SPS/PPS from csd-0/csd-1, matching the
      core's `build_avcc` byte for byte) → `configureCodecs(Some(avcc), None)`.
- [x] **NAL conversion.** `NalUnit.avccToAnnexBInPlace` rewrites length
      prefixes to `00 00 00 01` in place (no reallocation); keyframe from
      `BUFFER_FLAG_KEY_FRAME`.
- [x] **Push path.** Async `MediaCodec.Callback` on a dedicated `HandlerThread`;
      `VideoPts` rebases output `presentationTimeUs` to stream-relative ms
      (monotonic, frame-rate fallback when unstamped) → `push_video`.
- [x] **Wiring + resilience hooks.** `CameraStreamer` ↔ `StreamController`
      (`MediaIngest`) ↔ core `push_video`/`configure_codecs`, keyframe request
      on `Reconnecting`. JVM tests for NalUnit, VideoPts, AvcDecoderConfig,
      EncoderSizeSelector.
- [x] **Raw Camera2 dual-surface viewfinder (replaces CameraX preview).**
      `CameraController` opens the back camera directly and binds *both* the UI
      viewfinder (`TextureView`) and the encoder input surface in one capture
      session, so the preview keeps updating while streaming (no frozen frame).
      Surface swaps recreate the session; the aspect crop and the preview
      rotation/fill transform are pure math (`CameraMath`, JVM-tested). A
      `TextureView` is used because `SurfaceView` has no `setTransform` in the
      SDK.

Remaining:

- [x] **Rotation decision (revised).** The zero-copy surface path cannot rotate
      pixels, and the TextureView-matrix viewfinder it forced on us was a
      regression chain (sideways / squeeze / zoom per device). Replaced with a
      GL pipeline (`GlStreamer`): the camera feeds one SurfaceTexture; every
      frame is drawn — rotated upright, fill-cropped — into the viewfinder and
      (while live) the encoder input surface from one pure matrix builder.
      Portrait *and* landscape broadcasts now carry genuinely upright pixels;
      the shape is decided once at go-live from the device hold, which the UI
      locks while live. Going live attaches a render target and no longer
      reconfigures the camera session; the aspect selector and all
      SCALER_CROP_REGION math are gone. Device validation still pending:
- [ ] **Exit criteria (device).** Host-side RTMP ingest (`scripts/ingest-server.sh`)
      + `ffprobe`: `h264`, IDR cadence ≈2 s, correct resolution, monotonic PTS;
      plus on-device checks that the GL viewfinder is upright and undistorted
      in portrait and landscape holds, live and idle.

Exit criteria: host-side RTMP ingest server receives a stream; `ffprobe`
shows `h264` with IDR cadence ≈2 s and correct resolution; A/V PTS monotonic.

---

## Phase D — Audio pipeline & A/V sync (implemented; testing outstanding)

Goal: synchronized AAC audio.

Done:

- [x] **AudioRecord → MediaCodec AAC.** `capture/AudioRecorder.kt`: 48 kHz
      PCM16, stereo with a mono fallback (phone mics are mono; the FLV tag the
      core emits is stereo), AAC-LC CBR 128 k, AOT_LC, byte-buffer mode with
      explicit `presentationTimeUs`. Each `read()` is stamped with the
      monotonic clock; `StreamPts` rebases the encoder output to the same
      first-frame origin the video track uses (JVM-tested).
- [x] **ASC.** From csd-0 (`AudioSpecificConfig`) call
      `configure_codecs(None, Some(asc))`; raw AAC frames are pushed with no
      ADTS — the core owns ADTS for FLV. Wired via `StreamService.audio`,
      honoring the `audioEnabled` setting.

Remaining:

- [ ] **Sync check.** Instrumented test pushing a synthetic `push_video` +
      `push_audio` burst with known PTS into a loopback session, asserting the
      muxed output interleaves within one frame of tolerance. Needs an in-test
      loopback RTMP ingest server (the FFI surface is client-only), then a
      device run (`make android-test`).
- [ ] **Exit criteria (device).** Host-side ingest + `ffprobe`: AAC-LC + H.264,
      A/V PTS drift < 1 frame over 60 s.

Exit criteria: ingest output has AAC-LC + H.264; ffprobe A/V PTS drift < 1 frame
over 60 s.

---

## Phase E — End-to-end & resilience

Goal: a 30-minute YouTube Live-compatible stream from a real device.

Tasks:

1. **Local e2e harness.** `scripts/ingest-server.sh` runs the core's RTMP
   ingest server on the host; emulator publishes to `rtmp://10.0.2.2:1935/live`
   (device: LAN IP). Script ffprobes the server-side FLV, asserts keyframes,
   codecs, A/V PTS.
2. **Reconnect UX.** On `Reconnecting`, request `KEY_REQUEST_SYNC_FRAME`; UI
   shows a reconnecting badge; test by toggling the server off/on and asserting
   Live returns and playback on the viewer is uninterrupted (cut at keyframe).
3. **Backpressure behavior.** Saturate the network (throttle host) and assert
   the app keeps pushing, `drop_ratio` climbs, the stream stays live — no
   encoder deadlock, no UI freeze.
4. **Thermal/battery sanity.** 30 min at 720p30: CPU of the app < 30% (one
   core), battery drop reportable; screen-off via foreground service keeps
   streaming.

Exit criteria (public): a live YouTube stream runs **30+ minutes** from a
physical device with viewer playback showing no drift, no mid-stream freeze;
reconnect survives a Wi-Fi toggle.

---

## Phase F — Release hardening

Goal: Play-store-ready.

Tasks:

1. **Proguard/R8 rules** for UniFFI (keep native exports, `@Keep` bindings);
   shrink by arch + ABI split; verify a release APK on a clean device.
2. **Logging hardening (partial).** `LogSink` → logcat in debug plus a
   structured sink (`StrymLogSink`) that redacts the stream key before it
   reaches logcat or the ring buffer, so nothing sensitive leaves the app.
   Still open: a crash reporter (Crashlytics/Atlas) wired to the same sink.
3. **Error taxonomy (partial).** Every `StreamError` maps to a documented
   string in `res/values/strings.xml` (`error_invalid_config` /
   `error_invalid_state` / `error_engine`); the controller resolves them via an
   injected `Context.getString`-backed lambda so no message string lives in
   code. A "Report an issue" button in Settings shares the last N (already
   redacted) core log records.
4. **Telemetry + analytics** gated behind consent; privacy policy.
5. **Release checklist.** Signing, versioning (semver mirroring core),
   Play listing, automated UI smoke on release build.

Exit criteria: release APK passes the Phase E soak; crash-free 95%+ across a
device matrix (ARM64 mid/low end, Android 8–15).

---

## Testing strategy summary

| Layer | Tool | What it proves |
| --- | --- | --- |
| NAL/AVCC, PTS math | JUnit (JVM) | conversion correctness, clock rebase |
| StreamController state map | Robolectric | FFI states → UiState, error text |
| Lifecycle smoke | instrumented | .so loads, session start/stop |
| Local e2e | host ingest + ffprobe | real H.264/AAC, keyframes, sync |
| Resilience | server/Wi-Fi toggles | reconnect, backpressure, no freeze |
| Public soak | YouTube Live, physical device | 30-min stable, drift-free |

Every behavior change ships with a test — same rule as the core repo.

## Definition of done

- `./scripts/build-native.sh` && `generate-bindings.sh` reproducible from a
  clean checkout with only the pinned core tag.
- CI green on every PR (unit + instrumented smoke + local e2e).
- One physical-device YouTube Live soak per release, 30 min, logged.
