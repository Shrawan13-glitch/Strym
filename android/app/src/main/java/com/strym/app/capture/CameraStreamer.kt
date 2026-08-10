package com.strym.app.capture

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.strym.app.settings.BroadcastSettings
import java.util.concurrent.Executor

private const val TAG = "StrymCameraStreamer"

/**
 * Coordinates camera → encoder → session.
 *
 * All CameraX use cases bind against the owner's lifecycle (the foreground
 * service), so capture keeps running with the screen off. While idle the
 * camera feeds the UI's preview surface; while live the camera renders
 * straight into the encoder's input surface — zero-copy, no ImageProxy, no
 * GL round-trip.
 */
class CameraStreamer(
    context: Context,
    lifecycleOwner: LifecycleOwner,
) {

    /** The slice of the session the capture pipeline feeds. */
    interface MediaIngest {
        fun configureCodecs(avcDecoderConfig: ByteArray?, audioSpecificConfig: ByteArray?)

        fun pushVideo(ptsMs: Long, isKeyframe: Boolean, annexB: ByteArray)
    }

    private val appContext = context.applicationContext
    private val owner: LifecycleOwner = lifecycleOwner
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val providerFuture = ProcessCameraProvider.getInstance(appContext)

    private var previewSurface: Preview.SurfaceProvider? = null
    private var encoder: VideoEncoder? = null
    private var ingest: MediaIngest? = null
    private var onError: ((String) -> Unit)? = null
    private var encoding = false

    init {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                stopEncoding()
            }
        })
    }

    /**
     * The UI's preview surface. Used whenever the pipeline is not encoding.
     * Passing null while idle releases the camera — with no foreground
     * service running, Android blocks background camera access, so the
     * camera only stays open while the UI is visible or a stream is live.
     */
    fun setPreviewSurface(provider: Preview.SurfaceProvider?) {
        previewSurface = provider
        if (!encoding) {
            if (provider == null) unbindCamera() else bindPreview()
        }
    }

    /**
     * Create the encoder and point the camera at its input surface. Encoder
     * output flows into [ingest]; fatal capture failures are reported to
     * [onError] (from which the caller should tear the broadcast down).
     */
    fun startEncoding(settings: BroadcastSettings, ingest: MediaIngest, onError: (String) -> Unit) {
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
        val created = VideoEncoder(encoderListener)
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
            created.stop()
            onError("Could not start the camera encoder: ${e.message}")
            return
        }
        encoder = created
        this.ingest = ingest
        this.onError = onError
        encoding = true
        bindToEncoder(selection.size, created)
    }

    /** Stop encoding and return the camera to the UI preview. */
    fun stopEncoding() {
        if (!encoding && encoder == null) return
        encoding = false
        ingest = null
        onError = null
        encoder?.stop()
        encoder = null
        if (previewSurface != null) bindPreview() else unbindCamera()
    }

    /** Request an IDR so viewers resync promptly after a reconnect. */
    fun requestKeyframe() {
        encoder?.requestKeyframe()
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

    private fun bindPreview() {
        val provider = previewSurface ?: return
        withCameraProvider { cameraProvider ->
            runCatching {
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(provider)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            }.onFailure { Log.e(TAG, "preview bind failed", it) }
        }
    }

    private fun unbindCamera() {
        withCameraProvider { cameraProvider ->
            runCatching { cameraProvider.unbindAll() }
        }
    }

    private fun bindToEncoder(size: Size, videoEncoder: VideoEncoder) {
        withCameraProvider { cameraProvider ->
            runCatching {
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    size,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                preview.setSurfaceProvider(EncoderSurfaceProvider(videoEncoder.inputSurface(), mainExecutor))
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            }.onFailure { failure ->
                Log.e(TAG, "camera → encoder bind failed", failure)
                stopEncoding()
                onError?.invoke("The camera could not be attached to the encoder")
            }
        }
    }

    private fun withCameraProvider(action: (ProcessCameraProvider) -> Unit) {
        // addListener runs immediately when the future is already done.
        providerFuture.addListener(
            {
                runCatching { action(providerFuture.get()) }
                    .onFailure { Log.e(TAG, "camera provider unavailable", it) }
            },
            mainExecutor,
        )
    }

    /** Hands the encoder's input surface to CameraX as the preview target. */
    private class EncoderSurfaceProvider(
        private val surface: Surface,
        private val executor: Executor,
    ) : Preview.SurfaceProvider {
        override fun onSurfaceRequested(request: SurfaceRequest) {
            request.provideSurface(surface, executor) { result ->
                if (result.resultCode != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                    Log.w(TAG, "encoder surface rejected: ${result.resultCode}")
                }
            }
        }
    }
}
