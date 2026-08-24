package com.strym.app.capture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import com.strym.app.settings.BroadcastSettings
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "StrymCameraStreamer"
private const val PREVIEW_ASPECT = 16f / 9f

/** Video is considered stalled when no encoded frame arrived for this long. */
private const val VIDEO_STALE_MS = 2_000L
/** Heartbeat cadence while video is stalled: one cached IDR per second. */
private const val HEARTBEAT_INTERVAL_MS = 1_000L

/**
 * Coordinates camera → encoder → session over raw Camera2 ([CameraController]).
 *
 * Dual-surface by design: the UI's viewfinder surface and the H.264 encoder's
 * input surface are both bound to the camera at once, so the preview keeps
 * showing live video while the stream runs. Idle → only the preview surface is
 * fed; live → the encoder surface joins it (zero-copy, no ImageProxy, no GL
 * round-trip). The camera stays open only while a preview surface is attached
 * or a stream is live, so capture is safe with the screen off and no
 * background-camera access is held otherwise.
 */
class CameraStreamer(context: Context) {

    private val controller = CameraController(context)

    @Volatile
    private var preview: Surface? = null
    private var previewSize: Size? = null

    private var encoder: VideoEncoder? = null
    private var encoderSize: Size? = null
    private var ingest: MediaIngest? = null
    private var onError: ((String) -> Unit)? = null
    private var encoding = false
    private var videoLog = 0

    // --- video heartbeat (Larix-style pause mode) ---------------------------
    // The ingest must never go silent: servers drop idle publishers, and a
    // silent video track is what leaves viewers on an endless spinner. While
    // real frames flow this costs nothing; the moment they stop (camera held
    // by the lock screen, OEM privacy pause, encoder stall) the heartbeat
    // re-sends the last cached keyframe at 1 fps so the connection stays
    // healthy and viewers see a frozen frame instead of a dead stream.
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "stry-heartbeat").apply {
            priority = Thread.MIN_PRIORITY + 1
            isDaemon = true
        }
    }

    @Volatile
    private var lastFrameWallMs = 0L

    @Volatile
    private var lastPushedDtsMs = -1L

    @Volatile
    private var lastKeyframe: ByteArray? = null

    @Volatile
    private var clock: SessionClock? = null

    private var lastHeartbeatWallMs = 0L

    /** A supported viewfinder size near the 16:9 encoder aspect. */
    fun choosePreviewSize(): Size? = controller.choosePreviewSize(PREVIEW_ASPECT)

    /** Sensor orientation in degrees; the UI rotates its viewfinder with it. */
    fun sensorOrientation(): Int = controller.sensorOrientation()

    /**
     * The UI's viewfinder surface. Passing null while idle releases the
     * camera — with no foreground service running, Android blocks background
     * camera access, so the camera only stays open while the UI is visible or
     * a stream is live.
     */
    fun setPreviewSurface(surface: Surface?, size: Size?) {
        preview = surface
        previewSize = size
        reconfigure()
    }

    /**
     * Create the encoder and feed its input surface to the camera alongside
     * the preview. Encoder output flows into [ingest]; fatal capture failures
     * are reported to [onError] (from which the caller tears the broadcast
     * down).
     */
    fun startEncoding(settings: BroadcastSettings, ingest: MediaIngest, onError: (String) -> Unit, clock: SessionClock) {
        if (encoding) return
        val preset = settings.preset
        val selection = EncoderCapabilities.select(preset.width, preset.height, settings.videoBitrateBps)
        if (selection == null) {
            onError("No H.264 encoder available on this device")
            return
        }
        if (selection.size.width != preset.width || selection.size.height != preset.height) {
            Log.w(TAG, "preset ${preset.width}x${preset.height} clamped to ${selection.size}")
        }
        val created = VideoEncoder(encoderListener, clock)
        // Wire the listener target before starting the encoder: its first
        // onOutputFormatChanged (SPS/PPS config) can fire as soon as start()
        // returns, and must not be dropped because ingest was still null.
        encoder = created
        encoderSize = selection.size
        this.ingest = ingest
        this.onError = onError
        try {
            created.start(
                VideoEncoder.Config(
                    width = selection.size.width,
                    height = selection.size.height,
                    framerate = preset.framerate.toFloat(),
                    bitrateBps = settings.videoBitrateBps,
                ),
                codecName = selection.codecName,
            )
        } catch (e: VideoEncoderException) {
            encoder = null
            encoderSize = null
            this.ingest = null
            this.onError = null
            created.stop()
            onError("Could not start the camera encoder: ${e.message}")
            return
        }
        encoding = true
        this.clock = clock
        lastFrameWallMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        startHeartbeat()
        reconfigure()
    }

    /**
     * Stop encoding and return the camera to the UI preview. The encoder is
     * released only once the camera no longer targets its surface (the
     * controller's onConfigured), so no surface is destroyed mid-capture.
     */
    fun stopEncoding() {
        if (!encoding) return
        encoding = false
        val stale = encoder
        val staleSize = encoderSize
        encoder = null
        encoderSize = null
        ingest = null
        onError = null
        reconfigure { stale?.stop() }
    }

    private fun startHeartbeat() {
        heartbeat.scheduleWithFixedDelay(
            {
                try {
                    pumpHeartbeat()
                } catch (t: Throwable) {
                    Log.w(TAG, "heartbeat pass failed", t)
                }
            },
            HEARTBEAT_INTERVAL_MS,
            500,
            TimeUnit.MILLISECONDS,
        )
    }

    /** One heartbeat pass: push a cached IDR when real video has gone quiet. */
    private fun pumpHeartbeat() {
        if (!encoding) return
        val sink = ingest ?: return
        val keyframe = lastKeyframe ?: return
        val nowMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        if (nowMs - lastFrameWallMs < VIDEO_STALE_MS) return
        if (nowMs - lastHeartbeatWallMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatWallMs = nowMs
        // Same wall-clock base the live track uses, clamped forward so the
        // heartbeat can never move the track's dts backwards.
        val origin = clock?.originMs ?: return
        val dtsMs = maxOf(nowMs - origin, lastPushedDtsMs + 1)
        Log.w(TAG, "video stale ${nowMs - lastFrameWallMs}ms; pushing IDR heartbeat dts=$dtsMs")
        sink.pushVideo(dtsMs, true, keyframe)
    }

    /** Request an IDR so viewers resync promptly after a reconnect. */
    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    /** Release the camera; safe after teardown. */
    fun close() {
        heartbeat.shutdownNow()
        encoder?.stop()
        encoder = null
        encoderSize = null
        controller.close()
    }

    private val encoderListener = object : VideoEncoder.Listener {
        override fun onCodecConfig(avcDecoderConfig: ByteArray) {
            ingest?.configureCodecs(avcDecoderConfig, null)
        }

        override fun onFrame(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray) {
            if (videoLog++ % 30 == 0) {
                Log.d(TAG, "VIDEO dts=$ptsMs wall=${SystemClock.elapsedRealtimeNanos() / 1_000_000L}")
            }
            lastFrameWallMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
            if (ptsMs > lastPushedDtsMs) lastPushedDtsMs = ptsMs
            if (isKeyframe) {
                // Cheap enough at one IDR per 2 s, and it is what the
                // heartbeat re-sends when the camera disappears.
                lastKeyframe = annexB.copyOf()
            }
            ingest?.pushVideo(ptsMs, isKeyframe, annexB)
        }

        override fun onError(message: String) {
            // Video problems are no longer fatal to the broadcast: the camera
            // controller recovers on its own and the heartbeat holds the
            // ingest in the meantime. Stopping the whole stream because one
            // leg hiccupped is exactly the behavior we are engineering out.
            Log.e(TAG, "(non-fatal) $message")
        }
    }

    private fun reconfigure(onConfigured: () -> Unit = {}) {
        controller.configure(
            preview = preview,
            previewSize = previewSize,
            encoder = encoder?.inputSurface(),
            encoderSize = encoderSize,
            onError = { message ->
                onError?.invoke(message)
                onConfigured()
            },
            onConfigured = onConfigured,
        )
    }
}
