package com.strym.app.capture

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "StrymCameraX"

/**
 * CameraX front end: owns the camera through [ProcessCameraProvider] bound to
 * the streaming service's lifecycle. Two Preview use cases draw from one
 * camera:
 *
 *  - the **display** preview → the UI's [androidx.camera.view.PreviewView]
 *    surface (CameraX handles every rotation/crop/OEM quirk itself), and
 *  - the **GL** preview → [GlStreamer]'s SurfaceTexture, the encoder feed,
 *    sized near 1080p via a resolution strategy.
 *
 * Binding is use-case-granular: the broadcast survives the screen turning off
 * (display unbound on pause, GL stays), and with no stream and no UI the
 * camera is fully released.
 */
class CameraXEngine(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val gl: GlStreamer,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** CameraX provider, initialized eagerly on the main thread. */
    private val provider = scope.async {
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                },
                mainExecutor,
            )
        }
    }

    @Volatile
    private var displayProvider: Preview.SurfaceProvider? = null

    @Volatile
    private var glWanted = false

    /** Rebind the use-case set to match the current wants. Main thread. */
    private suspend fun rebind() {
        val cameraProvider = provider.await()
        cameraProvider.unbindAll()
        val useCases = mutableListOf<Preview>()
        displayProvider?.let { display ->
            Preview.Builder().build().also { useCase ->
                useCase.setSurfaceProvider(display)
                useCases += useCase
            }
        }
        if (glWanted) {
            Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build(),
                )
                .build()
                .also { useCase ->
                    useCase.setSurfaceProvider(::onGlSurfaceRequest)
                    useCases += useCase
                }
        }
        if (useCases.isNotEmpty()) {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                *useCases.toTypedArray(),
            )
        }
        Log.i(TAG, "camera bound: display=${displayProvider != null} gl=$glWanted")
    }

    private fun launchRebind() {
        scope.launch { runCatching { rebind() }.onFailure(::logFailure) }
    }

    private fun logFailure(t: Throwable) {
        Log.e(TAG, "camera binding failed", t)
    }

    /** Show the viewfinder ([androidx.camera.view.PreviewView]'s provider). */
    fun attachDisplay(provider: Preview.SurfaceProvider) {
        displayProvider = provider
        launchRebind()
    }

    /**
     * Stop showing the viewfinder. [keepCapture] keeps the GL (encoder) feed
     * alive — true while a broadcast runs, so screen-off never starves it.
     */
    fun detachDisplay(keepCapture: Boolean) {
        displayProvider = null
        if (!keepCapture) glWanted = false
        launchRebind()
    }

    /** Ensure the GL feed is bound (go-live). Display stays as the UI left it. */
    fun ensureCapture() {
        glWanted = true
        launchRebind()
    }

    /** Unbind the GL feed (broadcast over); display stays as the UI left it. */
    fun releaseCapture() {
        glWanted = false
        launchRebind()
    }

    /** Release everything; the service lifecycle's destruction also unbinds. */
    fun release() {
        scope.cancel()
    }

    private fun onGlSurfaceRequest(request: SurfaceRequest) {
        val size = request.resolution
        gl.obtainCameraSurface(size.width, size.height) { surface ->
            // A cancelled request tolerates a late provideSurface; the result
            // listener simply reports it as cancelled.
            request.provideSurface(surface, mainExecutor) { }
        }
    }
}
