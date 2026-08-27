package com.strym.app.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.strym.app.settings.BroadcastSettings
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "StrymCameraStreamer"

/** Video is considered stalled when no encoded frame arrived for this long. */
private const val VIDEO_STALE_MS = 2_000L
/** Heartbeat cadence while video is stalled: one cached IDR per second. */
private const val HEARTBEAT_INTERVAL_MS = 1_000L

/**
 * Coordinates camera → GL → encoder → session.
 *
 * CameraX owns the camera (see [CameraXEngine]): the UI's viewfinder is a
 * PreviewView the engine binds directly, and the GL pipeline ([GlStreamer])
 * receives the same camera through its own Preview use case. Going live
 * attaches the encoder as a GL render target — rotated upright and
 * fill-cropped for the hold captured at go-live, so viewers receive genuinely
 * upright portrait or landscape pixels. It never reconfigures the camera
 * session's use-case set beyond adding the GL feed, and stopping detaches it
 * before the codec is released.
 *
 * The camera stays bound only while the viewfinder is shown or a stream is
 * live, so capture is safe with the screen off and no background-camera
 * access is held otherwise.
 */
class CameraStreamer(private val context: Context, lifecycleOwner: LifecycleOwner) {

    private val gl = GlStreamer()
    private val engine = CameraXEngine(context, lifecycleOwner, gl)
    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Rear camera id, for characteristics queries only (CameraX opens it). */
    private val cameraId: String? by lazy { findRearCameraId() }

    /**
     * Display rotation, read lazily: a Service context has no display and
     * `Context.getDisplay()` throws for background contexts — asking at
     * construction time crashed every service start. Uses modern `Context.display`
     * on API 30+ to handle folding/multi-window; falls back to WindowManager on older.
     */
    private fun displayRotationDegrees(): Int {
        // Modern path: Service/Application may still have a display via context.display on API 30+.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val displayRotation = try {
                context.display?.rotation
            } catch (_: Exception) {
                null
            }
            if (displayRotation != null) return displayRotation * 90
        }
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            val rot = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            rot * 90
        } catch (_: Exception) {
            0
        }
    }

    private var encoder: VideoEncoder? = null
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
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }

    @Volatile
    private var lastFrameWallMs = 0L

    private val lastPushedDtsMs = java.util.concurrent.atomic.AtomicLong(-1L)

    @Volatile
    private var lastKeyframe: ByteArray? = null

    @Volatile
    private var clock: SessionClock? = null

    @Volatile
    private var lastHeartbeatWallMs = 0L

    @Volatile
    private var heartbeatScheduled = false
    private var heartbeatFuture: java.util.concurrent.ScheduledFuture<*>? = null

    /** Sensor orientation in degrees of clockwise rotation to upright. */
    fun sensorOrientation(): Int = cameraId?.let { id ->
        runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull()
    } ?: 0

    /** Show the UI viewfinder; CameraX handles every display transform. */
    fun attachPreview(provider: Preview.SurfaceProvider) {
        engine.attachDisplay(provider)
    }

    /** Stop showing the viewfinder; a live broadcast keeps its GL feed. */
    fun detachPreview() {
        engine.detachDisplay(keepCapture = encoding)
    }

    /**
     * Create the encoder and attach its input surface to the GL pipeline;
     * [portrait] fixes the encoded shape (and its uprighting rotation) at
     * go-live. Encoder output flows into [ingest]; fatal capture failures are
     * reported to [onError].
     */
    fun startEncoding(
        settings: BroadcastSettings,
        portrait: Boolean,
        ingest: MediaIngest,
        onError: (String) -> Unit,
        clock: SessionClock,
    ) {
        if (encoding) return
        val preset = settings.preset
        val (outWidth, outHeight) = preset.outputSize(portrait)
        val selection = EncoderCapabilities.select(outWidth, outHeight, settings.videoBitrateBps)
        if (selection == null) {
            onError("No H.264 encoder available on this device")
            return
        }
        if (selection.size.width != outWidth || selection.size.height != outHeight) {
            Log.w(TAG, "requested ${outWidth}x$outHeight clamped to ${selection.size}")
        }
        val created = VideoEncoder(encoderListener, clock)
        // Wire the listener target before starting the encoder: its first
        // onOutputFormatChanged (SPS/PPS config) can fire as soon as start()
        // returns, and must not be dropped because ingest was still null.
        encoder = created
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
            this.ingest = null
            this.onError = null
            created.stop()
            onError("Could not start the camera encoder: ${e.message}")
            return
        }
        encoding = true
        this.clock = clock
        lastFrameWallMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        lastHeartbeatWallMs = 0L
        lastPushedDtsMs.set(-1L)
        lastKeyframe = null
        videoLog = 0
        startHeartbeat()
        // Upright the sensor frame for the hold captured at go-live; the
        // encoded canvas matches, so viewers see upright portrait or
        // landscape pixels without any rotation metadata. (Pre-rotating HALs
        // get their half-turn from the GL pipeline's ST classification.)
        val upright = ((sensorOrientation() - displayRotationDegrees()) % 360 + 360) % 360
        gl.setEncoder(created.inputSurface(), selection.size.width, selection.size.height, upright)
        engine.ensureCapture()
    }

    /**
     * Stop encoding: detach the GL target first — its callback is the point
     * where releasing the codec can no longer race an in-flight draw.
     */
    fun stopEncoding() {
        if (!encoding) return
        encoding = false
        val stale = encoder
        encoder = null
        ingest = null
        onError = null
        gl.removeEncoder {
            stale?.stop()
            engine.releaseCapture()
        }
    }

    /** Request an IDR so viewers resync promptly after a reconnect. */
    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    /** Release the GL pipeline and camera bindings; safe after teardown. */
    fun close() {
        try { heartbeatFuture?.cancel(true) } catch (_: Exception) {}
        heartbeatFuture = null
        heartbeatScheduled = false
        heartbeat.shutdownNow()
        encoder?.stop()
        encoder = null
        gl.close()
        engine.release()
    }

    private fun findRearCameraId(): String? {
        val ids = runCatching { cameraManager.cameraIdList }.getOrNull() ?: return null
        return ids.firstOrNull { id ->
            runCatching {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        } ?: ids.firstOrNull()
    }

    private fun startHeartbeat() {
        if (heartbeatScheduled) return
        heartbeatScheduled = true
        heartbeatFuture = heartbeat.scheduleWithFixedDelay(
            {
                try {
                    pumpHeartbeat()
                } catch (t: Throwable) {
                    Log.w(TAG, "heartbeat pass failed", t)
                }
            },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** One heartbeat pass: push a cached IDR when real video has gone quiet. */
    private fun pumpHeartbeat() {
        if (!encoding) return
        val sink = ingest ?: return
        // Don't heartbeat if we haven't yet produced a keyframe (stream not yet primed).
        val keyframe = lastKeyframe ?: return
        val nowMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        val staleFor = nowMs - lastFrameWallMs
        if (staleFor < VIDEO_STALE_MS) return
        if (nowMs - lastHeartbeatWallMs < HEARTBEAT_INTERVAL_MS) return
        // Double-check encoding still true after time checks to avoid race with stopEncoding.
        if (!encoding) return
        lastHeartbeatWallMs = nowMs
        // Same wall-clock base the live track uses, clamped forward so the
        // heartbeat can never move the track's dts backwards. Add explicit
        // monotonic clamp against last pushed to handle concurrent encoder frame.
        val origin = clock?.originMs ?: return
        val wallDts = nowMs - origin
        // Guard against wall clock going backwards (should not happen with elapsedRealtime, but be safe)
        if (wallDts < 0) return
        val last = lastPushedDtsMs.get()
        val dtsMs = maxOf(wallDts, last + HEARTBEAT_INTERVAL_MS)
        // CAS to prevent duplicate DTS from concurrent video frame
        if (!lastPushedDtsMs.compareAndSet(last, dtsMs)) {
            // Another frame advanced the clock concurrently; retry next tick
            return
        }
        Log.w(TAG, "video stale ${staleFor}ms; pushing IDR heartbeat dts=$dtsMs keyframe=${keyframe.size}B")
        try {
            sink.pushVideo(dtsMs, true, keyframe)
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat push failed", e)
            // Roll back on failure to allow retry
            lastPushedDtsMs.compareAndSet(dtsMs, last)
        }
    }

    private val encoderListener = object : VideoEncoder.Listener {
        override fun onCodecConfig(avcDecoderConfig: ByteArray) {
            ingest?.configureCodecs(avcDecoderConfig, null)
        }

        override fun onFrame(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray) {
            if (videoLog++ % 30 == 0) {
                Log.d(TAG, "VIDEO dts=$ptsMs wall=${SystemClock.elapsedRealtimeNanos() / 1_000_000L} key=$isKeyframe size=${annexB.size}")
            }
            lastFrameWallMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
            // Atomic max to handle heartbeat race
            while (true) {
                val cur = lastPushedDtsMs.get()
                if (ptsMs <= cur) break
                if (lastPushedDtsMs.compareAndSet(cur, ptsMs)) break
            }
            if (isKeyframe) {
                // Cheap enough at one IDR per 2 s, and it is what the
                // heartbeat re-sends when the camera disappears.
                lastKeyframe = annexB.copyOf()
            }
            try {
                ingest?.pushVideo(ptsMs, isKeyframe, annexB)
            } catch (e: Exception) {
                Log.w(TAG, "pushVideo failed dts=$ptsMs", e)
            }
        }

        override fun onError(message: String) {
            // Video problems are no longer fatal to the broadcast: the camera
            // controller recovers on its own and the heartbeat holds the
            // ingest in the meantime. Stopping the whole stream because one
            // leg hiccupped is exactly the behavior we are engineering out.
            Log.e(TAG, "(non-fatal) $message")
        }
    }
}
