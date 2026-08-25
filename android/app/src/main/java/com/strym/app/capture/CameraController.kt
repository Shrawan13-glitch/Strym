package com.strym.app.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

private const val TAG = "StrymCamera"
private const val MAX_SESSION_FAILURES = 3
private const val PREVIEW_MAX_WIDTH = 1920

/** Bounded camera-reopen policy: some OEMs (and lock screens) drop the
 *  camera device mid-stream; the stream must survive by re-opening instead of
 *  tearing the broadcast down. */
private const val MAX_REOPEN_ATTEMPTS = 5
private const val REOPEN_BASE_DELAY_MS = 300L

/**
 * Raw Camera2 capture-session manager with exactly one output: the GL
 * pipeline's [SurfaceTexture] surface ([GlStreamer]). The viewfinder and the
 * H.264 encoder are both fed *from* that pipeline, so the camera never needs
 * to know about either — going live no longer reconfigures the capture
 * session at all, and idle vs live is purely which render targets are
 * attached downstream.
 *
 * All state is confined to a dedicated [HandlerThread]. A bounded reopen
 * policy survives lock screens and OEM privacy pauses without killing the
 * broadcast; [CameraStreamer]'s heartbeat holds the ingest meanwhile.
 */
class CameraController(private val context: Context) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("stry-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executor { cameraHandler.post(it) }

    private val cameraId: String? by lazy { findRearCameraId() }

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** The surface the camera must feed; null releases the camera. */
    @Volatile
    private var target: Surface? = null

    /** The surface the active session was built for. */
    private var boundSurface: Surface? = null
    private var creating = false
    private var consecutiveFailures = 0

    /** Reopen attempts since the last successful session configuration. */
    private var reopenAttempts = 0

    /** True while a delayed reopen is queued on [cameraHandler]. */
    private var reopenPending = false

    private var onError: ((String) -> Unit)? = null

    /**
     * Point the camera at [surface] (null stops capture). [onError] reports
     * fatal problems.
     */
    fun configure(surface: Surface?, onError: (String) -> Unit) {
        cameraHandler.post {
            this.onError = onError
            target = surface
            applyTarget()
        }
    }

    /** Tear everything down and stop the camera thread. Idempotent. */
    fun close() {
        cameraHandler.post {
            target = null
            session?.close()
            session = null
            camera?.close()
            camera = null
            onError = null
        }
        cameraThread.quitSafely()
    }

    /** A supported camera-output size near [aspect], ≤ [maxWidth] wide. */
    fun chooseOutputSize(aspect: Float, maxWidth: Int = PREVIEW_MAX_WIDTH): Size? {
        val map = characteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        if (sizes.isEmpty()) return null
        val chosen = choosePreviewSize(
            sizes.map { it.width to it.height },
            aspect,
            maxWidth,
        ) ?: return null
        return Size(chosen.first, chosen.second)
    }

    /** Sensor mounting orientation (degrees of clockwise rotation to upright). */
    fun sensorOrientation(): Int = characteristics()
        ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    // --- state machine (all on cameraHandler) --------------------------------

    private fun applyTarget() {
        if (target == null) {
            if (creating) return // settle the in-flight creation; onClosed cleans up
            session?.close()
            session = null
            boundSurface = null
            camera?.close()
            camera = null
            return
        }
        when {
            camera == null -> openCamera()
            session == null && !creating -> createSession()
        }
    }

    private fun openCamera() {
        val id = cameraId ?: return notifyError("No back camera found on this device")
        try {
            cameraManager.openCamera(id, cameraCallback, cameraHandler)
        } catch (e: SecurityException) {
            notifyError("The camera permission was revoked")
        } catch (e: CameraAccessException) {
            // Transiently held cameras (another app, an OEM privacy lock while
            // the screen turns off) surface here; recover like a disconnect.
            if (!scheduleReopen("Could not open the camera (${e.message})")) {
                notifyError("Could not open the camera: ${e.message}")
            }
        }
    }

    /**
     * Queue a bounded, backed-off reopen of the camera. Returns false when
     * recovery does not apply (nothing wants the camera, or the retry budget
     * is exhausted and the caller must surface the failure). The stream keeps
     * running meanwhile — [CameraStreamer]'s heartbeat holds the ingest.
     */
    private fun scheduleReopen(why: String): Boolean {
        if (target == null) return false
        if (reopenAttempts >= MAX_REOPEN_ATTEMPTS) {
            reopenAttempts = 0
            return false
        }
        if (reopenPending) return true
        reopenPending = true
        val delay = REOPEN_BASE_DELAY_MS shl reopenAttempts.coerceAtMost(4)
        reopenAttempts++
        Log.w(TAG, "$why; reopening in ${delay}ms (attempt $reopenAttempts/$MAX_REOPEN_ATTEMPTS)")
        cameraHandler.postDelayed(
            {
                reopenPending = false
                if (target != null && camera == null) openCamera()
            },
            delay,
        )
        return true
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            Log.i(TAG, "camera opened")
            camera = device
            if (session == null && !creating) createSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            camera = null
            runCatching { device.close() }
            // Lock screens and OEM power managers drop the device mid-stream;
            // that is a pause to survive, not a reason to end the broadcast.
            if (!scheduleReopen("The camera disconnected")) {
                notifyError("The camera disconnected")
            }
        }

        override fun onError(device: CameraDevice, error: Int) {
            camera = null
            runCatching { device.close() }
            if (!scheduleReopen("Camera error $error")) {
                notifyError("Camera error $error")
            }
        }
    }

    private fun createSession() {
        val device = camera ?: return
        val surface = target ?: return
        creating = true
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)),
                        executor,
                        sessionCallback,
                    ),
                )
            } else {
                device.createCaptureSession(listOf(surface), sessionCallback, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            creating = false
            notifyError("Could not configure the camera: ${e.message}")
        } catch (e: IllegalArgumentException) {
            creating = false
            notifyError("The camera rejected the output surface: ${e.message}")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(capturing: CameraCaptureSession) {
            creating = false
            consecutiveFailures = 0
            reopenAttempts = 0
            session = capturing
            boundSurface = target
            Log.i(TAG, "session configured; starting repeating request")
            startRepeating(capturing)
        }

        override fun onConfigureFailed(capturing: CameraCaptureSession) {
            creating = false
            runCatching { capturing.close() }
            if (++consecutiveFailures >= MAX_SESSION_FAILURES) {
                notifyError("The camera could not be attached to the stream")
            } else if (camera != null && target != null) {
                createSession()
            }
        }

        override fun onClosed(capturing: CameraCaptureSession) {
            if (session === capturing) session = null
            creating = false
            if (target != null && boundSurface != target) createSession()
        }
    }

    private fun startRepeating(capturing: CameraCaptureSession) {
        val device = camera ?: return
        val request = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(boundSurface ?: return)
            }
        } catch (e: CameraAccessException) {
            notifyError("Could not start the camera: ${e.message}")
            return
        }
        runCatching { capturing.setRepeatingRequest(request.build(), null, cameraHandler) }
            .onFailure { Log.e(TAG, "setRepeatingRequest failed", it) }
            .onSuccess { Log.i(TAG, "repeating request live") }
    }

    private fun notifyError(message: String) {
        Log.e(TAG, message)
        onError?.invoke(message)
    }

    private fun characteristics(): CameraCharacteristics? {
        val id = cameraId ?: return null
        return runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
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
}
