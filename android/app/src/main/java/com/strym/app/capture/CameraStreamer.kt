package com.strym.app.capture

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import com.strym.app.settings.BroadcastSettings

private const val TAG = "StrymCameraStreamer"
private const val PREVIEW_ASPECT = 16f / 9f

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

    /** Request an IDR so viewers resync promptly after a reconnect. */
    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    /** Release the camera; safe after teardown. */
    fun close() {
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
            ingest?.pushVideo(ptsMs, isKeyframe, annexB)
        }

        override fun onError(message: String) {
            Log.e(TAG, message)
            onError?.invoke(message)
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
