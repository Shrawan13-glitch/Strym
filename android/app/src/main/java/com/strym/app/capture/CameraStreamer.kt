package com.strym.app.capture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
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
 * The camera feeds exactly one consumer: [GlStreamer]'s SurfaceTexture. The
 * GL pipeline then draws every frame into both the UI's viewfinder surface
 * and (while live) the H.264 encoder's input surface — rotated upright and
 * fill-cropped per target, so the preview always matches the stream and
 * portrait *or* landscape broadcasts carry genuinely upright pixels. Going
 * live attaches one render target; it never reconfigures the camera session,
 * and stopping detaches it before the codec is released.
 *
 * The camera stays open only while a preview surface is attached or a stream
 * is live, so capture is safe with the screen off and no background-camera
 * access is held otherwise.
 */
class CameraStreamer(context: Context) {

    private val controller = CameraController(context)
    private val gl = GlStreamer()

    /**
     * Display rotation, read lazily: a Service context has no display and
     * `Context.getDisplay()` throws for background contexts — asking at
     * construction time crashed every service start.
     */
    private fun displayRotationDegrees(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        return (wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0) * 90
    }

    @Volatile
    private var viewfinderAttached = false

    private var cameraStarted = false
    private var cameraSurface: Surface? = null

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

    /** Sensor orientation in degrees; the UI rotates its viewfinder with it. */
    fun sensorOrientation(): Int = controller.sensorOrientation()

    /**
     * The UI's viewfinder surface. Passing null while idle releases the
     * camera — with no foreground service running, Android blocks background
     * camera access, so the camera only stays open while the UI is visible or
     * a stream is live.
     */
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int, rotationDegrees: Int) {
        if (surface != null) {
            viewfinderAttached = true
            gl.setViewfinder(surface, width, height, rotationDegrees)
            ensureCamera()
        } else {
            viewfinderAttached = false
            gl.setViewfinder(null, 0, 0, 0)
            releaseCameraIfIdle()
        }
    }

    /** Refresh the viewfinder's size/rotation (display rotated or resized). */
    fun updatePreviewTransform(width: Int, height: Int, rotationDegrees: Int) {
        gl.updateViewfinder(width, height, rotationDegrees)
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
        startHeartbeat()
        // Upright the sensor frame for the hold captured at go-live; the
        // encoded canvas matches, so viewers see upright portrait or
        // landscape pixels without any rotation metadata.
        val upright = ((controller.sensorOrientation() - displayRotationDegrees()) % 360 + 360) % 360
        gl.setEncoder(created.inputSurface(), selection.size.width, selection.size.height, upright)
        ensureCamera()
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
            releaseCameraIfIdle()
        }
    }

    /** Start the camera toward the GL pipeline exactly once. */
    private fun ensureCamera() {
        if (cameraStarted) return
        cameraStarted = true
        // One sensor-native landscape buffer feeds everything downstream;
        // each render target crops/scales it independently on the GPU.
        val size = controller.chooseOutputSize(16f / 9f)
        val width = size?.width ?: 1280
        val height = size?.height ?: 720
        if (size == null) Log.w(TAG, "no camera output sizes; falling back to ${width}x${height}")
        gl.start(width, height) { surface ->
            cameraSurface = surface
            controller.configure(surface) { message -> reportCaptureError(message) }
        }
    }

    private fun releaseCameraIfIdle() {
        if (encoding || viewfinderAttached) return
        cameraStarted = false
        controller.configure(null) { }
    }

    /** Request an IDR so viewers resync promptly after a reconnect. */
    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    /** Release the camera and GL pipeline; safe after teardown. */
    fun close() {
        heartbeat.shutdownNow()
        encoder?.stop()
        encoder = null
        gl.close()
        controller.close()
    }

    private fun reportCaptureError(message: String) {
        onError?.invoke(message)
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
}
