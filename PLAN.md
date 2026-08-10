# `stream-platform` PLAN — Android live-streaming app

Roadmap for the Android publisher app that embeds the [core] via its frozen
UniFFI facade. This is a **separate repo** from the core; the core is consumed
read-only as a pinned git dependency.

State: **Phase A in progress**. Core API 1.0 frozen; native build pipeline
being wired.

---

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
- **Generated artifacts never committed.** `libstream_ffi.so`,
  `uniffi/*.kt`, and build outputs are git-ignored.

### Module map

```text
android/
├── rust/                    # thin crate pinning stream-ffi (git dep) → libstream_ffi.so
│   └── Cargo.toml
├── scripts/
│   ├── build-native.sh      # cargo-ndk → jniLibs
│   ├── generate-bindings.sh # uniffi-bindgen --library → java/uniffi
│   └── ingest-server.sh     # run the core's RTMP server on host (e2e + dev)
├── app/
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/stream/platform/
│   │   ├── StreamApp.kt             # Application: set_log_sink, max level
│   │   ├── service/StreamService.kt # foreground service owning the session
│   │   ├── session/StreamController.kt  # coordinator, clock, listener→StateFlow
│   │   ├── capture/CameraFeed.kt    # CameraX + encoder surface provider
│   │   ├── capture/AudioFeed.kt     # AudioRecord loop
│   │   ├── encode/VideoEncoder.kt   # MediaCodec H.264 wrapper
│   │   ├── encode/AudioEncoder.kt   # MediaCodec AAC wrapper
│   │   ├── avc/NalUnit.kt           # AVCC↔Annex-B, AVCDecoderConfig builder
│   │   └── ui/                      # Compose: connect screen, live screen
│   └── src/main/java/uniffi/        # GENERATED Kotlin bindings (ignored)
│   └── src/main/jniLibs/<abi>/      # GENERATED .so (ignored)
└── gradle/libs.versions.toml
```

Toolchain: AGP 8.5+, Kotlin 2.0, Compose BOM, minSdk 26, targetSdk 35, NDK 27,
`cargo-ndk`, pinned core tag `v1.0.0`.

---

## Phase A — Native build pipeline

Goal: an APK that loads `libstream_ffi.so`, constructs a `StreamSession`, and
drives its full lifecycle from Kotlin.

Tasks:

1. **Pin the core.** `rust/Cargo.toml` depends on
   `stream-ffi = { git = "<core>", tag = "v1.0.0" }`; `[lib] name = "stream_ffi"`
   and re-export the scaffolding (`pub use stream_ffi::*;`) so the cdylib carries
   every UniFFI symbol under `libstream_ffi.so`.
2. **`scripts/build-native.sh`.** `cargo ndk -t arm64-v8a [-t x86_64] -o
   app/src/main/jniLibs build -p stream_ffi --release` (debug builds use
   arm64-v8a only for speed). Validate with `file`/`readelf` that symbols exist.
3. **`scripts/generate-bindings.sh`.** `uniffi-bindgen generate --language
   kotlin --library <debug .so> --out-dir app/src/main/java`.
4. **Gradle wiring.** Version catalog; `cargo-ndk` as a preBuild task; a
   `genBindings` task; `sourceSets` including `java/uniffi`; `loadLibrary`.
5. **Smoke test (instrumented).** Build a `StreamSession` with a loopback
   `SessionConfig`, call `start()`, observe `Connecting`, `stop()`, assert
   `Stopped`. Run on an emulator via `gradle connectedDebugAndroidTest`.

Exit criteria: green instrumented lifecycle smoke test on arm64 emulator; CI
(GitHub Actions: rustup + NDK + cargo-ndk) builds the APK and runs the test.

---

## Phase B — App shell & session UX

Goal: a runnable app that manages the session and renders its state honestly.

Tasks:

1. **Foreground service.** `StreamService` (type `camera|microphone`, POST_NOTIFICATIONS
   on 33+) keeps the session alive with screen off; UI binds to it.
2. **Permissions flow.** CAMERA, RECORD_AUDIO, INTERNET; Compose UI with
   rationale states (shouldShowRequestPermissionRationale, permanently denied).
3. **Settings screen.** URL/app/stream-key, video presets (720p30 / 1080p30),
   bitrate, latency mode (`LatencyMode`), audio-on toggle. Persisted via
   `DataStore`. Stream key rendered masked.
4. **Live screen.** Camera preview (the codec input surface), state badge
   (Idle/Connecting/Live/Reconnecting/Exhausted/Stopped), live stats row
   (`bitrate_out_bps`, `drop_ratio`, `buffer_lag_ms`, `rtt_ms`, uptime),
   Start/Stop/Retry buttons honoring session state.
5. **StreamController.** Maps `on_state_changed`/`on_stats` onto a single
   `StateFlow<UiState>`; owns the reference clock; converts FFI exceptions
   (`InvalidConfig`, `InvalidState`, `Engine`) into user-readable messages.
6. **Error surfacing.** `last_error()` shown with a Retry path; `Exhausted`
   state maps to explicit "Give up" / "Try again".

Exit criteria: end-to-end state machine visible in UI against a dead endpoint
(connect fails → Idle + error → retry); stats tick every second while trying.

---

## Phase C — Video pipeline

Goal: camera frames reach the network as H.264.

Tasks:

1. **Encoder surface via CameraX.** Create `MediaCodec` (H.264, `COLOR_FormatSurface`,
   `BITRATE_MODE_CBR`, `I_FRAME_INTERVAL=2`, `MAX_B_FRAMES=0`, High profile).
   Query `CodecCapabilities` for the largest supported resolution ≤ preset and
   match CameraX `Preview` resolution to it. Implement a `Preview.SurfaceProvider`
   whose surface *is* `codec.createInputSurface()` → zero-copy.
2. **Codec config.** On first `BUFFER_FLAG_CODEC_CONFIG`, take csd-0/csd-1
   (SPS/PPS), build the `AVCDecoderConfigurationRecord` and call
   `configure_codecs(Some(avcc), None)`.
3. **NAL conversion.** `NalUnit.avccToAnnexB(buffer)`: walk length-prefixed NALs,
   rewrite lengths to `00 00 00 01`, emit into a single reusable
   `ByteBuffer`/`ByteArray`. Keyframe = NAL type 5 present (or
   `BUFFER_FLAG_KEY_FRAME` — use the buffer flag, faster).
4. **Push path.** On each output buffer (callback mode on a dedicated
   `HandlerThread`), compute `pts_ms` from the output's `presentationTimeUs`
   against the shared clock origin and call `push_video(pts_ms, is_keyframe,
   annexB)`. Reuse/recycle the converted buffer; MediaCodec returns its own
   buffers via `releaseOutputBuffer`.
5. **Rotation.** Encoder configured with the natural (landscape) resolution;
   CameraX handles orientation via `setSurfaceRotation`/transform matrix so the
   encoded stream is upright.

Exit criteria: host-side RTMP ingest server receives a stream; `ffprobe`
shows `h264` with IDR cadence ≈2 s and correct resolution; A/V PTS monotonic.

---

## Phase D — Audio pipeline & A/V sync

Goal: synchronized AAC audio.

Tasks:

1. **AudioRecord → MediaCodec AAC.** 48 kHz stereo AAC-LC, CBR 128 k, AOT_LC,
   20 ms input frames fed via byte-buffer mode with explicit
   `presentationTimeUs`. Capture timestamp at `read()` time (same clock).
2. **ASC.** From csd-0 (`AudioSpecificConfig`) call
   `configure_codecs(None, Some(asc))`; push raw AAC frames (no ADTS — the core
   owns ADTS for FLV).
3. **Sync check.** Write an instrumented test that pushes a synthetic
   `push_video`+`push_audio` burst with known PTS into a loopback session and
   asserts the muxed output interleaves within one frame of tolerance.

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
2. **Crash reporting.** Wire `LogSink` → Logcat in debug, a structured sink
   (Crashlytics/Atlas) in release; no stream key ever logged (redact in the
   sink).
3. **Error taxonomy.** Map every `StreamError` to a documented user string in
   `res/values/strings.xml`; add a "report issue" export of last N log records.
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
